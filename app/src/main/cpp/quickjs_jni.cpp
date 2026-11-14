#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "quickjs.h"

#define LOG_TAG "QuickJsJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr size_t kMemoryLimit = 32u * 1024u * 1024u;
constexpr size_t kStackLimit = 2u * 1024u * 1024u;

struct JsInstance {
    JSRuntime * rt = nullptr;
    JSContext * ctx = nullptr;
    std::atomic<bool> cancelled{false};
    JavaVM * vm = nullptr;
    jobject host = nullptr;
    jmethodID call_tool = nullptr;
    std::string logs;

    ~JsInstance() {
        if (ctx != nullptr) {
            JS_FreeContext(ctx);
            ctx = nullptr;
        }
        if (rt != nullptr) {
            JS_FreeRuntime(rt);
            rt = nullptr;
        }
        if (host != nullptr && vm != nullptr) {
            JNIEnv * env = nullptr;
            if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env != nullptr) {
                env->DeleteGlobalRef(host);
            }
            host = nullptr;
        }
    }
};

inline JsInstance * from_handle(jlong handle) {
    return reinterpret_cast<JsInstance *>(static_cast<intptr_t>(handle));
}

std::string jstring_to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return "";
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return "";
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

jstring utf8_to_jstring(JNIEnv * env, const std::string & value) {
    std::vector<jchar> utf16;
    utf16.reserve(value.size());
    size_t i = 0;
    while (i < value.size()) {
        const auto b0 = static_cast<unsigned char>(value[i]);
        uint32_t cp = 0xFFFD;
        size_t extra = 0;
        if (b0 < 0x80) {
            cp = b0;
        } else if ((b0 & 0xE0) == 0xC0) {
            cp = b0 & 0x1F;
            extra = 1;
        } else if ((b0 & 0xF0) == 0xE0) {
            cp = b0 & 0x0F;
            extra = 2;
        } else if ((b0 & 0xF8) == 0xF0) {
            cp = b0 & 0x07;
            extra = 3;
        }
        bool valid = extra == 0 || i + extra < value.size();
        for (size_t k = 1; valid && k <= extra; ++k) {
            const auto bn = static_cast<unsigned char>(value[i + k]);
            if ((bn & 0xC0) != 0x80) {
                valid = false;
            } else {
                cp = (cp << 6) | (bn & 0x3F);
            }
        }
        if (!valid) {
            cp = 0xFFFD;
            extra = 0;
        }
        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += extra + 1;
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string json_escape(const std::string & input) {
    std::string out;
    out.reserve(input.size() + 16);
    for (char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned char>(c));
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

std::string js_value_to_string(JSContext * ctx, JSValueConst value) {
    if (JS_IsUndefined(value)) {
        return "";
    }
    if (JS_IsNull(value)) {
        return "null";
    }
    if (JS_IsObject(value)) {
        JSValue json = JS_JSONStringify(ctx, value, JS_UNDEFINED, JS_UNDEFINED);
        if (!JS_IsException(json) && !JS_IsUndefined(json)) {
            const char * str = JS_ToCString(ctx, json);
            std::string out = str != nullptr ? str : "";
            if (str != nullptr) JS_FreeCString(ctx, str);
            JS_FreeValue(ctx, json);
            return out;
        }
        JS_FreeValue(ctx, json);
    }
    const char * str = JS_ToCString(ctx, value);
    std::string out = str != nullptr ? str : "";
    if (str != nullptr) JS_FreeCString(ctx, str);
    return out;
}

std::string take_exception(JSContext * ctx) {
    JSValue exc = JS_GetException(ctx);
    std::string message = js_value_to_string(ctx, exc);
    JSValue stack = JS_GetPropertyStr(ctx, exc, "stack");
    if (!JS_IsUndefined(stack) && !JS_IsNull(stack)) {
        std::string stack_text = js_value_to_string(ctx, stack);
        if (!stack_text.empty() && stack_text != message) {
            message += "\n" + stack_text;
        }
    }
    JS_FreeValue(ctx, stack);
    JS_FreeValue(ctx, exc);
    if (message.empty()) {
        message = "JavaScript error";
    }
    return message;
}

JNIEnv * current_env(JsInstance * inst) {
    JNIEnv * env = nullptr;
    if (inst->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (inst->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

JSValue js_tool(JSContext * ctx, JSValueConst, int argc, JSValueConst * argv) {
    auto * inst = static_cast<JsInstance *>(JS_GetContextOpaque(ctx));
    if (inst == nullptr) {
        return JS_ThrowInternalError(ctx, "host unavailable");
    }
    if (inst->cancelled.load()) {
        return JS_ThrowInternalError(ctx, "interrupted");
    }
    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "tool(name, args) expects a name");
    }
    const char * name_c = JS_ToCString(ctx, argv[0]);
    const char * args_c = argc > 1 ? JS_ToCString(ctx, argv[1]) : nullptr;
    std::string name = name_c != nullptr ? name_c : "";
    std::string args = args_c != nullptr ? args_c : "{}";
    if (name_c != nullptr) JS_FreeCString(ctx, name_c);
    if (args_c != nullptr) JS_FreeCString(ctx, args_c);

    JNIEnv * env = current_env(inst);
    if (env == nullptr) {
        return JS_ThrowInternalError(ctx, "cannot attach thread");
    }
    jstring jname = utf8_to_jstring(env, name);
    jstring jargs = utf8_to_jstring(env, args);
    auto result = static_cast<jstring>(env->CallObjectMethod(inst->host, inst->call_tool, jname, jargs));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(jargs);
        return JS_ThrowInternalError(ctx, "tool bridge failed");
    }
    std::string raw;
    if (result != nullptr) {
        raw = jstring_to_utf8(env, result);
        env->DeleteLocalRef(result);
    }
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jargs);

    JSValue value = JS_NewString(ctx, raw.c_str());
    return value;
}

JSValue js_log(JSContext * ctx, JSValueConst, int argc, JSValueConst * argv) {
    auto * inst = static_cast<JsInstance *>(JS_GetContextOpaque(ctx));
    if (inst != nullptr) {
        for (int i = 0; i < argc; ++i) {
            if (i > 0) inst->logs += " ";
            inst->logs += js_value_to_string(ctx, argv[i]);
        }
        inst->logs += "\n";
    }
    return JS_UNDEFINED;
}

int interrupt_handler(JSRuntime *, void * opaque) {
    auto * inst = static_cast<JsInstance *>(opaque);
    return (inst != nullptr && inst->cancelled.load()) ? 1 : 0;
}

const char * kPrelude = R"JS(
var console = {
  log: function() {
    var parts = [];
    for (var i = 0; i < arguments.length; i++) {
      var a = arguments[i];
      try { parts.push(typeof a === 'string' ? a : JSON.stringify(a)); }
      catch (e) { parts.push(String(a)); }
    }
    __log(parts.join(' '));
  }
};
console.info = console.log;
console.warn = console.log;
console.error = console.log;
function print() { console.log.apply(console, arguments); }
function tool(name, args) {
  var raw = __tool(String(name), JSON.stringify(args || {}));
  var r = JSON.parse(raw);
  if (!r.ok) throw new Error(r.error || 'tool failed');
  return r.result;
}
)JS";

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_llamaport_app_QuickJsBridge_create(JNIEnv * env, jclass, jobject host) {
    auto * inst = new JsInstance();
    env->GetJavaVM(&inst->vm);
    inst->host = env->NewGlobalRef(host);
    jclass cls = env->GetObjectClass(host);
    inst->call_tool = env->GetMethodID(cls, "callTool", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    env->DeleteLocalRef(cls);
    if (inst->call_tool == nullptr) {
        delete inst;
        return 0;
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        delete inst;
        return 0;
    }

    inst->rt = JS_NewRuntime();
    if (inst->rt == nullptr) {
        delete inst;
        return 0;
    }
    JS_SetMemoryLimit(inst->rt, kMemoryLimit);
    JS_SetMaxStackSize(inst->rt, kStackLimit);
    JS_SetInterruptHandler(inst->rt, interrupt_handler, inst);
    inst->ctx = JS_NewContext(inst->rt);
    if (inst->ctx == nullptr) {
        delete inst;
        return 0;
    }
    JS_SetContextOpaque(inst->ctx, inst);

    JSValue global = JS_GetGlobalObject(inst->ctx);
    JS_SetPropertyStr(inst->ctx, global, "__tool",
                      JS_NewCFunction(inst->ctx, js_tool, "tool", 2));
    JS_SetPropertyStr(inst->ctx, global, "__log",
                      JS_NewCFunction(inst->ctx, js_log, "log", 1));
    JS_FreeValue(inst->ctx, global);

    JSValue prelude = JS_Eval(inst->ctx, kPrelude, std::strlen(kPrelude),
                              "<prelude>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(prelude)) {
        LOGE("prelude failed: %s", take_exception(inst->ctx).c_str());
        JS_FreeValue(inst->ctx, prelude);
        delete inst;
        return 0;
    }
    JS_FreeValue(inst->ctx, prelude);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(inst));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_llamaport_app_QuickJsBridge_eval(JNIEnv * env, jclass, jlong handle, jstring jcode) {
    JsInstance * inst = from_handle(handle);
    if (inst == nullptr || inst->ctx == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"no instance\"}");
    }
    std::string code = jstring_to_utf8(env, jcode);

    inst->logs.clear();
    JSValue result = JS_Eval(inst->ctx, code.c_str(), code.size(), "<run_js>", JS_EVAL_TYPE_GLOBAL);
    std::string out;
    bool ok = true;
    if (JS_IsException(result)) {
        ok = false;
        out = take_exception(inst->ctx);
        if (out == "interrupted" || inst->cancelled.load()) {
            out = "Выполнение прервано по таймауту";
        }
    } else {
        out = js_value_to_string(inst->ctx, result);
    }
    JS_FreeValue(inst->ctx, result);

    if (!inst->logs.empty()) {
        if (ok) {
            out = inst->logs + out;
        } else {
            out = inst->logs + out;
        }
    }

    std::string payload;
    payload.reserve(out.size() + 32);
    payload += ok ? "{\"ok\":true,\"output\":\"" : "{\"ok\":false,\"error\":\"";
    payload += json_escape(out);
    payload += "\"}";
    return utf8_to_jstring(env, payload);
}

extern "C" JNIEXPORT void JNICALL
Java_com_llamaport_app_QuickJsBridge_cancel(JNIEnv *, jclass, jlong handle) {
    JsInstance * inst = from_handle(handle);
    if (inst != nullptr) {
        inst->cancelled.store(true);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_llamaport_app_QuickJsBridge_destroy(JNIEnv *, jclass, jlong handle) {
    JsInstance * inst = from_handle(handle);
    delete inst;
}
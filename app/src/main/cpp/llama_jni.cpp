#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <malloc.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct CacheSlot {
    std::vector<llama_token> tokens;
    std::vector<uint8_t>     state;
    bool                     valid = false;

    void clear() {
        tokens.clear();
        state.clear();
        valid = false;
    }

    size_t bytes() const {
        return state.size();
    }
};

struct Session {
    llama_model       * model   = nullptr;
    llama_context     * ctx     = nullptr;
    const llama_vocab * vocab   = nullptr;
    mtmd_context      * mctx    = nullptr;
    std::string         chat_template;
    std::atomic<bool>   cancel{false};
    std::atomic<bool>   busy{false};
    bool                use_mmap = true;
    bool                use_mlock = false;
    bool                mmproj_ok = false;
    bool                vision = false;
    bool                audio = false;
    int                 kv_type = 0;
    int                 flash_attn = 0;
    int                 n_batch = 512;
    int                 n_ubatch = 512;
    int                 n_threads = 4;
    int                 n_threads_batch = 4;

    // Text-only prompt cache. The prompt slot is captured after the prompt is
    // evaluated (without its last token). The full slot is captured after
    // generation and includes the generated tokens, so the next turn can reuse
    // the whole previous answer instead of re-evaluating it.
    CacheSlot prompt_cache;
    CacheSlot full_cache;

    // The base slot contains the immutable system block (system prompt plus
    // tool instructions and the thinking instruction). It is captured at the
    // first prefill and persisted, so a fresh app start or a chat switch does
    // not re-evaluate it. Unlike the other slots it survives prefix mismatches.
    CacheSlot base_cache;
    std::vector<llama_token> base_tokens;
    std::vector<uint8_t>     base_fingerprint;
    std::string              base_cache_path;
    bool                     base_load_attempted = false;
    std::thread              base_save_thread;
};

inline Session * from_handle(jlong h) {
    return reinterpret_cast<Session *>(static_cast<intptr_t>(h));
}

inline ggml_type kv_ggml_type(int kv_type) {
    return kv_type == 1 ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
}

inline int clamp_batch(int value, int fallback) {
    if (value <= 0) {
        return fallback;
    }
    return std::max(32, std::min(value, 2048));
}

inline int clamp_threads(int value, int fallback) {
    if (value <= 0) {
        return fallback;
    }
    return std::max(1, std::min(value, 16));
}

inline size_t common_prefix_len(const std::vector<llama_token> & a,
                                const std::vector<llama_token> & b) {
    const size_t n = std::min(a.size(), b.size());
    size_t i = 0;
    while (i < n && a[i] == b[i]) {
        ++i;
    }
    return i;
}

// Keep the saved prefix state in host RAM, but do not let it compete with the
// model/KV for memory on very long contexts. 256 MiB covers ordinary chats;
// larger contexts simply fall back to a full prompt evaluation.
constexpr size_t kMaxCacheStateBytes = 256ull * 1024ull * 1024ull;

void clear_all_caches(Session * s) {
    s->prompt_cache.clear();
    s->full_cache.clear();
}

bool save_cache_slot(CacheSlot & slot, llama_context * ctx,
                     const std::vector<llama_token> & tokens,
                     size_t max_bytes, const char * label) {
    if (tokens.empty() || ctx == nullptr) {
        slot.clear();
        return false;
    }

    const size_t state_size = llama_state_seq_get_size(ctx, 0);
    if (state_size == 0 || state_size > max_bytes) {
        LOGI("cache: %s state %.2f MiB is too large, disabled",
             label, state_size / 1048576.0);
        slot.clear();
        return false;
    }

    // Allocate the replacement first: if the snapshot fails, the previous slot
    // is still usable as a fallback.
    std::vector<uint8_t> next(state_size);
    const size_t got = llama_state_seq_get_data(ctx, next.data(), next.size(), 0);
    if (got != state_size) {
        LOGE("cache: %s state save failed (got %zu of %zu)", label, got, state_size);
        slot.clear();
        return false;
    }

    slot.state.swap(next);
    slot.tokens = tokens;
    slot.valid = true;
    LOGI("cache: saved %s %zu tokens, state %.2f MiB",
         label, tokens.size(), state_size / 1048576.0);
    return true;
}

bool try_restore_cache(Session * s, CacheSlot & slot,
                       const std::vector<llama_token> & tokens,
                       size_t & prefix_len, const char * label) {
    if (!slot.valid || slot.tokens.empty() || slot.state.empty()) {
        return false;
    }

    const size_t common = common_prefix_len(slot.tokens, tokens);
    if (common != slot.tokens.size() || common >= tokens.size()) {
        LOGI("prompt: %s cache mismatch (common=%zu, cached=%zu, prompt=%zu), dropping",
             label, common, slot.tokens.size(), tokens.size());
        slot.clear();
        return false;
    }

    const size_t restored = llama_state_seq_set_data(
        s->ctx, slot.state.data(), slot.state.size(), 0);
    if (restored == 0) {
        LOGE("llama_state_seq_set_data failed for %s cache (%zu), dropping",
             label, restored);
        slot.clear();
        return false;
    }

    prefix_len = common;
    LOGI("prompt: restored %zu tokens from %s cache (state %.2f MiB)",
         prefix_len, label, slot.state.size() / 1048576.0);
    return true;
}

// Base-cache files are scoped to a model and validated by a 32-byte
// fingerprint computed in Kotlin from the model, mmproj, context, KV type,
// Flash Attention, system prompt and tool prompt. The header also repeats the
// effective runtime KV layout, because a q8_0 request may silently fall back
// to f16 and a state recorded with one layout cannot be restored into another.
constexpr uint32_t kBaseCacheVersion = 1;
constexpr size_t kBaseCacheFingerprintBytes = 32;
constexpr size_t kBaseCacheHeaderBytes =
    4 + 4 + kBaseCacheFingerprintBytes + 4 + 8 + 4 + 4 + 4;
constexpr char kBaseCacheMagic[4] = {'L', 'P', 'B', 'C'};
constexpr size_t kMinBaseCacheTokens = 8;

bool read_base_bytes(FILE * f, void * out, size_t size) {
    return std::fread(out, 1, size, f) == size;
}

bool write_base_bytes(FILE * f, const void * data, size_t size) {
    return std::fwrite(data, 1, size, f) == size;
}

bool write_base_cache_file(const std::string & path,
                           const std::vector<uint8_t> & fingerprint,
                           uint32_t token_count,
                           const std::vector<uint8_t> & state,
                           int kv_type, int flash_attn, int n_ctx) {
    if (path.empty() || fingerprint.size() != kBaseCacheFingerprintBytes ||
        state.empty()) {
        return false;
    }

    const std::string tmp_path = path + ".tmp";
    FILE * f = std::fopen(tmp_path.c_str(), "wb");
    if (f == nullptr) {
        LOGI("cache: cannot open base cache file %s", tmp_path.c_str());
        return false;
    }

    const uint32_t version = kBaseCacheVersion;
    const uint64_t state_size = state.size();
    const uint32_t kv = static_cast<uint32_t>(kv_type);
    const uint32_t fa = static_cast<uint32_t>(flash_attn);
    const uint32_t ctx = static_cast<uint32_t>(n_ctx);

    bool ok = write_base_bytes(f, kBaseCacheMagic, sizeof(kBaseCacheMagic)) &&
              write_base_bytes(f, &version, sizeof(version)) &&
              write_base_bytes(f, fingerprint.data(), fingerprint.size()) &&
              write_base_bytes(f, &token_count, sizeof(token_count)) &&
              write_base_bytes(f, &state_size, sizeof(state_size)) &&
              write_base_bytes(f, &kv, sizeof(kv)) &&
              write_base_bytes(f, &fa, sizeof(fa)) &&
              write_base_bytes(f, &ctx, sizeof(ctx)) &&
              write_base_bytes(f, state.data(), state.size());
    if (ok) {
        ok = std::fflush(f) == 0 && fsync(fileno(f)) == 0;
    }
    std::fclose(f);

    if (!ok) {
        std::remove(tmp_path.c_str());
        return false;
    }
    if (std::rename(tmp_path.c_str(), path.c_str()) != 0) {
        std::remove(tmp_path.c_str());
        return false;
    }
    return true;
}

void remove_base_cache_file(Session * s) {
    if (!s->base_cache_path.empty()) {
        std::remove(s->base_cache_path.c_str());
    }
}

bool load_base_cache_file(Session * s) {
    if (s->base_cache_path.empty() ||
        s->base_fingerprint.size() != kBaseCacheFingerprintBytes ||
        s->base_tokens.empty()) {
        return false;
    }

    FILE * f = std::fopen(s->base_cache_path.c_str(), "rb");
    if (f == nullptr) {
        LOGI("cache: base cache file not found");
        return false;
    }

    char magic[4] = {0};
    uint32_t version = 0;
    uint8_t fingerprint[kBaseCacheFingerprintBytes] = {0};
    uint32_t token_count = 0;
    uint64_t state_size = 0;
    uint32_t kv_type = 0;
    uint32_t flash_attn = 0;
    uint32_t n_ctx = 0;

    bool ok = read_base_bytes(f, magic, sizeof(magic)) &&
              std::memcmp(magic, kBaseCacheMagic, sizeof(magic)) == 0 &&
              read_base_bytes(f, &version, sizeof(version)) &&
              read_base_bytes(f, fingerprint, sizeof(fingerprint)) &&
              read_base_bytes(f, &token_count, sizeof(token_count)) &&
              read_base_bytes(f, &state_size, sizeof(state_size)) &&
              read_base_bytes(f, &kv_type, sizeof(kv_type)) &&
              read_base_bytes(f, &flash_attn, sizeof(flash_attn)) &&
              read_base_bytes(f, &n_ctx, sizeof(n_ctx));

    if (!ok) {
        LOGI("cache: base cache header is truncated");
        std::fclose(f);
        remove_base_cache_file(s);
        return false;
    }

    const bool matches = version == kBaseCacheVersion &&
        std::memcmp(fingerprint, s->base_fingerprint.data(),
                    kBaseCacheFingerprintBytes) == 0 &&
        token_count > 0 && token_count <= s->base_tokens.size() &&
        state_size > 0 && state_size <= kMaxCacheStateBytes &&
        kv_type == static_cast<uint32_t>(s->kv_type) &&
        flash_attn == static_cast<uint32_t>(s->flash_attn) &&
        n_ctx == static_cast<uint32_t>(llama_n_ctx(s->ctx));

    if (!matches) {
        LOGI("cache: base cache fingerprint/layout mismatch, rebuilding");
        std::fclose(f);
        remove_base_cache_file(s);
        return false;
    }

    const off_t end = fseeko(f, 0, SEEK_END) == 0 ? ftello(f) : -1;
    const bool size_ok = end >= 0 &&
        static_cast<uint64_t>(end) == kBaseCacheHeaderBytes + state_size;
    if (!size_ok || fseeko(f, static_cast<off_t>(kBaseCacheHeaderBytes), SEEK_SET) != 0) {
        LOGI("cache: base cache file has an unexpected size");
        std::fclose(f);
        remove_base_cache_file(s);
        return false;
    }

    std::vector<uint8_t> state(static_cast<size_t>(state_size));
    if (!read_base_bytes(f, state.data(), state.size())) {
        std::fclose(f);
        remove_base_cache_file(s);
        return false;
    }
    std::fclose(f);

    // The state is loaded lazily: it is applied to the KV cache only when the
    // current prompt actually starts with it. This avoids touching the cache
    // for chats that need a different prefix.
    s->base_cache.tokens.assign(
        s->base_tokens.begin(),
        s->base_tokens.begin() + static_cast<ptrdiff_t>(token_count));
    s->base_cache.state = std::move(state);
    s->base_cache.valid = true;
    LOGI("cache: loaded %u tokens from base cache file (state %.2f MiB)",
         token_count, s->base_cache.state.size() / 1048576.0);
    return true;
}

void save_base_cache_file_async(Session * s) {
    if (s->base_cache_path.empty() ||
        s->base_fingerprint.size() != kBaseCacheFingerprintBytes ||
        !s->base_cache.valid || s->base_cache.tokens.empty() ||
        s->base_cache.state.empty()) {
        return;
    }
    if (s->base_save_thread.joinable()) {
        s->base_save_thread.join();
    }

    std::string path = s->base_cache_path;
    std::vector<uint8_t> fingerprint = s->base_fingerprint;
    const uint32_t token_count =
        static_cast<uint32_t>(s->base_cache.tokens.size());
    std::vector<uint8_t> state = s->base_cache.state;
    const int kv_type = s->kv_type;
    const int flash_attn = s->flash_attn;
    const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));

    s->base_save_thread = std::thread(
        [path = std::move(path), fingerprint = std::move(fingerprint),
         token_count, state = std::move(state), kv_type, flash_attn, n_ctx]() {
            const bool ok = write_base_cache_file(
                path, fingerprint, token_count, state, kv_type, flash_attn, n_ctx);
            LOGI("cache: base cache file %s (%u tokens, %.2f MiB)",
                 ok ? "saved" : "save failed", token_count,
                 state.size() / 1048576.0);
        });
}

void configure_base_cache(Session * s, std::vector<llama_token> base_tokens,
                          std::vector<uint8_t> fingerprint,
                          const std::string & path) {
    if (fingerprint != s->base_fingerprint || path != s->base_cache_path) {
        if (s->base_save_thread.joinable()) {
            s->base_save_thread.join();
        }
        s->base_cache.clear();
        s->base_fingerprint = std::move(fingerprint);
        s->base_cache_path = path;
        s->base_load_attempted = false;
    }
    s->base_tokens = std::move(base_tokens);
}

// The base slot is shared by every chat and must survive a prompt that does
// not start with it (for example a trimmed prompt). A mismatch is therefore
// not a reason to drop the saved state.
bool try_restore_base_cache(Session * s, const std::vector<llama_token> & tokens,
                            size_t & prefix_len) {
    CacheSlot & slot = s->base_cache;
    if (!slot.valid || slot.tokens.empty() || slot.state.empty()) {
        return false;
    }

    const size_t common = common_prefix_len(slot.tokens, tokens);
    if (common < slot.tokens.size() || common >= tokens.size()) {
        LOGI("prompt: base cache mismatch (common=%zu, cached=%zu, prompt=%zu), kept",
             common, slot.tokens.size(), tokens.size());
        return false;
    }

    const size_t restored = llama_state_seq_set_data(
        s->ctx, slot.state.data(), slot.state.size(), 0);
    if (restored == 0) {
        LOGE("llama_state_seq_set_data failed for base cache, dropping");
        remove_base_cache_file(s);
        slot.clear();
        return false;
    }

    prefix_len = common;
    LOGI("prompt: restored %zu tokens from base cache (state %.2f MiB)",
         prefix_len, slot.state.size() / 1048576.0);
    return true;
}

void capture_base_cache(Session * s, const std::vector<llama_token> & tokens,
                        size_t prefix_len) {
    if (prefix_len < kMinBaseCacheTokens) {
        return;
    }

    std::vector<llama_token> prefix_tokens(
        tokens.begin(), tokens.begin() + static_cast<ptrdiff_t>(prefix_len));
    if (save_cache_slot(s->base_cache, s->ctx, prefix_tokens,
                        kMaxCacheStateBytes, "base")) {
        save_base_cache_file_async(s);
    }
}

inline int64_t file_size_bytes(const std::string & path) {
    struct stat st{};
    if (stat(path.c_str(), &st) != 0) {
        return 0;
    }
    return static_cast<int64_t>(st.st_size);
}

bool read_file_bytes(const std::string & path, std::vector<unsigned char> & out,
                     size_t max_bytes) {
    const int64_t size = file_size_bytes(path);
    if (size <= 0 || static_cast<uint64_t>(size) > max_bytes) {
        return false;
    }
    FILE * f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return false;
    out.resize(static_cast<size_t>(size));
    const bool ok = std::fread(out.data(), 1, out.size(), f) == out.size();
    std::fclose(f);
    if (!ok) out.clear();
    return ok;
}

// ---------------------------------------------------------------- utf-8 glue

std::string jstring_to_utf8(JNIEnv * env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const jsize len = env->GetStringLength(s);
    const jchar * chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

jbyteArray make_bytes(JNIEnv * env, const void * data, size_t size) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
    if (arr != nullptr && size > 0) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
    }
    return arr;
}

jbyteArray empty_bytes(JNIEnv * env) {
    return env->NewByteArray(0);
}

std::vector<uint8_t> jbytes_to_vector(JNIEnv * env, jbyteArray arr) {
    if (arr == nullptr) {
        return {};
    }
    const jsize size = env->GetArrayLength(arr);
    if (size <= 0) {
        return {};
    }
    std::vector<uint8_t> out(static_cast<size_t>(size));
    env->GetByteArrayRegion(arr, 0, size, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

// Returns the length of the longest prefix that ends on a complete UTF-8 sequence.
size_t utf8_complete_prefix(const std::string & s) {
    size_t i = 0;
    size_t last = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len = 0;
        if (c < 0x80) {
            len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            len = 4;
        } else {
            ++i; // invalid lead byte, drop it
            last = i;
            continue;
        }
        if (i + len > s.size()) {
            break; // incomplete sequence at the end
        }
        bool ok = true;
        for (size_t k = 1; k < len; ++k) {
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) {
                ok = false;
                break;
            }
        }
        if (!ok) {
            ++i;
            last = i;
            continue;
        }
        i += len;
        last = i;
    }
    return last;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    if (text.empty()) {
        return {};
    }
    int32_t n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                nullptr, 0, add_special, true);
    if (n <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    const int32_t m = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                     tokens.data(), n, add_special, true);
    if (m < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(m));
    return tokens;
}

std::string format_params(uint64_t n) {
    char buf[64];
    if (n >= 1000000000ULL) {
        std::snprintf(buf, sizeof(buf), "%.2fB параметров", static_cast<double>(n) / 1e9);
    } else if (n >= 1000000ULL) {
        std::snprintf(buf, sizeof(buf), "%.0fM параметров", static_cast<double>(n) / 1e6);
    } else {
        std::snprintf(buf, sizeof(buf), "%llu параметров", static_cast<unsigned long long>(n));
    }
    return buf;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_llamaport_app_LlamaBridge_backendInit(JNIEnv *, jclass) {
    llama_backend_init();
}

JNIEXPORT void JNICALL
Java_com_llamaport_app_LlamaBridge_backendFree(JNIEnv *, jclass) {
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_llamaport_app_LlamaBridge_systemInfo(JNIEnv * env, jclass) {
    const char * info = llama_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "");
}

static jlong load_model_impl(
        JNIEnv * env, jstring jpath, jint n_ctx, jint n_threads_in,
        jint n_threads_batch_in, jboolean use_mmap, jint kv_type, jint flash_attn,
        jint n_batch_in, jint n_ubatch_in, jstring jmmproj) {
    const std::string path = jstring_to_utf8(env, jpath);
    const std::string mmproj_path = jstring_to_utf8(env, jmmproj);
    if (path.empty()) {
        return -1;
    }
    if (kv_type != 0 && kv_type != 1) {
        return -4;
    }
    if (flash_attn < 0 || flash_attn > 2) {
        return -4;
    }

    auto * s = new Session();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = use_mmap != 0 ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    s->model = llama_model_load_from_file(path.c_str(), mparams);
    if (s->model == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        delete s;
        return -2;
    }

    const int n_batch = clamp_batch(n_batch_in, 512);
    int n_ubatch = clamp_batch(n_ubatch_in, n_batch);
    n_ubatch = std::min(n_ubatch, n_batch);

    const int n_threads = clamp_threads(n_threads_in, 4);
    const int n_threads_batch = clamp_threads(n_threads_batch_in, n_threads);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : 4096);
    cparams.n_batch         = static_cast<uint32_t>(n_batch);
    cparams.n_ubatch        = static_cast<uint32_t>(n_ubatch);
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads_batch;
    cparams.type_k          = kv_ggml_type(kv_type);
    cparams.type_v          = kv_ggml_type(kv_type);
    if (flash_attn == 1) {
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    } else if (flash_attn == 2) {
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    } else {
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    }
    if (kv_type == 1) {
        // Quantized V cache needs Flash Attention.
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    cparams.abort_callback      = [](void * data) -> bool {
        return static_cast<Session *>(data)->cancel.load();
    };
    cparams.abort_callback_data = s;

    s->ctx = llama_init_from_model(s->model, cparams);
    int effective_kv = kv_type;
    int effective_flash = flash_attn;
    if (s->ctx == nullptr && flash_attn == 1 && kv_type == 0) {
        LOGE("flash attention context failed, retrying with auto");
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        s->ctx = llama_init_from_model(s->model, cparams);
        if (s->ctx != nullptr) {
            effective_flash = 0;
        }
    }
    if (s->ctx == nullptr && kv_type == 1) {
        LOGE("q8_0 KV context failed, retrying with f16 KV");
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        effective_flash = 0;
        s->ctx = llama_init_from_model(s->model, cparams);
        if (s->ctx != nullptr) {
            effective_kv = 0;
        }
    }
    if (s->ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(s->model);
        delete s;
        return -3;
    }

    s->vocab = llama_model_get_vocab(s->model);
    s->use_mmap = use_mmap != 0;
    s->use_mlock = false;
    s->kv_type = effective_kv;
    s->flash_attn = effective_flash;
    s->n_batch = n_batch;
    s->n_ubatch = n_ubatch;
    s->n_threads = n_threads;
    s->n_threads_batch = n_threads_batch;

    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl != nullptr) {
        s->chat_template = tmpl;
    }

    if (!mmproj_path.empty()) {
        mtmd_context_params mp = mtmd_context_params_default();
        mp.use_gpu = false;
        mp.n_threads = n_threads_batch;
        mp.print_timings = false;
        mp.warmup = false;
        s->mctx = mtmd_init_from_file(mmproj_path.c_str(), s->model, mp);
        if (s->mctx == nullptr) {
            LOGE("failed to load mmproj: %s", mmproj_path.c_str());
        } else {
            s->mmproj_ok = true;
            s->vision = mtmd_support_vision(s->mctx);
            s->audio = mtmd_support_audio(s->mctx);
            LOGI("mmproj loaded: %s (vision=%d audio=%d)",
                 mmproj_path.c_str(), s->vision ? 1 : 0, s->audio ? 1 : 0);
        }
    }

    LOGI("model loaded: %s (n_ctx=%u, threads=%d/%d, mmap=%d, kv=%d, fa=%d, batch=%d/%d, mmproj=%d)",
         path.c_str(), llama_n_ctx(s->ctx), n_threads, n_threads_batch,
         s->use_mmap, effective_kv, effective_flash, n_batch, n_ubatch,
         s->mmproj_ok ? 1 : 0);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(s));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_llamaport_app_LlamaBridge_loadModel(
        JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    return load_model_impl(env, jpath, n_ctx, n_threads, n_threads,
                           JNI_TRUE, 0, 0, 512, 512, nullptr);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_llamaport_app_LlamaBridge_loadModelEx(
        JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads,
        jint n_threads_batch, jboolean use_mmap, jint kv_type, jint flash_attn,
        jint n_batch, jint n_ubatch, jstring mmproj) {
    return load_model_impl(env, jpath, n_ctx, n_threads, n_threads_batch,
                           use_mmap, kv_type, flash_attn, n_batch, n_ubatch, mmproj);
}

namespace {

bool read_exact(FILE * f, void * out, size_t size) {
    return std::fread(out, 1, size, f) == size;
}

bool read_u32(FILE * f, uint32_t & value) {
    return read_exact(f, &value, sizeof(value));
}

bool read_u64(FILE * f, uint64_t & value) {
    return read_exact(f, &value, sizeof(value));
}

bool skip_bytes(FILE * f, uint64_t count) {
    return fseeko(f, static_cast<off_t>(count), SEEK_CUR) == 0;
}

bool read_string(FILE * f, bool v1, std::string & out) {
    uint64_t length = 0;
    if (v1) {
        uint32_t small = 0;
        if (!read_u32(f, small)) return false;
        length = small;
    } else {
        if (!read_u64(f, length)) return false;
    }
    if (length > (1u << 24)) {
        return false;
    }
    out.assign(static_cast<size_t>(length), '\0');
    if (length > 0 && !read_exact(f, out.data(), static_cast<size_t>(length))) {
        return false;
    }
    return true;
}

bool numeric_size(uint32_t type, uint64_t & size) {
    switch (type) {
        case 0: case 1: case 7: size = 1; return true;
        case 2: case 3: size = 2; return true;
        case 4: case 5: case 6: size = 4; return true;
        case 10: case 11: case 12: size = 8; return true;
        default: return false;
    }
}

bool read_scalar(FILE * f, uint32_t type, int64_t & out) {
    switch (type) {
        case 0: { uint8_t v; if (!read_exact(f, &v, 1)) return false; out = v; return true; }
        case 1: { int8_t v; if (!read_exact(f, &v, 1)) return false; out = v; return true; }
        case 2: { uint16_t v; if (!read_exact(f, &v, 2)) return false; out = v; return true; }
        case 3: { int16_t v; if (!read_exact(f, &v, 2)) return false; out = v; return true; }
        case 4: { uint32_t v; if (!read_exact(f, &v, 4)) return false; out = v; return true; }
        case 5: { int32_t v; if (!read_exact(f, &v, 4)) return false; out = v; return true; }
        case 6: { float v; if (!read_exact(f, &v, 4)) return false; out = static_cast<int64_t>(v); return true; }
        case 7: { uint8_t v; if (!read_exact(f, &v, 1)) return false; out = v; return true; }
        case 10: { uint64_t v; if (!read_exact(f, &v, 8)) return false; out = static_cast<int64_t>(v); return true; }
        case 11: { int64_t v; if (!read_exact(f, &v, 8)) return false; out = v; return true; }
        case 12: { double v; if (!read_exact(f, &v, 8)) return false; out = static_cast<int64_t>(v); return true; }
        default: return false;
    }
}

// Consumes a GGUF value. If first_numeric is not null and the value is numeric
// (or a numeric array), stores its first positive element there. sum_numeric
// receives the sum of every element of a numeric array.
bool consume_value(FILE * f, uint32_t type, bool v1, int64_t * first_numeric,
                   int64_t * sum_numeric, int depth) {
    if (depth > 4) return false;
    if (type == 8) {
        std::string ignored;
        return read_string(f, v1, ignored);
    }
    if (type != 9) {
        int64_t value = 0;
        if (!read_scalar(f, type, value)) return false;
        if (first_numeric != nullptr) *first_numeric = value;
        if (sum_numeric != nullptr) *sum_numeric = value;
        return true;
    }

    uint32_t element_type = 0;
    uint64_t count = 0;
    if (!read_u32(f, element_type)) return false;
    if (v1) {
        uint32_t small = 0;
        if (!read_u32(f, small)) return false;
        count = small;
    } else {
        if (!read_u64(f, count)) return false;
    }
    if (count > 100000000ull) return false;

    uint64_t scalar_size = 0;
    if (numeric_size(element_type, scalar_size)) {
        int64_t sum = 0;
        bool got_first = false;
        for (uint64_t i = 0; i < count; ++i) {
            int64_t value = 0;
            if (!read_scalar(f, element_type, value)) return false;
            sum += value;
            if (!got_first && value > 0) {
                got_first = true;
                if (first_numeric != nullptr) *first_numeric = value;
            }
        }
        if (!got_first && count > 0 && first_numeric != nullptr) {
            // Arrays such as head_count_kv may start with zeros.
            *first_numeric = 0;
        }
        if (sum_numeric != nullptr) *sum_numeric = sum;
        return true;
    }
    for (uint64_t i = 0; i < count; ++i) {
        if (!consume_value(f, element_type, v1, nullptr, nullptr, depth + 1)) return false;
    }
    return true;
}

struct GgufEstimate {
    int64_t n_layer = 0;
    int64_t n_embd = 0;
    int64_t n_head = 0;
    int64_t n_head_kv = 0;
    int64_t n_head_kv_total = 0;
    int64_t context_length = 0;
    bool complete() const {
        return n_layer > 0 && n_embd > 0 && n_head > 0;
    }
};

bool read_gguf_estimate(const std::string & path, GgufEstimate & estimate) {
    FILE * f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return false;

    char magic[4] = {0};
    uint32_t version = 0;
    uint64_t tensor_count = 0;
    uint64_t kv_count = 0;
    bool ok = read_exact(f, magic, 4) &&
              std::memcmp(magic, "GGUF", 4) == 0 &&
              read_u32(f, version) &&
              read_u64(f, tensor_count) &&
              read_u64(f, kv_count);
    (void) tensor_count;
    const bool v1 = version == 1;

    for (uint64_t i = 0; ok && i < kv_count && i < 100000; ++i) {
        std::string key;
        uint32_t type = 0;
        ok = read_string(f, v1, key) && read_u32(f, type);
        if (!ok) break;

        const auto ends_with = [](const std::string & value, const char * suffix) {
            const size_t n = std::strlen(suffix);
            return value.size() >= n && value.compare(value.size() - n, n, suffix) == 0;
        };
        const bool is_block_count = ends_with(key, ".block_count");
        const bool is_embedding = ends_with(key, ".embedding_length");
        const bool is_head_count_kv = ends_with(key, ".attention.head_count_kv");
        const bool is_head_count = ends_with(key, ".attention.head_count");
        const bool is_context_length = ends_with(key, ".context_length");
        if (is_block_count || is_embedding || is_head_count || is_head_count_kv ||
            is_context_length) {
            int64_t value = 0;
            int64_t sum = 0;
            if (!consume_value(f, type, v1, &value, &sum, 0)) {
                ok = false;
                break;
            }
            if (is_block_count) {
                estimate.n_layer = value;
            } else if (is_embedding) {
                estimate.n_embd = value;
            } else if (is_head_count_kv) {
                estimate.n_head_kv = value;
                estimate.n_head_kv_total = sum;
            } else if (is_head_count) {
                estimate.n_head = value;
            } else {
                estimate.context_length = value;
            }
            if (estimate.complete() && estimate.n_head_kv > 0 &&
                estimate.context_length > 0) {
                break;
            }
        } else {
            if (!consume_value(f, type, v1, nullptr, nullptr, 0)) {
                ok = false;
                break;
            }
        }
    }

    std::fclose(f);
    if (!estimate.complete()) return false;
    if (estimate.n_head <= 0) estimate.n_head = 1;
    if (estimate.n_head_kv <= 0) estimate.n_head_kv = estimate.n_head;
    if (estimate.n_head_kv_total <= 0) {
        estimate.n_head_kv_total = estimate.n_layer * estimate.n_head_kv;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_llamaport_app_LlamaBridge_estimateModel(
        JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint kv_type) {
    const std::string path = jstring_to_utf8(env, jpath);
    if (path.empty()) {
        return env->NewStringUTF("");
    }

    GgufEstimate estimate;
    if (!read_gguf_estimate(path, estimate)) {
        // Fallback: at least report the file size so the UI can warn.
        const int64_t file_bytes = file_size_bytes(path);
        if (file_bytes <= 0) return env->NewStringUTF("");
        char buf[256];
        std::snprintf(buf, sizeof(buf), "%lld|0|0|0|0|0|0|0",
                      static_cast<long long>(file_bytes));
        return env->NewStringUTF(buf);
    }

    const int64_t file_bytes = file_size_bytes(path);
    const int64_t head_dim = std::max<int64_t>(1, estimate.n_embd / estimate.n_head);
    const double bytes_per_element = (kv_type == 1) ? (34.0 / 32.0) : 2.0;
    const int64_t kv_bytes = static_cast<int64_t>(
        2.0 * static_cast<double>(std::max(1, static_cast<int>(n_ctx))) *
        static_cast<double>(estimate.n_head_kv_total) *
        static_cast<double>(head_dim) * bytes_per_element);

    char buf[256];
    std::snprintf(buf, sizeof(buf), "%lld|%lld|%lld|%lld|%lld|%lld|0|%lld",
                  static_cast<long long>(file_bytes),
                  static_cast<long long>(kv_bytes),
                  static_cast<long long>(estimate.n_layer),
                  static_cast<long long>(estimate.n_embd),
                  static_cast<long long>(estimate.n_head),
                  static_cast<long long>(estimate.n_head_kv),
                  static_cast<long long>(estimate.context_length));
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_llamaport_app_LlamaBridge_trimMemory(JNIEnv *, jclass) {
    return mallopt(M_PURGE, 0) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_llamaport_app_LlamaBridge_freeModel(JNIEnv *, jclass, jlong handle) {
    Session * s = from_handle(handle);
    if (s == nullptr) {
        return;
    }
    s->cancel = true;
    if (s->base_save_thread.joinable()) {
        s->base_save_thread.join();
    }
    if (s->ctx != nullptr) {
        llama_free(s->ctx);
        s->ctx = nullptr;
    }
    if (s->mctx != nullptr) {
        mtmd_free(s->mctx);
        s->mctx = nullptr;
    }
    if (s->model != nullptr) {
        llama_model_free(s->model);
        s->model = nullptr;
    }
    delete s;
}

JNIEXPORT jstring JNICALL
Java_com_llamaport_app_LlamaBridge_modelInfo(JNIEnv * env, jclass, jlong handle) {
    Session * s = from_handle(handle);
    if (s == nullptr || s->model == nullptr) {
        return env->NewStringUTF("");
    }

    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));

    const std::string out =
        std::string(desc) + "|" +
        format_params(llama_model_n_params(s->model)) + "|" +
        std::to_string(llama_model_n_ctx_train(s->model)) + "|" +
        (s->vision ? "1" : "0") + "|" +
        (s->audio ? "1" : "0") + "|" +
        (s->mmproj_ok ? "1" : "0");

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jbyteArray JNICALL
Java_com_llamaport_app_LlamaBridge_applyTemplate(
        JNIEnv * env, jclass, jlong handle,
        jobjectArray roles, jobjectArray contents, jboolean add_assistant,
        jboolean enable_thinking) {
    Session * s = from_handle(handle);
    if (s == nullptr || s->model == nullptr) {
        return empty_bytes(env);
    }

    const jsize n = std::min(env->GetArrayLength(roles), env->GetArrayLength(contents));

    std::vector<std::string> role_store;
    std::vector<std::string> content_store;
    std::vector<llama_chat_message> messages;
    role_store.reserve(static_cast<size_t>(n));
    content_store.reserve(static_cast<size_t>(n));
    messages.reserve(static_cast<size_t>(n));

    for (jsize i = 0; i < n; ++i) {
        auto * jr = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto * jc = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_store.push_back(jstring_to_utf8(env, jr));
        content_store.push_back(jstring_to_utf8(env, jc));
        if (jr != nullptr) env->DeleteLocalRef(jr);
        if (jc != nullptr) env->DeleteLocalRef(jc);
    }
    for (jsize i = 0; i < n; ++i) {
        messages.push_back(llama_chat_message{role_store[static_cast<size_t>(i)].c_str(),
                                              content_store[static_cast<size_t>(i)].c_str()});
    }

    const char * tmpl = s->chat_template.empty() ? "chatml" : s->chat_template.c_str();

    int32_t len = llama_chat_apply_template(tmpl, messages.data(),
                                            messages.size(), add_assistant != 0, nullptr, 0);
    if (len <= 0 && s->chat_template != "chatml") {
        tmpl = "chatml";
        len = llama_chat_apply_template(tmpl, messages.data(),
                                        messages.size(), add_assistant != 0, nullptr, 0);
    }
    if (len <= 0) {
        LOGE("chat template could not be applied");
        return empty_bytes(env);
    }

    std::vector<char> buf(static_cast<size_t>(len) + 1, '\0');
    llama_chat_apply_template(tmpl, messages.data(), messages.size(),
                              add_assistant != 0, buf.data(), static_cast<int32_t>(buf.size()));

    std::string result(buf.data(), static_cast<size_t>(len));
    if (add_assistant != 0 && enable_thinking != 0) {
        // llama_chat_apply_template() has no way to pass extra template variables
        // (enable_thinking et al). Most thinking-capable templates end the
        // generation prompt with the disabled sentinel; rewrite it in place.
        auto ends_with = [](const std::string & s, const char * suffix) {
            const size_t n = std::strlen(suffix);
            return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
        };
        struct ThinkingRewrite {
            const char * from;
            const char * to;
        };
        static const ThinkingRewrite rewrites[] = {
            {"<think></think>", "<think>"},
            {"<think>", "<think>"},
            {" <think>", "<think>"},
            {" think", "<think>"},
        };
        bool rewritten = false;
        for (const ThinkingRewrite & rewrite : rewrites) {
            if (ends_with(result, rewrite.from)) {
                result.replace(result.size() - std::strlen(rewrite.from),
                               std::strlen(rewrite.from), rewrite.to);
                rewritten = true;
                break;
            }
        }
        if (!rewritten) {
            static const char * const assistant_suffixes[] = {
                "<|im_start|>assistant\n",
                "<|start_header_id|>assistant<|end_header_id|>\n\n",
            };
            for (const char * suffix : assistant_suffixes) {
                if (ends_with(result, suffix)) {
                    result.append("\x3cthink\x3e");
                    break;
                }
            }
        }
    }

    return make_bytes(env, result.data(), result.size());
}

JNIEXPORT jint JNICALL
Java_com_llamaport_app_LlamaBridge_generate(
        JNIEnv * env, jclass, jlong handle, jbyteArray jprompt,
        jint max_tokens, jfloat temp, jfloat top_p, jint top_k, jint seed,
        jobjectArray jmedia_paths, jbyteArray jbase_prompt,
        jbyteArray jbase_fingerprint, jstring jbase_cache_path,
        jobject callback) {
    Session * s = from_handle(handle);
    if (s == nullptr || s->ctx == nullptr || s->vocab == nullptr) {
        return -1;
    }
    if (s->busy.exchange(true)) {
        return -6;
    }

    struct BusyGuard {
        Session * s;
        ~BusyGuard() { s->busy = false; }
    } guard{s};

    s->cancel = false;

    // Prompt bytes -> utf8 string -> tokens
    const jsize prompt_len = env->GetArrayLength(jprompt);
    std::string prompt(static_cast<size_t>(prompt_len), '\0');
    if (prompt_len > 0) {
        env->GetByteArrayRegion(jprompt, 0, prompt_len, reinterpret_cast<jbyte *>(prompt.data()));
    }

    std::vector<std::string> media_paths;
    if (jmedia_paths != nullptr) {
        const jsize media_count = env->GetArrayLength(jmedia_paths);
        media_paths.reserve(static_cast<size_t>(media_count));
        for (jsize i = 0; i < media_count; ++i) {
            auto * item = static_cast<jstring>(env->GetObjectArrayElement(jmedia_paths, i));
            media_paths.push_back(jstring_to_utf8(env, item));
            if (item != nullptr) env->DeleteLocalRef(item);
        }
    }

    const std::vector<uint8_t> base_prompt_bytes =
        jbytes_to_vector(env, jbase_prompt);
    std::vector<llama_token> base_prompt_tokens;
    if (!base_prompt_bytes.empty()) {
        const std::string base_prompt_text(
            base_prompt_bytes.begin(), base_prompt_bytes.end());
        base_prompt_tokens = tokenize(s->vocab, base_prompt_text, true);
    }
    std::vector<uint8_t> base_fingerprint =
        jbytes_to_vector(env, jbase_fingerprint);
    const std::string base_cache_path = jstring_to_utf8(env, jbase_cache_path);
    configure_base_cache(s, std::move(base_prompt_tokens),
                         std::move(base_fingerprint), base_cache_path);

    std::vector<llama_token> tokens = tokenize(s->vocab, prompt, true);
    if (tokens.empty()) {
        LOGE("empty prompt after tokenization");
        return -1;
    }

    // Clamp the prompt so that there is room for the requested number of tokens.
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(s->ctx));
    int32_t max_new = std::min<int32_t>(max_tokens, n_ctx - 8);
    if (max_new < 1) {
        max_new = 1;
    }
    bool prompt_trimmed = false;
    const int32_t keep = n_ctx - max_new - 4;
    if (keep > 0 && static_cast<int32_t>(tokens.size()) > keep) {
        const bool had_bos = tokens.front() == llama_vocab_bos(s->vocab);
        tokens.erase(tokens.begin(), tokens.end() - keep);
        if (had_bos && !tokens.empty()) {
            tokens.insert(tokens.begin(), llama_vocab_bos(s->vocab));
        }
        prompt_trimmed = true;
    }

    const size_t base_prefix = s->base_tokens.empty()
        ? 0
        : common_prefix_len(s->base_tokens, tokens);

    llama_memory_t mem = llama_get_memory(s->ctx);
    size_t prefix_len = 0;

    if (!media_paths.empty()) {
        // Multimodal path is intentionally not cached for now: mtmd tokens and
        // positions do not fit the text cache state below.
        if (mem != nullptr) {
            llama_memory_clear(mem, true);
        }
        clear_all_caches(s);
        LOGI("prompt: media path, KV cache cleared");
    } else if (mem != nullptr && !prompt_trimmed) {
        // Prefer the cache that contains the previous answer. If the visible
        // answer was re-tokenized differently (or thinking was stripped from
        // the next prompt), fall back to the prompt-only snapshot. Recurrent
        // models such as LFM3 cannot roll back to an arbitrary common prefix,
        // so only a clean extension of a saved prefix is accepted. The base
        // slot is the last resort: it only covers the system block, but it is
        // shared by every chat and survives restarts.
        if (try_restore_cache(s, s->full_cache, tokens, prefix_len, "full")) {
            s->prompt_cache.clear();
        } else if (try_restore_cache(s, s->prompt_cache, tokens, prefix_len, "prompt")) {
            s->full_cache.clear();
        } else {
            if (!s->base_cache.valid && !s->base_load_attempted) {
                s->base_load_attempted = true;
                load_base_cache_file(s);
            }
            if (!try_restore_base_cache(s, tokens, prefix_len)) {
                if (mem != nullptr) {
                    llama_memory_clear(mem, true);
                }
                clear_all_caches(s);
            } else {
                clear_all_caches(s);
            }
        }
    } else {
        if (mem != nullptr) {
            llama_memory_clear(mem, true);
        }
        clear_all_caches(s);
    }

    if (media_paths.empty()) {
        LOGI("prompt: %zu tokens, cache hit %zu, evaluate %zu, base boundary %zu",
             tokens.size(), prefix_len, tokens.size() - prefix_len, base_prefix);
    }

    // Optional callback methods.
    jclass callback_class = nullptr;
    jmethodID on_token = nullptr;
    jmethodID on_prompt_progress = nullptr;
    jmethodID on_gen_progress = nullptr;
    if (callback != nullptr) {
        callback_class = env->GetObjectClass(callback);
        on_token = env->GetMethodID(callback_class, "onToken", "([B)Z");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            on_token = nullptr;
        }
        on_prompt_progress = env->GetMethodID(callback_class, "onPromptProgress", "(IIF)V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            on_prompt_progress = nullptr;
        }
        on_gen_progress = env->GetMethodID(callback_class, "onGenerationProgress", "(IF)V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            on_gen_progress = nullptr;
        }
    }

    // Feed the prompt in small chunks so progress/speed can be reported in real time.
    const auto prompt_start = std::chrono::steady_clock::now();
    int32_t total_prompt = 0;
    int32_t processed = 0;

    if (!media_paths.empty()) {
        if (s->mctx == nullptr) {
            LOGE("media supplied but mmproj is not loaded");
            return -7;
        }

        std::vector<mtmd_bitmap *> bitmaps;
        bitmaps.reserve(media_paths.size());
        bool media_ok = true;
        for (const std::string & media_path : media_paths) {
            if (s->cancel) {
                media_ok = false;
                break;
            }
            std::vector<unsigned char> data;
            if (!read_file_bytes(media_path, data, 64ull * 1024ull * 1024ull)) {
                LOGE("failed to read media: %s", media_path.c_str());
                media_ok = false;
                break;
            }
            mtmd_helper_init_opt opts = mtmd_helper_init_opt_default();
            mtmd_helper_bitmap_wrapper wrapper = mtmd_helper_bitmap_init_from_buf(
                s->mctx, data.data(), data.size(), false, opts);
            if (wrapper.bitmap == nullptr) {
                LOGE("failed to decode media: %s", media_path.c_str());
                media_ok = false;
                break;
            }
            bitmaps.push_back(wrapper.bitmap);
        }
        if (!media_ok) {
            for (mtmd_bitmap * bitmap : bitmaps) mtmd_bitmap_free(bitmap);
            return s->cancel ? -2 : -8;
        }

        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        if (chunks == nullptr) {
            for (mtmd_bitmap * bitmap : bitmaps) mtmd_bitmap_free(bitmap);
            return -3;
        }

        // The chat template already emits BOS when it defines bos_token.
        const bool add_special = s->chat_template.find("bos_token") == std::string::npos;
        mtmd_input_text text{prompt.c_str(), prompt.size(), add_special, true};
        int32_t rc = mtmd_tokenize(s->mctx, chunks, &text, bitmaps.data(), bitmaps.size());
        for (mtmd_bitmap * bitmap : bitmaps) mtmd_bitmap_free(bitmap);
        if (rc != 0) {
            LOGE("mtmd_tokenize failed: %d", rc);
            mtmd_input_chunks_free(chunks);
            return -3;
        }

        total_prompt = static_cast<int32_t>(mtmd_helper_get_n_tokens(chunks));
        const llama_pos n_pos = mtmd_helper_get_n_pos(chunks);
        if (n_pos < 0 || n_pos >= n_ctx - 4) {
            LOGE("multimodal prompt too long: n_pos=%d n_ctx=%d", static_cast<int>(n_pos), n_ctx);
            mtmd_input_chunks_free(chunks);
            return -9;
        }
        max_new = std::min<int32_t>(max_new, n_ctx - static_cast<int32_t>(n_pos) - 4);
        if (max_new < 1) max_new = 1;

        if (on_prompt_progress != nullptr) {
            env->CallVoidMethod(callback, on_prompt_progress, 0, total_prompt, 0.0f);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }

        llama_pos new_n_past = 0;
        rc = mtmd_helper_eval_chunks(s->mctx, s->ctx, chunks, 0, 0,
                                     s->n_batch, true, &new_n_past);
        mtmd_input_chunks_free(chunks);
        if (rc != 0) {
            LOGE("multimodal prompt decode failed: %d", rc);
            return rc == 2 ? -2 : -3;
        }
        processed = total_prompt;
        if (on_prompt_progress != nullptr) {
            const double elapsed = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - prompt_start).count();
            const float speed = elapsed > 0.0 ? static_cast<float>(processed / elapsed) : 0.0f;
            env->CallVoidMethod(callback, on_prompt_progress, processed, total_prompt, speed);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    } else {
        // Larger prompt chunks reduce per-decode overhead. Keep the cap at 256
        // so progress updates remain reasonably frequent on slow CPUs.
        const int32_t n_chunk = std::max(32, std::min(s->n_batch, 256));
        total_prompt = static_cast<int32_t>(tokens.size());
        const int32_t cached = static_cast<int32_t>(prefix_len);
        int32_t processed_new = 0;
        if (on_prompt_progress != nullptr) {
            env->CallVoidMethod(callback, on_prompt_progress, cached, total_prompt, 0.0f);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }

        // Evaluate everything except the last token. The state at this point is
        // exactly the reusable prefix; the final token is decoded afterwards so
        // the sampler has fresh logits.
        const size_t n_prefix_eval = tokens.size() - 1;
        // Capture the base slot exactly at the system-block boundary during the
        // first prefill. These tokens are evaluated anyway, so this adds no
        // work; the snapshot is written to disk asynchronously afterwards.
        size_t base_capture_at = 0;
        if (!prompt_trimmed && prefix_len == 0 && !s->base_cache.valid &&
            base_prefix >= kMinBaseCacheTokens &&
            base_prefix <= n_prefix_eval) {
            base_capture_at = base_prefix;
        }

        size_t pos = prefix_len;
        while (pos < n_prefix_eval) {
            if (base_capture_at != 0 && pos == base_capture_at) {
                capture_base_cache(s, tokens, pos);
                base_capture_at = 0;
                continue;
            }
            if (s->cancel) {
                return -2;
            }
            size_t limit = n_prefix_eval;
            if (base_capture_at != 0 && base_capture_at > pos) {
                limit = std::min(limit, base_capture_at);
            }
            const int32_t n = static_cast<int32_t>(
                std::min<size_t>(static_cast<size_t>(n_chunk), limit - pos));
            llama_batch batch = llama_batch_get_one(tokens.data() + pos, n);
            const int32_t rc = llama_decode(s->ctx, batch);
            if (rc != 0) {
                LOGE("prompt decode failed: %d", rc);
                return rc == 2 ? -2 : -3;
            }
            pos += static_cast<size_t>(n);
            processed_new += n;
            if (on_prompt_progress != nullptr) {
                const double elapsed = std::chrono::duration<double>(
                    std::chrono::steady_clock::now() - prompt_start).count();
                const float speed = elapsed > 0.0
                    ? static_cast<float>(processed_new / elapsed) : 0.0f;
                env->CallVoidMethod(callback, on_prompt_progress,
                                    cached + processed_new, total_prompt, speed);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                }
            }
        }
        if (base_capture_at != 0 && pos == base_capture_at) {
            capture_base_cache(s, tokens, pos);
        }

        if (mem != nullptr && n_prefix_eval > 0) {
            std::vector<llama_token> prefix_tokens(
                tokens.begin(), tokens.begin() + static_cast<ptrdiff_t>(n_prefix_eval));
            if (!save_cache_slot(s->prompt_cache, s->ctx, prefix_tokens,
                                 kMaxCacheStateBytes, "prompt")) {
                clear_all_caches(s);
            }
        } else {
            clear_all_caches(s);
        }

        if (s->cancel) {
            return -2;
        }

        llama_batch last_batch = llama_batch_get_one(
            tokens.data() + n_prefix_eval, 1);
        const int32_t last_rc = llama_decode(s->ctx, last_batch);
        if (last_rc != 0) {
            LOGE("final prompt token decode failed: %d", last_rc);
            return last_rc == 2 ? -2 : -3;
        }
        processed_new += 1;
        if (on_prompt_progress != nullptr) {
            const double elapsed = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - prompt_start).count();
            const float speed = elapsed > 0.0
                ? static_cast<float>(processed_new / elapsed) : 0.0f;
            env->CallVoidMethod(callback, on_prompt_progress,
                                cached + processed_new, total_prompt, speed);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }

    // Sampler chain.
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (smpl == nullptr) {
        return -1;
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    std::string pending;
    std::vector<llama_token> generated_tokens;
    generated_tokens.reserve(static_cast<size_t>(std::max(1, max_new)));
    int32_t generated = 0;
    int32_t result = 0;

    const auto gen_start = std::chrono::steady_clock::now();
    auto last_gen_report = gen_start;

    auto emit = [&](const char * data, size_t size) -> bool {
        if (on_token == nullptr || callback == nullptr || size == 0) {
            return true;
        }
        jbyteArray arr = make_bytes(env, data, size);
        if (arr == nullptr) {
            return false;
        }
        const jboolean keep_going = env->CallBooleanMethod(callback, on_token, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep_going != 0;
    };

    for (int32_t i = 0; i < max_new; ++i) {
        if (s->cancel) {
            result = -2;
            break;
        }

        const llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) {
            break;
        }

        char piece[512];
        int32_t n_piece = llama_token_to_piece(s->vocab, id, piece, sizeof(piece), 0, true);
        if (n_piece < 0) {
            std::vector<char> big(static_cast<size_t>(-n_piece));
            n_piece = llama_token_to_piece(s->vocab, id, big.data(),
                                           static_cast<int32_t>(big.size()), 0, true);
            if (n_piece > 0) {
                pending.append(big.data(), static_cast<size_t>(n_piece));
            }
        } else if (n_piece > 0) {
            pending.append(piece, static_cast<size_t>(n_piece));
        }

        const size_t complete = utf8_complete_prefix(pending);
        if (complete > 0) {
            if (!emit(pending.data(), complete)) {
                pending.erase(0, complete);
                break;
            }
            pending.erase(0, complete);
        }

        ++generated;

        const auto now = std::chrono::steady_clock::now();
        if (on_gen_progress != nullptr &&
            std::chrono::duration<double>(now - last_gen_report).count() >= 0.2) {
            const double elapsed = std::chrono::duration<double>(now - gen_start).count();
            const float speed = elapsed > 0.0 ? static_cast<float>(generated / elapsed) : 0.0f;
            env->CallVoidMethod(callback, on_gen_progress, generated, speed);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            last_gen_report = now;
        }

        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        const int32_t rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            LOGE("token decode failed: %d", rc);
            result = rc == 2 ? -2 : -4;
            break;
        }
        generated_tokens.push_back(id);
    }

    if (!pending.empty()) {
        emit(pending.data(), pending.size());
    }

    if (on_gen_progress != nullptr && generated > 0) {
        const double elapsed = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - gen_start).count();
        const float speed = elapsed > 0.0 ? static_cast<float>(generated / elapsed) : 0.0f;
        env->CallVoidMethod(callback, on_gen_progress, generated, speed);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }

    // Keep the post-generation KV state as well. The next turn's prompt normally
    // contains this answer verbatim, so restoring the full slot avoids
    // re-evaluating the assistant message. If the answer is re-tokenized
    // differently (or thinking was stripped), the prompt-only slot remains as a
    // fallback.
    if (result == 0 && media_paths.empty() && !prompt_trimmed &&
        !generated_tokens.empty()) {
        std::vector<llama_token> full_tokens;
        full_tokens.reserve(tokens.size() + generated_tokens.size());
        full_tokens.insert(full_tokens.end(), tokens.begin(), tokens.end());
        full_tokens.insert(full_tokens.end(), generated_tokens.begin(), generated_tokens.end());

        if (save_cache_slot(s->full_cache, s->ctx, full_tokens,
                            kMaxCacheStateBytes, "full")) {
            if (s->prompt_cache.valid &&
                s->prompt_cache.bytes() + s->full_cache.bytes() > kMaxCacheStateBytes) {
                LOGI("cache: dropping prompt fallback to stay under %zu MiB",
                     kMaxCacheStateBytes / 1048576);
                s->prompt_cache.clear();
            }
        }
    }

    llama_sampler_free(smpl);
    if (callback_class != nullptr) {
        env->DeleteLocalRef(callback_class);
    }

    if (result != 0) {
        return result;
    }
    return generated;
}

JNIEXPORT void JNICALL
Java_com_llamaport_app_LlamaBridge_cancel(JNIEnv *, jclass, jlong handle) {
    Session * s = from_handle(handle);
    if (s != nullptr) {
        s->cancel = true;
    }
}

} // extern "C"

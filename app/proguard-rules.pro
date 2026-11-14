# Keep JNI entry points.
-keep class com.llamaport.app.LlamaBridge { *; }
-keep class com.llamaport.app.LlamaBridge$TokenCallback { *; }

# The native code resolves callback methods on the runtime (anonymous) class.
-keepclassmembers class * implements com.llamaport.app.LlamaBridge$TokenCallback {
    public boolean onToken(byte[]);
    public void onPromptProgress(int, int, float);
    public void onGenerationProgress(int, float);
}

# Binder/AIDL classes are resolved by name across the :llm process boundary.
-keep class com.llamaport.app.ILlmService { *; }
-keep class com.llamaport.app.ILlmService$Stub { *; }
-keep class com.llamaport.app.ILlmCallback { *; }
-keep class com.llamaport.app.ILlmCallback$Stub { *; }
-keep class com.llamaport.app.LlmService { *; }
-keep class com.llamaport.app.LlmService$* { *; }

# QuickJS bridge and its native callback target.
-keep class com.llamaport.app.QuickJsBridge { *; }
-keep class com.llamaport.app.QuickJsHost { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class * implements com.llamaport.app.ILlmCallback {
    public *;
}

package com.llamaport.app;

/**
 * Streaming callback from the inference process to the UI process.
 * All methods are one-way to keep token generation off the binder thread.
 */
oneway interface ILlmCallback {
    void onToken(in byte[] chunk);
    void onPromptProgress(int processed, int total, float tokensPerSecond);
    void onGenerationProgress(int generated, float tokensPerSecond);
    void onComplete(int generated, String error);
}
package com.llamaport.app;

import com.llamaport.app.ILlmCallback;

interface ILlmService {
    /** Returns 0 on success, negative error code on failure. */
    int load(String path, int nCtx, int threads, int threadsBatch, boolean useMmap, int kvType, int flashAttn, int nBatch, int nUbatch, String mmprojPath);

    /** Blocking generation call. Returns generated token count or a negative error code. */
    int generate(in byte[] prompt, int maxTokens, float temp, float topP, int topK, int seed, in String[] mediaPaths, in byte[] basePrompt, in byte[] baseFingerprint, String baseCachePath, ILlmCallback callback);

    void stop();

    /** Frees the model. Blocks until a running generation has stopped. */
    void unload();

    String modelInfo();

    String systemInfo();

    /** "availMb|totalMb|low|thresholdMb|pid". */
    String memInfo();

    byte[] applyTemplate(in String[] roles, in String[] contents, boolean addAssistant, boolean enableThinking);

    /** "fileBytes|kvBytes|nLayer|nEmbd|nHead|nHeadKv|params|contextLength" or empty on failure. */
    String estimateModel(String path, int nCtx, int kvType);

    /** Runs malloc_trim and returns available memory in MiB. */
    long trimMemory();

    /** Hard reset: frees everything and kills the :llm process. */
    void killProcess();
}
package com.llamaport.app

/**
 * Thin JNI bridge to the prebuilt llama.cpp shared libraries.
 *
 * All strings crossing the boundary are plain UTF-8 byte arrays so that
 * multi-byte characters (emoji, CJK, Cyrillic) survive untouched.
 */
object LlamaBridge {

    fun interface TokenCallback {
        /**
         * Called from the generation thread with a complete UTF-8 chunk.
         * Return false to stop generation.
         */
        fun onToken(bytes: ByteArray): Boolean

        /**
         * Called while the prompt is being processed.
         * @param processed number of prompt tokens processed so far
         * @param total total number of prompt tokens
         * @param tokensPerSecond average prompt processing speed since the start
         */
        fun onPromptProgress(processed: Int, total: Int, tokensPerSecond: Float) {}

        /**
         * Called a few times per second while tokens are being generated.
         * @param generated number of tokens generated so far
         * @param tokensPerSecond average generation speed since the first token
         */
        fun onGenerationProgress(generated: Int, tokensPerSecond: Float) {}
    }

    init {
        System.loadLibrary("llamaport")
    }

    external fun backendInit()
    external fun backendFree()
    external fun systemInfo(): String

    /** Returns a native handle or 0 on failure. */
    external fun loadModel(path: String, nCtx: Int, nThreads: Int): Long

    /**
     * Extended loader.
     * @param nThreads threads for single-token generation (normally big cores)
     * @param nThreadsBatch threads for prompt/batch processing
     * @param useMmap map model weights instead of reading them into anonymous memory
     * @param kvType 0 = f16 KV cache, 1 = q8_0 KV cache
     * @param flashAttn 0 = auto, 1 = enabled, 2 = disabled
     * @return positive handle, or a negative error code (-1 path, -2 model, -3 context, -4 params)
     */
    external fun loadModelEx(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        useMmap: Boolean,
        kvType: Int,
        flashAttn: Int,
        nBatch: Int,
        nUbatch: Int,
        mmprojPath: String?,
    ): Long

    /**
     * Reads only GGUF metadata (vocab_only) and estimates memory.
     * Returns "fileBytes|kvBytes|nLayer|nEmbd|nHead|nHeadKv|nParams" or "".
     */
    external fun estimateModel(path: String, nCtx: Int, kvType: Int): String

    /** Returns malloc_trim result (1 if pages were returned to the OS). */
    external fun trimMemory(): Int
    external fun freeModel(handle: Long)

    /** Human readable "description|params|trainCtx|vocab" string. */
    external fun modelInfo(handle: Long): String

    /** Applies the model chat template. Returns empty array on failure. */
    external fun applyTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
        enableThinking: Boolean,
    ): ByteArray

    /**
     * Generates tokens into [callback].
     * @param basePrompt system-only portion of the prompt, used to build and
     *   restore the persistent base cache; may be empty to disable it
     * @param baseFingerprint 32-byte SHA-256 cache key; empty to disable it
     * @param baseCachePath file used to persist the base cache; empty to keep
     *   the base snapshot in RAM only
     * Returns number of generated tokens, or a negative value on error/abort.
     */
    external fun generate(
        handle: Long,
        prompt: ByteArray,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        seed: Int,
        mediaPaths: Array<String>,
        basePrompt: ByteArray,
        baseFingerprint: ByteArray,
        baseCachePath: String,
        callback: TokenCallback,
    ): Int

    external fun cancel(handle: Long)
}

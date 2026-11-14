package com.llamaport.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * Inference service living in the ":llm" process.
 *
 * Keeping llama.cpp in a separate process means an OOM kill or a native crash
 * takes down only this service; the UI process survives and can offer a reload.
 */
class LlmService : Service() {

    companion object {
        private const val TAG = "LlmService"
        private const val CHANNEL_ID = "llm_status"
        private const val NOTIFICATION_ID = 1001
    }

    private val ioLock = Any()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var cancelFlag: Boolean = false

    @Volatile
    private var generating: Boolean = false

    @Volatile
    private var modelInfo: String = ""

    @Volatile
    private var foregroundStarted: Boolean = false

    private lateinit var activityManager: ActivityManager

    private val binder = object : ILlmService.Stub() {

        override fun load(
            path: String,
            nCtx: Int,
            threads: Int,
            threadsBatch: Int,
            useMmap: Boolean,
            kvType: Int,
            flashAttn: Int,
            nBatch: Int,
            nUbatch: Int,
            mmprojPath: String?,
        ): Int {
            Log.i(
                TAG,
                "load start: $path nCtx=$nCtx threads=$threads/$threadsBatch " +
                    "mmap=$useMmap kv=$kvType fa=$flashAttn batch=$nBatch/$nUbatch mmproj=$mmprojPath",
            )
            synchronized(ioLock) {
                if (generating) return -10
                ensureForeground("Загрузка модели…")
                freeLocked()
                val h = LlamaBridge.loadModelEx(
                    path = path,
                    nCtx = nCtx,
                    nThreads = threads,
                    nThreadsBatch = threadsBatch,
                    useMmap = useMmap,
                    kvType = kvType,
                    flashAttn = flashAttn,
                    nBatch = nBatch,
                    nUbatch = nUbatch,
                    mmprojPath = mmprojPath,
                )
                // Native handles are raw pointers and can have the sign bit set
                // on 64-bit Android. Only tiny negative values are error codes.
                val loadError = h == 0L || (h < 0L && h > -1000L)
                if (loadError) {
                    modelInfo = ""
                    updateForeground("Модель не загружена")
                    Log.e(TAG, "load failed: code=$h")
                    return h.toInt()
                }
                handle = h
                modelInfo = LlamaBridge.modelInfo(h)
                updateForeground("Модель загружена")
                Log.i(TAG, "load done: handle=$h info=$modelInfo")
                return 0
            }
        }

        override fun generate(
            prompt: ByteArray,
            maxTokens: Int,
            temp: Float,
            topP: Float,
            topK: Int,
            seed: Int,
            mediaPaths: Array<String>?,
            callback: ILlmCallback?,
        ): Int {
            synchronized(ioLock) {
                val h = handle
                if (h == 0L) {
                    safeCallback { callback?.onComplete(-1, "Модель не загружена") }
                    return -1
                }
                if (generating) {
                    safeCallback { callback?.onComplete(-6, "Генерация уже выполняется") }
                    return -6
                }
                generating = true
                cancelFlag = false
                Log.i(TAG, "generate start: prompt=${prompt.size} bytes max=$maxTokens")
                ensureForeground("Генерация…")
                var generated = 0
                var error: String? = null
                try {
                    val tokenCallback = object : LlamaBridge.TokenCallback {
                        override fun onToken(bytes: ByteArray): Boolean {
                            if (cancelFlag) return false
                            safeCallback { callback?.onToken(bytes) }
                            return true
                        }

                        override fun onPromptProgress(
                            processed: Int,
                            total: Int,
                            tokensPerSecond: Float,
                        ) {
                            safeCallback {
                                callback?.onPromptProgress(processed, total, tokensPerSecond)
                            }
                        }

                        override fun onGenerationProgress(
                            generated: Int,
                            tokensPerSecond: Float,
                        ) {
                            safeCallback {
                                callback?.onGenerationProgress(generated, tokensPerSecond)
                            }
                        }
                    }
                    generated = LlamaBridge.generate(
                        h,
                        prompt,
                        maxTokens,
                        temp,
                        topP,
                        topK,
                        seed,
                        mediaPaths ?: emptyArray(),
                        tokenCallback,
                    )
                    if (generated < 0 && generated != -2) {
                        error = generationError(generated)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "generation failed", t)
                    error = t.message ?: t.toString()
                } finally {
                    Log.i(TAG, "generate done: generated=$generated error=$error")
                    generating = false
                    LlamaBridge.trimMemory()
                    updateForeground("Модель загружена")
                    safeCallback { callback?.onComplete(generated, error) }
                }
                return generated
            }
        }

        override fun stop() {
            cancelFlag = true
            val h = handle
            if (h != 0L) {
                LlamaBridge.cancel(h)
            }
        }

        override fun unload() {
            cancelFlag = true
            val h = handle
            if (h != 0L) {
                LlamaBridge.cancel(h)
            }
            synchronized(ioLock) {
                freeLocked()
                LlamaBridge.trimMemory()
                modelInfo = ""
                updateForeground("Модель выгружена")
            }
        }

        override fun modelInfo(): String = modelInfo

        override fun systemInfo(): String = try {
            LlamaBridge.systemInfo()
        } catch (t: Throwable) {
            ""
        }

        override fun memInfo(): String {
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            return listOf(
                info.availMem / (1024L * 1024L),
                info.totalMem / (1024L * 1024L),
                if (info.lowMemory) 1 else 0,
                info.threshold / (1024L * 1024L),
                Process.myPid(),
            ).joinToString("|")
        }

        override fun applyTemplate(
            roles: Array<String>?,
            contents: Array<String>?,
            addAssistant: Boolean,
            enableThinking: Boolean,
        ): ByteArray {
            val h = handle
            if (h == 0L || roles == null || contents == null) {
                return ByteArray(0)
            }
            return LlamaBridge.applyTemplate(h, roles, contents, addAssistant, enableThinking)
        }

        override fun estimateModel(path: String, nCtx: Int, kvType: Int): String {
            synchronized(ioLock) {
                if (generating) return ""
                return try {
                    LlamaBridge.estimateModel(path, nCtx, kvType)
                } finally {
                    LlamaBridge.trimMemory()
                }
            }
        }

        override fun trimMemory(): Long {
            LlamaBridge.trimMemory()
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            return info.availMem / (1024L * 1024L)
        }

        override fun killProcess() {
            cancelFlag = true
            val h = handle
            if (h != 0L) {
                LlamaBridge.cancel(h)
            }
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        createChannel()
        try {
            LlamaBridge.backendInit()
        } catch (t: Throwable) {
            Log.e(TAG, "native backend init failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(if (handle != 0L) "Модель загружена" else "Сервис инференса")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        if (!generating) {
            synchronized(ioLock) {
                freeLocked()
            }
        }
        foregroundStarted = false
    }

    private fun freeLocked() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            LlamaBridge.freeModel(h)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Инференс",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Состояние процесса llama.cpp"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("LlamaPort")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun ensureForeground(text: String) {
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification(text))
            foregroundStarted = true
        } else {
            updateForeground(text)
        }
    }

    private fun updateForeground(text: String) {
        if (!foregroundStarted) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private inline fun safeCallback(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "callback failed: ${t.message}")
        }
    }

    private fun generationError(code: Int): String = when (code) {
        -1 -> "Не удалось подготовить промпт"
        -2 -> "Генерация остановлена"
        -3 -> "Ошибка обработки промпта ($code)"
        -4 -> "Ошибка декодирования токена ($code)"
        -6 -> "Генерация уже выполняется"
        else -> "Ошибка генерации (код $code)"
    }
}
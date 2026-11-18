package com.llamaport.app

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val role: String, // system | user | assistant | tool
    val text: String,
    val streaming: Boolean = false,
    /** Legacy stats text kept for old saved sessions; new messages use [metrics]. */
    val stats: String? = null,
    val metrics: MessageMetrics? = null,
    val isError: Boolean = false,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val attachments: List<Attachment> = emptyList(),
)

/** Per-answer performance numbers shown under an assistant message. */
data class MessageMetrics(
    val promptTokens: Int = 0,
    val promptTokensPerSecond: Float = 0f,
    val generatedTokens: Int = 0,
    val generationTokensPerSecond: Float = 0f,
    val durationMs: Long = 0L,
)

data class Settings(
    val contextSize: Int = 4096,
    val threads: Int = 2,
    val threadsBatch: Int = 4,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    val thinkingBudget: Int = 0,
    val systemPrompt: String = "You are a helpful assistant. Answer concisely.",
    val useMmap: Boolean = true,
    val kvType: Int = 0, // 0 = f16, 1 = q8_0
    val flashAttn: Int = 0, // 0 = auto, 1 = enabled, 2 = disabled
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val toolsEnabled: Boolean = false,
    val httpToolEnabled: Boolean = false,
    val enabledTools: Set<String> = setOf(
        "get_time",
        "calculator",
        "read_file",
        "write_file",
        "list_dir",
    ),
)

enum class GenerationPhase { PROMPT, GENERATION }

data class GenerationProgress(
    val phase: GenerationPhase,
    val processed: Int,
    val total: Int,
    val tokensPerSecond: Float,
)

data class ModelEstimate(
    val fileBytes: Long,
    val kvBytes: Long,
    val layers: Int,
    val nEmbd: Int,
    val nHead: Int,
    val nHeadKv: Int,
    val contextLength: Int = 0,
    val estimateCtx: Int = 0,
    val estimateKvType: Int = 0,
)

data class MemoryWarning(
    val file: File,
    val needMb: Long,
    val availMb: Long,
    val suggestedCtx: Int,
    val suggestedKvType: Int,
)

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val settings: Settings = Settings(),
    val modelLoaded: Boolean = false,
    val modelName: String? = null,
    val modelInfo: String? = null,
    val modelCap: Int = 8192,
    val estimate: ModelEstimate? = null,
    val mmprojName: String? = null,
    val mmprojBytes: Long = 0L,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val loadingModel: Boolean = false,
    val generating: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val models: List<File> = emptyList(),
    val projectors: List<File> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val activeSessionTitle: String = "Новый чат",
    val sessionsLoaded: Boolean = false,
    val pendingAttachments: List<Attachment> = emptyList(),
    val scrollRequest: Int = 0,
    val backendInfo: String = "",
    val progress: GenerationProgress? = null,
    val memAvailableMb: Long = 0L,
    val memTotalMb: Long = 0L,
    val memLow: Boolean = false,
    val lastNeedMb: Long = 0L,
    val memoryWarning: MemoryWarning? = null,
    val freeMemoryBusy: Boolean = false,
    val serviceDead: Boolean = false,
    val showFreeMemory: Boolean = false,
)

/**
 * Live answer of the running generation, kept outside [UiState] on purpose.
 *
 * With the text inside [UiState] every token replaced the whole message list
 * and woke up every composable that observes the chat state (the scaffold, the
 * bars, the drawer). Here a token only invalidates the bubble that renders it.
 * The ViewModel coalesces writes to the display frame, and text is committed
 * to [ChatMessage.text] once the answer is finished.
 */
class StreamingAnswer {
    /** Id of the message being streamed, or 0 when nothing is streaming. */
    var messageId by mutableStateOf(0L)
        private set
    var text by mutableStateOf("")
        private set
    /** Live tok/s shown under the answer and in the input bar. */
    var speed by mutableStateOf(0f)
        private set
    /** Bumped on every visible change, so scroll effects can observe it. */
    var revision by mutableStateOf(0)
        private set

    private val builder = StringBuilder()

    fun begin(id: Long) {
        builder.setLength(0)
        messageId = id
        text = ""
        speed = 0f
        revision++
    }

    /** Appends [chunk] only while [id] is still the live message. */
    fun append(id: Long, chunk: String) {
        if (messageId != id || chunk.isEmpty()) return
        builder.append(chunk)
        text = builder.toString()
        revision++
    }

    fun setSpeed(id: Long, value: Float) {
        if (messageId != id || value <= 0f) return
        if (kotlin.math.abs(value - speed) < 0.05f) return
        speed = value
    }

    /** Clears the live answer; with [id] it only clears that message. */
    fun end(id: Long? = null) {
        if (id != null && messageId != id) return
        val idle = messageId == 0L && text.isEmpty() && speed == 0f
        if (idle) return
        messageId = 0L
        builder.setLength(0)
        text = ""
        speed = 0f
        revision++
    }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "ChatViewModel"
        const val MB = 1024L * 1024L
        const val RESERVE_MB = 300L
        const val MAX_ATTACHMENT_BYTES = 64L * MB
        const val DEFAULT_CONTEXT_CAP = 8192
        const val MEDIA_MARKER = "\u003c__media__\u003e"
        const val THINK_OPEN = "\u003cthink\u003e"
        const val THINK_CLOSE = "\u003c/think\u003e"
        /** Bump together with the native base-cache format. */
        const val BASE_CACHE_FORMAT = "llamaport-base-v1"
        const val LLAMA_BUILD = "b12129"
        const val BASE_CACHE_KEEP_FILES = 3
        /** At most one live-text update per display frame at 120 Hz. */
        const val LIVE_FLUSH_MS = 8L
        /** Prompt progress is redrawn at most ten times per second. */
        const val PROMPT_UI_MS = 100L
        /** tok/s changes slowly: four updates per second is plenty. */
        const val SPEED_UI_MS = 250L
        val HEAVY_PACKAGES = listOf(
            "com.android.chrome",
            "com.google.android.youtube",
            "com.instagram.android",
            "com.telegram.messenger",
            "com.whatsapp",
            "com.vkontakte.android",
            "com.tencent.mm",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
        )
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Text and speed of the answer being generated; see [StreamingAnswer]. */
    val live = StreamingAnswer()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingTokens = StringBuilder()
    private val pendingLock = Any()
    private var pendingId = 0L
    private var flushScheduled = false
    private var lastFlushAt = 0L
    private val flushTokensRunnable = Runnable { flushLiveTokens() }

    private val appContext = app.applicationContext
    private val modelsDir: File = File(app.filesDir, "models").apply { mkdirs() }
    private val attachmentsDir: File = File(app.filesDir, "attachments").apply { mkdirs() }
    private val promptCacheDir: File = File(app.filesDir, "prompt_cache").apply { mkdirs() }
    private val chatStore = ChatStore(app.filesDir)
    private val sessionMessages = java.util.concurrent.ConcurrentHashMap<String, List<ChatMessage>>()
    private val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val toolRegistry = ToolRegistry(app)

    private var saveJob: Job? = null
    private var estimateJob: Job? = null
    @Volatile
    private var dirty = false

    @Volatile
    private var service: ILlmService? = null

    @Volatile
    private var binding = false

    @Volatile
    private var serviceDeferred = CompletableDeferred<ILlmService?>()

    @Volatile
    private var suppressServiceDeath = false

    @Volatile
    private var serviceEpoch = 0

    @Volatile
    private var serviceLossHandled = false

    @Volatile
    private var cancelRequested = false

    private var generationSeq = 0
    private var statusSeq = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = ILlmService.Stub.asInterface(binder)
            service = svc
            binding = false
            serviceLossHandled = false
            suppressServiceDeath = false
            val epoch = ++serviceEpoch
            if (!serviceDeferred.isCompleted) {
                serviceDeferred.complete(svc)
            }
            try {
                binder?.linkToDeath({
                    handleServiceLost("Процесс инференса был остановлен системой", epoch)
                }, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "linkToDeath failed: ${t.message}")
            }
            viewModelScope.launch {
                val info = runCatching { svc.systemInfo() }.getOrDefault("")
                // A restarted service starts with no model, so a previous death notice
                // must survive the automatic reconnect until the user reloads the model.
                _state.update { it.copy(backendInfo = info) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleServiceLost("Соединение с процессом инференса потеряно", serviceEpoch)
        }

        override fun onBindingDied(name: ComponentName?) {
            handleServiceLost("Процесс инференса завершился", serviceEpoch)
        }

        override fun onNullBinding(name: ComponentName?) {
            binding = false
            if (!serviceDeferred.isCompleted) serviceDeferred.complete(null)
            _state.update { it.copy(error = "Не удалось запустить процесс инференса") }
        }
    }

    private fun setStatus(message: String?, autoClearMs: Long = 4000) {
        val seq = ++statusSeq
        _state.update { it.copy(status = message) }
        if (message != null && autoClearMs > 0) {
            viewModelScope.launch {
                delay(autoClearMs)
                if (statusSeq == seq) {
                    _state.update { it.copy(status = null) }
                }
            }
        }
    }

    init {
        refreshModels()
        refreshMemory()
        loadSessions()
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                refreshMemory()
            }
        }
    }

    // --------------------------------------------------------------- sessions

    private fun loadSessions() {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { chatStore.load() }
            sessionMessages.clear()
            for ((id, messages) in stored.messages) {
                sessionMessages[id] = messages
            }
            // Rebuild derived fields from the messages so old files also lose any
            // thinking tags that were stored in previews before the splitter existed.
            // Empty chats are not part of the history and are dropped on load.
            val sessions = stored.sessions.mapNotNull { summary ->
                val messages = stored.messages[summary.id].orEmpty()
                val count = messages.count { it.role == "user" || it.role == "assistant" }
                if (count == 0) {
                    sessionMessages.remove(summary.id)
                    null
                } else {
                    summary.copy(
                        preview = previewOf(messages),
                        messageCount = count,
                    )
                }
            }.toMutableList()
            if (sessions.isEmpty()) {
                sessions += createSummary(null)
            }
            val active = sessions.firstOrNull { it.id == stored.activeId } ?: sessions.maxByOrNull { it.updatedAt } ?: sessions.first()
            sessionMessages.putIfAbsent(active.id, emptyList())
            _state.update {
                it.copy(
                    sessions = sessions,
                    activeSessionId = active.id,
                    activeSessionTitle = active.title,
                    messages = sessionMessages[active.id].orEmpty(),
                    sessionsLoaded = true,
                )
            }
        }
    }

    private fun createSummary(title: String?): SessionSummary {
        val now = System.currentTimeMillis()
        return SessionSummary(
            id = UUID.randomUUID().toString(),
            title = title ?: "Новый чат",
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun activeSessionSummary(): SessionSummary? {
        val state = _state.value
        return state.sessions.firstOrNull { it.id == state.activeSessionId }
    }

    private fun deriveTitle(messages: List<ChatMessage>, current: String): String {
        if (current != "Новый чат") return current
        val firstUser = messages.firstOrNull { it.role == "user" && it.text.isNotBlank() } ?: return current
        val line = firstUser.text.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "Новый чат" }
        return line.take(48).let { if (line.length > 48) "$it…" else it }
    }

    /**
     * Preview comes from the final answer only. Thinking traces never leak into
     * the chat list; a chat without an answer yet shows "Думает…" in the UI.
     */
    private fun previewOf(messages: List<ChatMessage>): String {
        val source = messages.lastOrNull {
            it.role == "assistant" && !it.isError && it.text.isNotBlank()
        } ?: return ""
        val answer = splitThinking(source.text).answer
        if (answer.isBlank()) return ""
        return answer.lineSequence().firstOrNull()?.trim().orEmpty().take(80)
    }

    /** Replaces the active chat messages and keeps the session list in sync. */
    private fun updateMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        _state.update { state ->
            val messages = transform(state.messages)
            val activeId = state.activeSessionId
            if (activeId != null) sessionMessages[activeId] = messages
            val sessions = state.sessions.map { summary ->
                if (summary.id == activeId) {
                    summary.copy(
                        title = deriveTitle(messages, summary.title),
                        updatedAt = System.currentTimeMillis(),
                        messageCount = messages.count { it.role == "user" || it.role == "assistant" },
                        preview = previewOf(messages),
                        modelName = state.modelName?.takeIf { it.isNotBlank() }
                            ?: summary.modelName,
                    )
                } else {
                    summary
                }
            }
            state.copy(
                messages = messages,
                sessions = sessions,
                activeSessionTitle = sessions.firstOrNull { it.id == activeId }?.title
                    ?: state.activeSessionTitle,
            )
        }
        dirty = true
    }

    // ------------------------------------------------------- live streaming

    /**
     * Buffers a token for [StreamingAnswer] and schedules a single UI update
     * per frame. Called from the inference callback thread, so the buffer is
     * guarded; the state write itself happens on the main thread.
     */
    private fun queueLiveToken(id: Long, chunk: String) {
        val delayMs: Long
        synchronized(pendingLock) {
            if (pendingId != id) {
                pendingId = id
                pendingTokens.setLength(0)
            }
            pendingTokens.append(chunk)
            if (flushScheduled) return
            flushScheduled = true
            delayMs = (lastFlushAt + LIVE_FLUSH_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        }
        mainHandler.postDelayed(flushTokensRunnable, delayMs)
    }

    private fun flushLiveTokens() {
        val id: Long
        val chunk: String
        synchronized(pendingLock) {
            flushScheduled = false
            lastFlushAt = SystemClock.uptimeMillis()
            if (pendingTokens.isEmpty()) return
            id = pendingId
            chunk = pendingTokens.toString()
            pendingTokens.setLength(0)
        }
        live.append(id, chunk)
    }

    fun selectSession(id: String) {
        if (_state.value.generating || id == _state.value.activeSessionId) return
        val state = _state.value
        state.activeSessionId?.let { sessionMessages[it] = state.messages }
        val target = state.sessions.firstOrNull { it.id == id } ?: return
        sessionMessages.putIfAbsent(id, emptyList())
        _state.update {
            it.copy(
                activeSessionId = id,
                activeSessionTitle = target.title,
                messages = sessionMessages[id].orEmpty(),
                input = "",
                error = null,
                pendingAttachments = emptyList(),
                scrollRequest = it.scrollRequest + 1,
            )
        }
        scheduleSave()
    }

    fun newChat() {
        if (_state.value.generating) return
        val state = _state.value
        if (state.messages.isEmpty()) {
            _state.update {
                it.copy(
                    input = "",
                    error = null,
                    pendingAttachments = emptyList(),
                )
            }
            return
        }
        state.activeSessionId?.let { sessionMessages[it] = state.messages }
        val summary = createSummary(null)
        sessionMessages[summary.id] = emptyList()
        _state.update {
            it.copy(
                sessions = it.sessions + summary,
                activeSessionId = summary.id,
                activeSessionTitle = summary.title,
                messages = emptyList(),
                input = "",
                error = null,
                pendingAttachments = emptyList(),
                scrollRequest = it.scrollRequest + 1,
            )
        }
        scheduleSave()
    }

    fun renameSession(id: String, title: String) {
        val clean = title.trim().take(80)
        if (clean.isBlank()) return
        _state.update { state ->
            state.copy(
                sessions = state.sessions.map { if (it.id == id) it.copy(title = clean) else it },
                activeSessionTitle = if (id == state.activeSessionId) clean else state.activeSessionTitle,
            )
        }
        scheduleSave()
    }

    /** Remembers just enough to undo the last deletion from the snackbar. */
    private data class DeletedSession(
        val summary: SessionSummary,
        val messages: List<ChatMessage>,
        val wasActive: Boolean,
    )

    private var lastDeleted: DeletedSession? = null

    fun deleteSession(id: String) {
        if (_state.value.generating) return
        val state = _state.value
        val target = state.sessions.firstOrNull { it.id == id } ?: return
        lastDeleted = DeletedSession(
            summary = target,
            messages = sessionMessages[id].orEmpty(),
            wasActive = id == state.activeSessionId,
        )
        val remaining = state.sessions.filterNot { it.id == id }
        sessionMessages.remove(id)
        if (remaining.isEmpty()) {
            val summary = createSummary(null)
            sessionMessages[summary.id] = emptyList()
            _state.update {
                it.copy(
                    sessions = listOf(summary),
                    activeSessionId = summary.id,
                    activeSessionTitle = summary.title,
                    messages = emptyList(),
                    scrollRequest = it.scrollRequest + 1,
                )
            }
        } else if (id == state.activeSessionId) {
            val next = remaining.maxByOrNull { it.updatedAt } ?: remaining.first()
            sessionMessages.putIfAbsent(next.id, emptyList())
            _state.update {
                it.copy(
                    sessions = remaining,
                    activeSessionId = next.id,
                    activeSessionTitle = next.title,
                    messages = sessionMessages[next.id].orEmpty(),
                    scrollRequest = it.scrollRequest + 1,
                )
            }
        } else {
            _state.update { it.copy(sessions = remaining) }
        }
        scheduleSave()
    }

    /** Puts the last deleted chat back, unless another deletion happened since. */
    fun undoDelete() {
        val deleted = lastDeleted ?: return
        lastDeleted = null
        sessionMessages[deleted.summary.id] = deleted.messages
        _state.update { state ->
            val restoredActiveId = if (deleted.wasActive) deleted.summary.id else state.activeSessionId
            val sessions = (state.sessions + deleted.summary)
                .filter { it.messageCount > 0 || it.id == restoredActiveId || it.id == deleted.summary.id }
                .sortedByDescending { it.updatedAt }
            state.copy(
                sessions = sessions,
                activeSessionId = restoredActiveId,
                activeSessionTitle = sessions.firstOrNull { it.id == restoredActiveId }?.title
                    ?: state.activeSessionTitle,
                messages = if (deleted.wasActive) deleted.messages else state.messages,
                scrollRequest = if (deleted.wasActive) state.scrollRequest + 1 else state.scrollRequest,
            )
        }
        scheduleSave()
    }

    fun clearUndo() {
        lastDeleted = null
    }

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(350)
            val snapshot = snapshotSessions()
            withContext(Dispatchers.IO) { chatStore.save(snapshot.sessions, snapshot.messages, snapshot.activeId) }
            dirty = false
        }
    }

    private data class SessionsSnapshot(
        val sessions: List<SessionSummary>,
        val messages: Map<String, List<ChatMessage>>,
        val activeId: String?,
    )

    private fun snapshotSessions(): SessionsSnapshot {
        val state = _state.value
        val map = HashMap(sessionMessages)
        state.activeSessionId?.let { map[it] = state.messages }
        // Empty chats are scratch space, not history: never write them out.
        val kept = state.sessions.filter { summary ->
            map[summary.id].orEmpty().any { it.role == "user" || it.role == "assistant" }
        }
        val ids = kept.mapTo(HashSet()) { it.id }
        val activeId = state.activeSessionId?.takeIf { it in ids }
            ?: kept.maxByOrNull { it.updatedAt }?.id
        return SessionsSnapshot(kept, map.filterKeys { it in ids }, activeId)
    }

    private fun saveNow() {
        val snapshot = snapshotSessions()
        chatStore.save(snapshot.sessions, snapshot.messages, snapshot.activeId)
        dirty = false
    }

    // ------------------------------------------------------------ service glue

    private fun startForegroundService() {
        try {
            appContext.startForegroundService(Intent(appContext, LlmService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "startForegroundService failed: ${t.message}")
        }
    }

    private suspend fun requireService(): ILlmService? {
        service?.let { return it }
        startForegroundService()
        if (!binding) {
            if (serviceDeferred.isCompleted) {
                serviceDeferred = CompletableDeferred()
            }
            binding = true
            val ok = runCatching {
                appContext.bindService(
                    Intent(appContext, LlmService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!ok) {
                binding = false
                if (!serviceDeferred.isCompleted) serviceDeferred.complete(null)
                return null
            }
        }
        val result = withTimeoutOrNull(20_000) { serviceDeferred.await() }
        if (result == null) {
            binding = false
            if (serviceDeferred.isCompleted) {
                serviceDeferred = CompletableDeferred()
            }
        }
        return result
    }

    private fun handleServiceLost(message: String, epoch: Int = serviceEpoch) {
        if (epoch != serviceEpoch || serviceLossHandled) return
        serviceLossHandled = true
        // The live answer never reached the message: keep what was streamed
        // before the process died.
        val liveId = live.messageId
        if (liveId != 0L) {
            val partial = live.text
            if (partial.isNotEmpty()) {
                updateMessages { messages ->
                    messages.map { if (it.id == liveId) it.copy(text = partial) else it }
                }
            }
            live.end()
        }
        val intentional = suppressServiceDeath
        suppressServiceDeath = false
        service = null
        binding = false
        serviceEpoch++
        serviceDeferred = CompletableDeferred()
        if (intentional) {
            _state.update {
                it.copy(
                    modelLoaded = false,
                    modelInfo = null,
                    loadingModel = false,
                    generating = false,
                    progress = null,
                    serviceDead = false,
                    showFreeMemory = false,
                    mmprojName = null,
                    mmprojBytes = 0L,
                    supportsVision = false,
                    supportsAudio = false,
                )
            }
            setStatus("Модель выгружена, память освобождена")
            return
        }
        _state.update {
            it.copy(
                modelLoaded = false,
                modelInfo = null,
                loadingModel = false,
                generating = false,
                progress = null,
                serviceDead = true,
                showFreeMemory = true,
                mmprojName = null,
                mmprojBytes = 0L,
                supportsVision = false,
                supportsAudio = false,
                error = "Модель выгружена из-за нехватки памяти. $message",
            )
        }
        updateMessages { messages ->
            messages.map {
                if (it.streaming) {
                    it.copy(
                        streaming = false,
                        isError = true,
                        text = it.text.ifBlank { "(процесс инференса остановлен)" },
                    )
                } else {
                    it
                }
            }
        }
        scheduleSave()
    }

    private fun refreshMemory() {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        _state.update {
            val avail = info.availMem / MB
            it.copy(
                memAvailableMb = avail,
                memTotalMb = info.totalMem / MB,
                memLow = info.lowMemory,
                showFreeMemory = it.freeMemoryBusy || info.lowMemory ||
                    (it.lastNeedMb > 0 && avail < it.lastNeedMb),
            )
        }
    }

    // ---------------------------------------------------------------- models

    private fun refreshModels() {
        val files = modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            .orEmpty()
        val models = files.filterNot { isMmprojFile(it) }
        val projectors = files.filter { isMmprojFile(it) }
        _state.update { it.copy(models = models, projectors = projectors) }
    }

    private fun isMmprojFile(file: File): Boolean =
        file.name.contains("mmproj", ignoreCase = true)

    private fun normalizeMmprojName(name: String): String =
        name.substringBeforeLast('.').lowercase(Locale.ROOT)
            .removePrefix("mmproj-")
            .removePrefix("mmproj_")

    /** Finds the multimodal projector that belongs to [model], if one was imported. */
    private fun findMmprojFor(model: File): File? {
        val projectors = _state.value.projectors
        if (projectors.isEmpty()) return null
        val modelName = model.name.substringBeforeLast('.').lowercase(Locale.ROOT)
        val exact = projectors.filter { projector ->
            val name = normalizeMmprojName(projector.name)
            name == modelName || name.startsWith("$modelName-") || modelName.startsWith("$name-")
        }
        if (exact.isNotEmpty()) return exact.first()
        // A single projector is only a fallback for models that are clearly
        // multimodal; pairing it with an arbitrary text model would make loading fail.
        val single = projectors.singleOrNull()
        if (single != null) {
            val name = model.name.lowercase(Locale.ROOT)
            if (name.contains("omni") || name.contains("vision") ||
                name.contains("audio") || name.contains("multimodal") ||
                name.contains("vl")
            ) {
                return single
            }
        }
        return null
    }

    fun matchedMmproj(file: File): File? = findMmprojFor(file)

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            setStatus("Копирование модели…", autoClearMs = 0)
            _state.update { it.copy(error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = appContext.contentResolver
                    var name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    if (name.isNullOrBlank()) name = "model-${System.currentTimeMillis()}.gguf"
                    require(name.endsWith(".gguf", ignoreCase = true)) {
                        "Выберите файл с расширением .gguf"
                    }
                    name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

                    val target = File(modelsDir, name)
                    val tmp = File(modelsDir, "$name.part")
                    resolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Не удалось открыть файл" }
                        tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                    }
                    if (target.exists()) target.delete()
                    check(tmp.renameTo(target)) { "Не удалось сохранить файл" }
                    target
                }
            }
            result.onSuccess {
                refreshModels()
                setStatus("Файл «${it.name}» добавлен")
            }.onFailure {
                setStatus(null)
                _state.update { s -> s.copy(error = "Ошибка импорта: ${it.message}") }
            }
        }
    }

    fun deleteModel(file: File) {
        val state = _state.value
        if (state.modelName == file.name && (state.modelLoaded || state.loadingModel)) {
            _state.update { it.copy(error = "Сначала выгрузите активную модель") }
            return
        }
        if (state.mmprojName == file.name && state.modelLoaded) {
            _state.update { it.copy(error = "Сначала выгрузите модель, использующую этот mmproj") }
            return
        }
        runCatching { baseCacheFile(file).delete() }
        file.delete()
        refreshModels()
    }

    private suspend fun parseEstimate(file: File, nCtx: Int, kvType: Int): ModelEstimate? {
        val svc = requireService() ?: return null
        val raw = withContext(Dispatchers.IO) {
            runCatching { svc.estimateModel(file.absolutePath, nCtx, kvType) }.getOrDefault("")
        }
        val parts = raw.split('|')
        if (parts.size < 7) return null
        return runCatching {
            ModelEstimate(
                fileBytes = parts[0].toLong(),
                kvBytes = parts[1].toLong(),
                layers = parts[2].toInt(),
                nEmbd = parts[3].toInt(),
                nHead = parts[4].toInt(),
                nHeadKv = parts[5].toInt(),
                contextLength = parts.getOrNull(7)?.toIntOrNull() ?: 0,
                estimateCtx = nCtx,
                estimateKvType = kvType,
            )
        }.getOrNull()
    }

    private fun mmprojFor(file: File): File? = findMmprojFor(file)

    private fun estimateNeedMb(
        file: File,
        estimate: ModelEstimate?,
        mmproj: File? = null,
    ): Long {
        val modelBytes = estimate?.fileBytes ?: file.length()
        val kvBytes = estimate?.kvBytes ?: 0L
        val mmprojBytes = mmproj?.length() ?: 0L
        return (modelBytes + kvBytes + mmprojBytes) / MB + RESERVE_MB
    }

    /** KV bytes for [targetCtx], scaled from the estimate taken at another context size. */
    private fun estimateKvBytes(estimate: ModelEstimate?, targetCtx: Int): Long {
        if (estimate == null || estimate.kvBytes <= 0 || estimate.estimateCtx <= 0) return 0L
        return estimate.kvBytes * targetCtx.toLong() / estimate.estimateCtx.coerceAtLeast(1)
    }

    private fun suggestedContext(
        file: File,
        estimate: ModelEstimate?,
        availMb: Long,
        currentCtx: Int,
        currentKvType: Int,
    ): Int {
        val mmproj = mmprojFor(file)
        val fileMb = ((estimate?.fileBytes ?: file.length()) + (mmproj?.length() ?: 0L)) / MB + RESERVE_MB
        val currentKvMb = (estimate?.kvBytes ?: 0L) / MB
        if (currentKvMb <= 0L) return currentCtx.coerceAtMost(2048)
        // Suggested load uses q8_0 KV. q8_0 is about 34/32 bytes per element
        // versus 2 bytes for f16.
        val q8KvMb = if (currentKvType == 1) {
            currentKvMb
        } else {
            (currentKvMb * 0.53125).toLong().coerceAtLeast(1L)
        }
        val cap = estimate?.contextLength?.takeIf { it > 0 } ?: currentCtx
        val candidates = listOf(8192, 6144, 4096, 3072, 2048, 1536, 1024, 512)
        for (ctx in candidates) {
            if (ctx > cap) continue
            val kv = q8KvMb * ctx / currentCtx.coerceAtLeast(1)
            val need = fileMb + kv
            if (need <= availMb) return ctx
        }
        return minOf(512, cap)
    }

    fun loadModel(file: File, force: Boolean = false) {
        val snapshot = _state.value
        if (snapshot.generating || snapshot.loadingModel) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loadingModel = true,
                    status = "Оценка памяти для ${file.name}…",
                    error = null,
                    serviceDead = false,
                )
            }
            var settings = _state.value.settings
            try {
                val mmproj = mmprojFor(file)
                var estimate = parseEstimate(file, settings.contextSize, settings.kvType)
                val cap = estimate?.contextLength?.takeIf { it > 0 }
                    ?: maxOf(8192, settings.contextSize)
                // The metadata context is a property of the model, not a hard
                // limit for the setting: the user may pick a larger cache and
                // sees a warning when it exceeds what the model advertises.
                _state.update {
                    it.copy(
                        modelCap = cap,
                        estimate = estimate,
                        mmprojBytes = mmproj?.length() ?: 0L,
                    )
                }
                val needMb = estimateNeedMb(file, estimate, mmproj)
                val info = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(info)
                val availMb = info.availMem / MB
                Log.i(TAG, "load ${file.name}: avail=${availMb}MB need=${needMb}MB cap=$cap force=$force")
                _state.update { it.copy(lastNeedMb = needMb) }

                if (!force && availMb < needMb) {
                    val suggested = suggestedContext(
                        file,
                        estimate,
                        availMb,
                        settings.contextSize,
                        settings.kvType,
                    )
                    _state.update {
                        it.copy(
                            loadingModel = false,
                            status = null,
                            showFreeMemory = true,
                            memoryWarning = MemoryWarning(
                                file = file,
                                needMb = needMb,
                                availMb = availMb,
                                suggestedCtx = suggested,
                                suggestedKvType = 1,
                            ),
                        )
                    }
                    return@launch
                }
                doLoad(file, settings)
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t)
                _state.update {
                    it.copy(loadingModel = false, status = null, error = "Ошибка загрузки: ${t.message}")
                }
            }
        }
    }

    private suspend fun doLoad(file: File, settings: Settings) {
        val mmproj = mmprojFor(file)
        _state.update {
            it.copy(
                loadingModel = true,
                status = if (mmproj != null) {
                    "Загрузка ${file.name} и ${mmproj.name}…"
                } else {
                    "Загрузка ${file.name}…"
                },
                memoryWarning = null,
            )
        }
        val svc = requireService()
        if (svc == null) {
            _state.update {
                it.copy(loadingModel = false, status = null, error = "Процесс инференса не запустился")
            }
            return
        }
        val code = withContext(Dispatchers.IO) {
            runCatching {
                svc.load(
                    file.absolutePath,
                    settings.contextSize,
                    settings.threads,
                    settings.threadsBatch,
                    settings.useMmap,
                    settings.kvType,
                    settings.flashAttn,
                    settings.nBatch,
                    settings.nUbatch,
                    mmproj?.absolutePath,
                )
            }.getOrElse {
                Log.e(TAG, "service load failed", it)
                if (it is android.os.DeadObjectException) {
                    handleServiceLost("Процесс инференса аварийно завершился")
                }
                -100
            }
        }
        Log.i(TAG, "doLoad ${file.name}: service code=$code")
        if (code != 0) {
            _state.update {
                it.copy(
                    loadingModel = false,
                    status = null,
                    modelLoaded = false,
                    error = loadErrorMessage(code),
                )
            }
            return
        }
        val info = withContext(Dispatchers.IO) {
            runCatching { svc.modelInfo() }.getOrDefault("")
        }
        val parts = info.split('|')
        val desc = parts.getOrNull(0)?.ifBlank { file.name } ?: file.name
        val ctx = parts.getOrNull(2)?.toIntOrNull()
        val vision = parts.getOrNull(3) == "1"
        val audio = parts.getOrNull(4) == "1"
        val mmprojLoaded = parts.getOrNull(5) == "1"
        _state.update {
            it.copy(
                loadingModel = false,
                modelLoaded = true,
                modelName = file.name,
                mmprojName = if (mmprojLoaded) mmproj?.name else null,
                mmprojBytes = mmproj?.length() ?: 0L,
                supportsVision = vision,
                supportsAudio = audio,
                modelCap = ctx?.takeIf { c -> c > 0 } ?: it.modelCap,
                modelInfo = buildString {
                    append(desc)
                    if (ctx != null) append(" · ctx ").append(ctx)
                    parts.getOrNull(1)?.takeIf { p -> p.isNotBlank() }?.let { p ->
                        append(" · ").append(p)
                    }
                    if (vision || audio) {
                        append(" · ")
                        if (vision) append("vision")
                        if (vision && audio) append("+")
                        if (audio) append("audio")
                    }
                },
                status = null,
                serviceDead = false,
                lastNeedMb = 0,
            )
        }
        if (mmproj != null && !mmprojLoaded) {
            setStatus("mmproj не загрузился: доступен только текст", autoClearMs = 8000)
        } else if (vision || audio) {
            setStatus("Модель загружена (изображения/аудио доступны)")
        } else {
            setStatus("Модель загружена")
        }
        refreshMemory()
        Log.i(TAG, "load ${file.name}: success vision=$vision audio=$audio")
    }

    private fun loadErrorMessage(code: Int): String = when (code) {
        -1 -> "Не найден файл модели"
        -2 -> "Не удалось прочитать модель (нехватка памяти или неподдерживаемый формат)"
        -3 -> "Не удалось создать контекст (проверьте размер контекста и тип KV)"
        -4 -> "Недопустимые параметры загрузки"
        -10, -11 -> "Дождитесь окончания генерации"
        -100 -> "Процесс инференса недоступен"
        else -> "Ошибка загрузки (код $code)"
    }

    fun reloadModel() {
        val name = _state.value.modelName ?: return
        val file = File(modelsDir, name)
        if (file.exists()) loadModel(file)
    }

    fun unloadModel() {
        if (_state.value.generating) return
        viewModelScope.launch {
            val svc = service
            if (svc != null) {
                withContext(Dispatchers.IO) { runCatching { svc.unload() } }
            }
            _state.update {
                it.copy(
                    modelLoaded = false,
                    modelInfo = null,
                    progress = null,
                    mmprojName = null,
                    mmprojBytes = 0L,
                    supportsVision = false,
                    supportsAudio = false,
                )
            }
            refreshMemory()
        }
    }

    // ---------------------------------------------------------- memory dialog

    fun dismissMemoryWarning() {
        _state.update { it.copy(memoryWarning = null) }
        setStatus("Загрузка отменена")
    }

    fun loadAnyway() {
        val warning = _state.value.memoryWarning ?: return
        _state.update { it.copy(memoryWarning = null) }
        loadModel(warning.file, force = true)
    }

    fun applySuggestedAndLoad() {
        val warning = _state.value.memoryWarning ?: return
        updateSettings {
            it.copy(
                contextSize = warning.suggestedCtx,
                kvType = warning.suggestedKvType,
                nBatch = minOf(it.nBatch, 512),
                nUbatch = minOf(it.nUbatch, 512),
            )
        }
        _state.update { it.copy(memoryWarning = null) }
        loadModel(warning.file, force = true)
    }

    fun freeMemory() {
        if (_state.value.freeMemoryBusy) return
        viewModelScope.launch {
            _state.update { it.copy(freeMemoryBusy = true) }
            val before = currentAvailMb()
            val svc = service
            if (svc != null) {
                suppressServiceDeath = true
                service = null
                binding = false
                serviceDeferred = CompletableDeferred()
                _state.update {
                    it.copy(
                        modelLoaded = false,
                        modelInfo = null,
                        generating = false,
                        progress = null,
                        mmprojName = null,
                        mmprojBytes = 0L,
                        supportsVision = false,
                        supportsAudio = false,
                    )
                }
                withContext(Dispatchers.IO) {
                    runCatching { svc.killProcess() }
                }
            } else {
                runCatching {
                    appContext.stopService(Intent(appContext, LlmService::class.java))
                }
                _state.update {
                    it.copy(
                        modelLoaded = false,
                        modelInfo = null,
                        mmprojName = null,
                        mmprojBytes = 0L,
                        supportsVision = false,
                        supportsAudio = false,
                    )
                }
            }
            killBackgroundPackages()
            delay(1_500)
            val after = currentAvailMb()
            Log.i(TAG, "freeMemory: avail before=${before}MB after=${after}MB")
            refreshMemory()
            _state.update { it.copy(freeMemoryBusy = false) }
            setStatus("Свободно ${formatRam(after)}")

            val warning = _state.value.memoryWarning
            if (warning != null) {
                if (after >= warning.needMb) {
                    loadModel(warning.file, force = true)
                } else {
                    val estimate = parseEstimate(warning.file, _state.value.settings.contextSize, 1)
                    val suggested = suggestedContext(
                        warning.file,
                        estimate,
                        after,
                        _state.value.settings.contextSize,
                        1,
                    )
                    _state.update {
                        it.copy(
                            memoryWarning = warning.copy(
                                availMb = after,
                                needMb = estimateNeedMb(warning.file, estimate, mmprojFor(warning.file)),
                                suggestedCtx = suggested,
                                suggestedKvType = 1,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun formatRam(mb: Long): String = if (mb >= 1024) {
        String.format(Locale.ROOT, "%.1f ГБ", mb / 1024.0)
    } else {
        "$mb МБ"
    }

    private fun currentAvailMb(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem / MB
    }

    private fun killBackgroundPackages() {
        for (pkg in HEAVY_PACKAGES) {
            runCatching { activityManager.killBackgroundProcesses(pkg) }
        }
    }

    // --------------------------------------------------------------- attachments

    fun attachMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = ArrayList<Attachment>()
            val errors = ArrayList<String>()
            for (uri in uris) {
                val result = withContext(Dispatchers.IO) { runCatching { copyAttachment(uri) } }
                result.onSuccess { added += it }
                result.onFailure { errors += (it.message ?: "не удалось прочитать файл") }
            }
            if (added.isNotEmpty()) {
                _state.update { it.copy(pendingAttachments = it.pendingAttachments + added) }
                setStatus("Добавлено вложений: ${added.size}")
            }
            if (errors.isNotEmpty()) {
                _state.update { it.copy(error = errors.first()) }
            }
        }
    }

    private fun copyAttachment(uri: Uri): Attachment {
        val resolver = appContext.contentResolver
        var name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L }
            ?: -1L
        require(size < 0 || size <= MAX_ATTACHMENT_BYTES) {
            "Файл больше ${MAX_ATTACHMENT_BYTES / MB} МБ"
        }
        val mime = resolver.getType(uri).orEmpty().ifBlank {
            when (name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
                "jpg", "jpeg", "png", "webp", "bmp", "gif" -> "image/*"
                "wav", "mp3", "flac", "ogg", "m4a", "aac" -> "audio/*"
                else -> "application/octet-stream"
            }
        }
        val kind = when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("audio/") -> "audio"
            else -> throw IllegalArgumentException("Поддерживаются только изображения и аудио")
        }
        if (name.isNullOrBlank()) {
            name = when (kind) {
                "image" -> "image-${System.currentTimeMillis()}.jpg"
                else -> "audio-${System.currentTimeMillis()}.wav"
            }
        }
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(attachmentsDir, "${System.currentTimeMillis()}-${Random.nextInt(1000)}-$name")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть вложение" }
            target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        }
        require(target.length() <= MAX_ATTACHMENT_BYTES) {
            target.delete()
            "Файл больше ${MAX_ATTACHMENT_BYTES / MB} МБ"
        }
        return Attachment(kind = kind, path = target.absolutePath, name = name, mime = mime)
    }

    fun removePendingAttachment(attachment: Attachment) {
        _state.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.path == attachment.path })
        }
    }

    fun clearPendingAttachments() {
        _state.update { it.copy(pendingAttachments = emptyList()) }
    }

    // ------------------------------------------------------------------ chat

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = _state.value.settings
        var next = transform(before)
        if (next.nUbatch > next.nBatch) {
            next = next.copy(nUbatch = next.nBatch)
        }
        _state.update { it.copy(settings = next) }
        if (next.contextSize != before.contextSize || next.kvType != before.kvType) {
            scheduleEstimateRefresh()
        }
    }

    private fun scheduleEstimateRefresh() {
        val state = _state.value
        if (state.generating || state.loadingModel) return
        val name = state.modelName ?: return
        val file = File(modelsDir, name)
        if (!file.exists()) return
        estimateJob?.cancel()
        estimateJob = viewModelScope.launch {
            delay(350)
            val settings = _state.value.settings
            val estimate = parseEstimate(file, settings.contextSize, settings.kvType) ?: return@launch
            val cap = estimate.contextLength.takeIf { it > 0 } ?: _state.value.modelCap
            _state.update {
                it.copy(
                    estimate = estimate,
                    modelCap = cap,
                    lastNeedMb = estimateNeedMb(file, estimate, mmprojFor(file)),
                )
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun clearStatus() = _state.update { it.copy(status = null) }

    fun stop() {
        cancelRequested = true
        val svc = service ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { svc.stop() }
        }
    }

    fun send() {
        val snapshot = _state.value
        if (snapshot.generating || !snapshot.modelLoaded) return
        val text = snapshot.input.trim()
        val attachments = snapshot.pendingAttachments
        if (text.isEmpty() && attachments.isEmpty()) return
        if (attachments.any { it.kind == "image" } && !snapshot.supportsVision) {
            _state.update { it.copy(error = "Модель не умеет читать изображения: нужен multimodal mmproj") }
            return
        }
        if (attachments.any { it.kind == "audio" } && !snapshot.supportsAudio) {
            _state.update { it.copy(error = "Модель не умеет слушать аудио: нужен multimodal mmproj") }
            return
        }

        val userMsg = ChatMessage(role = "user", text = text, attachments = attachments)
        updateMessages { it + userMsg }
        _state.update {
            it.copy(
                input = "",
                pendingAttachments = emptyList(),
                generating = true,
                error = null,
                status = null,
                scrollRequest = it.scrollRequest + 1,
            )
        }
        scheduleSave()

        viewModelScope.launch {
            Log.i(TAG, "send: start")
            cancelRequested = false
            val settings = _state.value.settings
            val toolsAvailable = settings.toolsEnabled && toolRegistry.enabledTools(settings).isNotEmpty()
            val toolsPrompt = if (toolsAvailable) toolRegistry.prompt(settings) else ""
            var completed = false
            var rounds = 0
            var lastWasTool = false

            while (rounds < 6) {
                if (cancelRequested) break
                rounds++
                val history = _state.value.messages.filter { message ->
                    !(message.role == "assistant" && message.isError)
                }
                val assistant = ChatMessage(role = "assistant", text = "", streaming = true)
                updateMessages { it + assistant }
                live.begin(assistant.id)

                val result = generateOnce(settings, history, assistant, toolsPrompt)
                if (result.cancelled) break
                if (result.error != null) break

                if (!toolsAvailable) {
                    completed = true
                    break
                }

                when (val parsed = ToolCallParser.parse(result.text)) {
                    ParsedToolCall.None -> {
                        completed = true
                        break
                    }

                    is ParsedToolCall.Failure -> {
                        lastWasTool = true
                        Log.w(TAG, "tool parse failure: ${parsed.message}; raw=${result.text.take(2000)}")
                        val failureText = parsed.message
                        val toolMessage = ChatMessage(
                            role = "tool",
                            text = failureText,
                            isError = true,
                            toolName = "format",
                            toolArgs = "",
                            toolResult = failureText,
                        )
                        updateMessages { it + toolMessage }
                        scheduleSave()
                    }

                    is ParsedToolCall.Success -> {
                        lastWasTool = true
                        val call = parsed.call
                        Log.i(TAG, "tool call: ${call.name} ${call.arguments}")
                        val visible = ToolCallParser.strip(result.text)
                        val toolMessage = ChatMessage(
                            role = "tool",
                            text = "Выполняется ${call.name}…",
                            toolName = call.name,
                            toolArgs = call.arguments,
                            toolResult = "",
                        )
                        val cleaned = _state.value.messages.mapNotNull { message ->
                            when {
                                message.id != assistant.id -> message
                                visible.isNotBlank() -> message.copy(text = visible, streaming = false)
                                else -> null
                            }
                        }
                        updateMessages { cleaned + toolMessage }
                        _state.update { it.copy(status = "Инструмент: ${call.name}…") }
                        val execution = toolRegistry.execute(call, settings)
                        updateMessages { messages ->
                            messages.map { message ->
                                if (message.id == toolMessage.id) {
                                    message.copy(
                                        text = execution.text,
                                        toolResult = execution.text,
                                        isError = execution.isError,
                                    )
                                } else {
                                    message
                                }
                            }
                        }
                        _state.update { it.copy(status = null) }
                        scheduleSave()
                    }
                }
            }

            if (rounds >= 6 && lastWasTool && !completed && !cancelRequested) {
                setStatus("Достигнут лимит 6 шагов с инструментами", autoClearMs = 6000)
            }
            _state.update { it.copy(generating = false, progress = null) }
            scheduleSave()
        }
    }

    private data class GenerationResult(
        val text: String,
        val generated: Int,
        val cancelled: Boolean,
        val error: String?,
    )

    private suspend fun generateOnce(
        settings: Settings,
        history: List<ChatMessage>,
        assistant: ChatMessage,
        extraSystem: String,
    ): GenerationResult {
        val svc = requireService()
        if (svc == null) {
            val message = "Процесс инференса недоступен"
            finishAssistant(assistant.id, "", -1, message, false, 0f, 0, 0f)
            return GenerationResult("", -1, false, message)
        }

        val seq = ++generationSeq
        val buffer = StringBuilder()
        var promptSpeed = 0f
        var promptTokens = 0
        var genSpeed = 0f
        val startedAt = System.nanoTime()
        val finalized = AtomicBoolean(false)
        var lastPromptUiAt = 0L
        var lastSpeedUiAt = 0L
        var generationStarted = false

        fun finish(generated: Int, error: String?) {
            if (!finalized.compareAndSet(false, true)) return
            val wasCancelled = cancelRequested || generated == -2
            finishAssistant(
                assistant.id,
                buffer.toString(),
                generated,
                error,
                wasCancelled,
                promptSpeed,
                promptTokens,
                genSpeed,
                startedAt,
            )
            if (!wasCancelled && error != null) {
                _state.update { it.copy(error = error) }
            }
        }

        val callback = object : ILlmCallback.Stub() {
            override fun onToken(chunk: ByteArray) {
                if (seq != generationSeq) return
                val text = String(chunk, Charsets.UTF_8)
                if (text.isEmpty()) return
                // Only the live answer is updated per token; the message text is
                // committed once when the answer is finished.
                if (live.messageId != assistant.id) return
                buffer.append(text)
                queueLiveToken(assistant.id, text)
            }

            override fun onPromptProgress(processed: Int, total: Int, tokensPerSecond: Float) {
                if (seq != generationSeq) return
                promptSpeed = tokensPerSecond
                promptTokens = total
                val now = SystemClock.uptimeMillis()
                if (now - lastPromptUiAt >= PROMPT_UI_MS || (total > 0 && processed >= total)) {
                    lastPromptUiAt = now
                    _state.update {
                        it.copy(
                            progress = GenerationProgress(
                                phase = GenerationPhase.PROMPT,
                                processed = processed,
                                total = total,
                                tokensPerSecond = tokensPerSecond,
                            ),
                        )
                    }
                }
                // Under the answer: processing speed while the prompt is read,
                // replaced by generation speed once tokens start arriving.
                if (now - lastSpeedUiAt >= SPEED_UI_MS) {
                    lastSpeedUiAt = now
                    live.setSpeed(assistant.id, tokensPerSecond)
                }
            }

            override fun onGenerationProgress(generated: Int, tokensPerSecond: Float) {
                if (seq != generationSeq) return
                genSpeed = tokensPerSecond
                if (!generationStarted) {
                    // The prompt bar is done; generation itself needs no progress
                    // state, only the live tok/s number.
                    generationStarted = true
                    _state.update { it.copy(progress = null) }
                }
                val now = SystemClock.uptimeMillis()
                if (now - lastSpeedUiAt >= SPEED_UI_MS) {
                    lastSpeedUiAt = now
                    live.setSpeed(assistant.id, tokensPerSecond)
                }
            }

            override fun onComplete(generated: Int, error: String?) {
                if (seq != generationSeq) return
                finish(generated, error ?: generationErrorMessage(generated))
            }
        }

        try {
            Log.i(TAG, "generateOnce: building prompt (${history.size} messages)")
            val enableThinking = settings.thinkingBudget > 0
            val bundle = buildPrompt(
                svc,
                settings.systemPrompt,
                history,
                extraSystem,
                enableThinking,
                settings.thinkingBudget,
            )
            val maxNew = settings.maxTokens + if (enableThinking) settings.thinkingBudget else 0
            val baseCache = withContext(Dispatchers.IO) {
                baseCacheConfig(bundle.baseBytes, settings)
            }
            Log.i(
                TAG,
                "generateOnce: prompt ${bundle.bytes.size} bytes, base=${baseCache.prompt.size} bytes, " +
                    "media=${bundle.mediaPaths.size}, maxNew=$maxNew",
            )
            val generated = withContext(Dispatchers.IO) {
                svc.generate(
                    bundle.bytes,
                    maxNew,
                    settings.temperature,
                    settings.topP,
                    settings.topK,
                    Random.nextInt(),
                    bundle.mediaPaths.toTypedArray(),
                    baseCache.prompt,
                    baseCache.fingerprint,
                    baseCache.path,
                    callback,
                )
            }
            Log.i(TAG, "generateOnce: generate returned $generated, buffer=${buffer.length}")
            val error = generationErrorMessage(generated)
            finish(generated, error)
            val wasCancelled = cancelRequested || generated == -2
            return GenerationResult(
                text = buffer.toString(),
                generated = generated,
                cancelled = wasCancelled,
                error = if (wasCancelled) null else error,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "generate failed", t)
            if (t is android.os.DeadObjectException) {
                handleServiceLost("Процесс инференса аварийно завершился")
            }
            val message = t.message ?: t.toString()
            finish(-1, message)
            return GenerationResult("", -1, false, message)
        }
    }

    private fun finishAssistant(
        assistantId: Long,
        fullText: String,
        generated: Int,
        error: String?,
        wasCancelled: Boolean,
        promptSpeed: Float,
        promptTokens: Int,
        genSpeed: Float,
        startedAt: Long = System.nanoTime(),
    ) {
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
        val finalSpeed = when {
            genSpeed > 0f -> genSpeed
            generated > 0 -> (generated / seconds.coerceAtLeast(0.001)).toFloat()
            else -> 0f
        }
        val metrics = if (generated > 0 || promptTokens > 0 || promptSpeed > 0f) {
            MessageMetrics(
                promptTokens = promptTokens.coerceAtLeast(0),
                promptTokensPerSecond = promptSpeed.coerceAtLeast(0f),
                generatedTokens = generated.coerceAtLeast(0),
                generationTokensPerSecond = finalSpeed.coerceAtLeast(0f),
                durationMs = durationMs,
            )
        } else {
            null
        }
        _state.update { it.copy(progress = null) }
        updateMessages { messages ->
            messages.map { message ->
                if (message.id != assistantId) {
                    message
                } else {
                    val text = if (fullText.isNotBlank()) fullText else message.text
                    message.copy(
                        streaming = false,
                        stats = null,
                        metrics = metrics ?: message.metrics,
                        isError = !wasCancelled && error != null,
                        text = when {
                            text.isNotBlank() -> text
                            error != null && !wasCancelled -> error
                            wasCancelled -> "(остановлено)"
                            else -> "(пустой ответ)"
                        },
                    )
                }
            }
        }
        // The final text is in the message now; the live overlay can go away.
        live.end(assistantId)
        scheduleSave()
    }

    private fun generationErrorMessage(code: Int): String? = when {
        code >= 0 -> null
        code == -2 -> null
        code == -1 -> "Не удалось подготовить промпт"
        code == -3 -> "Ошибка обработки промпта"
        code == -4 -> "Ошибка декодирования токена"
        code == -6 -> "Генерация уже выполняется"
        code == -7 -> "Вложение не поддерживается: не загружен multimodal mmproj"
        code == -8 -> "Не удалось прочитать вложение (поддерживаются изображения и wav/mp3/flac)"
        code == -9 -> "Промпт с вложением не помещается в контекст"
        else -> "Ошибка генерации (код $code)"
    }

    private data class PromptBundle(
        val bytes: ByteArray,
        /** System-only rendering: prefix of [bytes] used for the base cache. */
        val baseBytes: ByteArray,
        val mediaPaths: List<String>,
    )

    private fun messageContent(message: ChatMessage, mediaPaths: MutableList<String>): String {
        val sb = StringBuilder()
        if (message.text.isNotBlank()) sb.append(message.text)
        for (attachment in message.attachments) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(MEDIA_MARKER)
            mediaPaths += attachment.path
        }
        return sb.toString()
    }

    /**
     * Reasoning traces are never sent back to the model. Only the visible
     * answer is part of the conversation context; otherwise the traces would
     * occupy the context window and stay in the KV cache for every next turn.
     */
    private fun assistantPromptText(message: ChatMessage): String =
        if (hasThinkingTags(message.text)) {
            // Thinking is never fed back into the context.
            splitThinking(message.text).answer.trim()
        } else {
            // Preserve the generated text byte-for-byte so it re-tokenizes to
            // the same ids as the native post-generation snapshot.
            message.text
        }

    private data class BaseCacheConfig(
        val prompt: ByteArray,
        val fingerprint: ByteArray,
        val path: String,
    )

    private fun baseCacheFile(model: File): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${model.name}:${model.length()}".toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }.take(24)
        return File(promptCacheDir, "base-$key.bin")
    }

    /** Keeps at most [BASE_CACHE_KEEP_FILES] base-cache files, newest first. */
    private fun pruneBaseCaches(keep: File) {
        promptCacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("base-") && file.name.endsWith(".bin.tmp") &&
                System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000L
        }?.forEach { runCatching { it.delete() } }
        val files = promptCacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("base-") && file.name.endsWith(".bin")
        } ?: return
        files.asSequence()
            .filter { it != keep }
            .sortedByDescending { it.lastModified() }
            .drop(BASE_CACHE_KEEP_FILES - 1)
            .forEach { runCatching { it.delete() } }
    }

    /**
     * Computes the persistent base-cache key. The native side also checks the
     * effective KV layout (requested q8_0 may fall back to f16), so a cache
     * recorded under one runtime configuration is never restored into another.
     */
    private fun baseCacheConfig(basePrompt: ByteArray, settings: Settings): BaseCacheConfig {
        if (basePrompt.isEmpty()) return BaseCacheConfig(ByteArray(0), ByteArray(0), "")
        val model = _state.value.modelName?.let { File(modelsDir, it) }?.takeIf { it.isFile }
            ?: return BaseCacheConfig(ByteArray(0), ByteArray(0), "")
        val cacheFile = baseCacheFile(model)
        pruneBaseCaches(cacheFile)

        val mmproj = mmprojFor(model)
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        field(BASE_CACHE_FORMAT)
        field(LLAMA_BUILD)
        field(model.name)
        field(model.length().toString())
        field(model.lastModified().toString())
        field(mmproj?.name ?: "-")
        field(mmproj?.length()?.toString() ?: "-")
        field(mmproj?.lastModified()?.toString() ?: "-")
        field(settings.contextSize.toString())
        field(settings.kvType.toString())
        field(settings.flashAttn.toString())
        field(settings.thinkingBudget.toString())
        field(settings.toolsEnabled.toString())
        field(settings.enabledTools.sorted().joinToString(","))
        digest.update(basePrompt)
        return BaseCacheConfig(basePrompt, digest.digest(), cacheFile.absolutePath)
    }

    private suspend fun buildPrompt(
        service: ILlmService,
        systemPrompt: String,
        history: List<ChatMessage>,
        extraSystem: String,
        enableThinking: Boolean,
        thinkingBudget: Int,
    ): PromptBundle {
        val system = buildString {
            if (systemPrompt.isNotBlank()) append(systemPrompt)
            if (extraSystem.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(extraSystem)
            }
            if (enableThinking && thinkingBudget > 0) {
                if (isNotEmpty()) append("\n\n")
                append("Think through the problem before answering. Keep all private reasoning inside ")
                append(THINK_OPEN)
                append("...")
                append(THINK_CLOSE)
                append(". Spend no more than ")
                append(thinkingBudget)
                append(" tokens on reasoning, then write the final answer after the closing tag.")
            }
        }
        val roles = ArrayList<String>()
        val contents = ArrayList<String>()
        val mediaPaths = ArrayList<String>()
        if (system.isNotBlank()) {
            roles += "system"
            contents += system
        }
        for (m in history) {
            when (m.role) {
                "tool" -> {
                    roles += "tool"
                    val prefix = if (m.isError) "Tool error" else "Tool result"
                    contents += "$prefix (${m.toolName}):\n${m.toolResult ?: m.text}"
                }
                "user" -> {
                    val content = messageContent(m, mediaPaths)
                    if (content.isNotBlank()) {
                        roles += "user"
                        contents += content
                    }
                }
                "assistant" -> {
                    if (!m.isError && m.text.isNotBlank()) {
                        val content = assistantPromptText(m)
                        if (content.isNotBlank()) {
                            roles += "assistant"
                            contents += content
                        }
                    }
                }
            }
        }

        val (templated, baseTemplated) = withContext(Dispatchers.IO) {
            val full = runCatching {
                service.applyTemplate(
                    roles.toTypedArray(),
                    contents.toTypedArray(),
                    true,
                    enableThinking,
                )
            }.getOrDefault(ByteArray(0))
            // The base cache is only useful when the system block is rendered
            // through the same template as the conversation. If the template
            // cannot render the system message alone, leave the cache disabled.
            val base = if (full.isNotEmpty() && system.isNotBlank()) {
                runCatching {
                    service.applyTemplate(
                        arrayOf("system"),
                        arrayOf(system),
                        false,
                        enableThinking,
                    )
                }.getOrDefault(ByteArray(0))
            } else {
                ByteArray(0)
            }
            full to base
        }
        if (templated.isNotEmpty()) return PromptBundle(templated, baseTemplated, mediaPaths)

        val sb = StringBuilder()
        if (system.isNotBlank()) sb.append("System: ").append(system).append("\n\n")
        for (m in history) {
            when (m.role) {
                "tool" -> sb.append("Tool (").append(m.toolName).append("):\n")
                    .append(m.toolResult ?: m.text).append("\n\n")
                "user" -> {
                    val content = messageContent(m, ArrayList())
                    if (content.isNotBlank()) sb.append("User: ").append(content).append("\n\n")
                }
                "assistant" -> if (!m.isError && m.text.isNotBlank()) {
                    val content = assistantPromptText(m)
                    if (content.isNotBlank()) {
                        sb.append("Assistant: ").append(content).append("\n\n")
                    }
                }
            }
        }
        sb.append("Assistant:")
        return PromptBundle(sb.toString().toByteArray(Charsets.UTF_8), ByteArray(0), mediaPaths)
    }

    override fun onCleared() {
        super.onCleared()
        saveJob?.cancel()
        estimateJob?.cancel()
        runCatching { saveNow() }
        runCatching { appContext.unbindService(connection) }
        runCatching { appContext.stopService(Intent(appContext, LlmService::class.java)) }
        service = null
    }
}

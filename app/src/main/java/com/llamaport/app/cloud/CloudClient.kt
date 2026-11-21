package com.llamaport.app.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Provider refused the request; [message] is user-facing Russian text. */
class CloudException(
    message: String,
    val httpCode: Int = 0,
    val body: String = "",
) : Exception(message)

/** Generation was stopped by the user; the already streamed text is kept. */
class CloudCancelled : Exception("cancelled")

data class CloudModelsResult(
    /** Base URL after the `/v1` fallback; may differ from what the user typed. */
    val baseUrl: String,
    val models: List<String>,
)

data class CloudChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: JSONArray,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val maxTokens: Int,
    val compat: CloudCompat,
)

data class CloudChatResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    /** Profile after negotiation: differs from the request when a parameter was dropped. */
    val compat: CloudCompat,
    val downgraded: Boolean,
)

data class CloudProbeResult(
    val reachable: Boolean,
    val rows: List<CloudProbeRow>,
    val compat: CloudCompat,
)

/** Raw transport response; [stream] is set only for successful streaming posts. */
data class CloudHttp(
    val code: Int,
    val body: String = "",
    val stream: InputStream? = null,
)

/**
 * Injected so the whole negotiation and streaming logic can be exercised by JVM
 * tests without a device, a key or the network.
 */
interface CloudTransport {
    fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): CloudHttp

    fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): CloudHttp

    /** Breaks the connection in flight, so Stop is instant instead of blocking. */
    fun abort()
}

/** Real HTTP transport: `HttpURLConnection`, no extra dependency in the APK. */
class HttpUrlTransport : CloudTransport {

    @Volatile
    private var active: HttpURLConnection? = null

    override fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): CloudHttp = execute("GET", url, headers, null, connectTimeoutMs, readTimeoutMs, streaming = false)

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): CloudHttp = execute("POST", url, headers, body, connectTimeoutMs, readTimeoutMs, streaming = true)

    override fun abort() {
        runCatching { active?.disconnect() }
    }

    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        streaming: Boolean,
    ): CloudHttp {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            useCaches = false
            for ((name, value) in headers) setRequestProperty(name, value)
        }
        active = connection
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code in 200..299) {
                val input = connection.inputStream
                if (streaming) return CloudHttp(code, stream = input)
                val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
                connection.disconnect()
                active = null
                return CloudHttp(code, text)
            }
            val error = (connection.errorStream ?: connection.inputStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            connection.disconnect()
            active = null
            return CloudHttp(code, error)
        } catch (t: IOException) {
            connection.disconnect()
            active = null
            throw t
        }
    }
}

/**
 * OpenAI-compatible client.
 *
 * Capability negotiation lives here: a request is sent with the provider
 * profile, a 400/422 that names one of our optional fields drops exactly that
 * field and retries (at most [MAX_ATTEMPTS] times), and the resulting profile is
 * returned so the caller can persist it.
 */
class CloudClient(
    private val transport: CloudTransport = HttpUrlTransport(),
    private val logger: (String) -> Unit = {},
) {

    companion object {
        private const val THINK_OPEN = "<think>"
        private const val THINK_CLOSE = "</think>"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MODELS_READ_TIMEOUT_MS = 30_000
        private const val MAX_ATTEMPTS = 3
    }

    fun abort() = transport.abort()

    suspend fun listModels(baseUrl: String, apiKey: String): CloudModelsResult =
        withContext(Dispatchers.IO) {
            var base = CloudWire.normalizeBaseUrl(baseUrl)
            val headers = headers(apiKey, sse = false)
            var response = transport.get(
                CloudWire.modelsEndpoint(base),
                headers,
                CONNECT_TIMEOUT_MS,
                MODELS_READ_TIMEOUT_MS,
            )
            val fallback = CloudWire.withV1(base)
            if (response.code !in 200..299 && fallback != null) {
                val retry = transport.get(
                    CloudWire.modelsEndpoint(fallback),
                    headers,
                    CONNECT_TIMEOUT_MS,
                    MODELS_READ_TIMEOUT_MS,
                )
                if (retry.code in 200..299) {
                    base = fallback
                    response = retry
                } else if (retry.code != 404) {
                    // The /v1 address exists but rejects us: report that error,
                    // it is more precise than "not found" on the bare host.
                    response = retry
                }
            }
            if (response.code !in 200..299) {
                throw CloudException(
                    CloudWire.errorMessage(response.code, response.body),
                    response.code,
                    response.body,
                )
            }
            val models = CloudWire.parseModelIds(response.body)
            if (models.isEmpty()) {
                throw CloudException(
                    "Провайдер не вернул список моделей. Ответ: " + CloudWire.sanitize(response.body),
                    response.code,
                    response.body,
                )
            }
            logger("models $base → ${models.size}")
            CloudModelsResult(base, models)
        }

    suspend fun chat(
        request: CloudChatRequest,
        onDelta: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): CloudChatResult = withContext(Dispatchers.IO) {
        val base = CloudWire.normalizeBaseUrl(request.baseUrl)
        val endpoint = CloudWire.chatEndpoint(base)
        var compat = request.compat
        var downgraded = false

        for (attempt in 0 until MAX_ATTEMPTS) {
            if (isCancelled()) throw CloudCancelled()
            val body = CloudWire.buildChatBody(
                model = request.model,
                messages = request.messages,
                temperature = request.temperature,
                topP = request.topP,
                topK = request.topK,
                maxTokens = request.maxTokens,
                compat = compat,
            )
            logger(
                "chat ${request.model} attempt=${attempt + 1} stream=${compat.stream} " +
                    "topK=${compat.topK} max=${compat.maxTokensField.wire}",
            )
            val response = try {
                transport.post(
                    endpoint,
                    headers(request.apiKey, sse = compat.stream) +
                        ("Content-Type" to "application/json; charset=utf-8"),
                    body.toString(),
                    CONNECT_TIMEOUT_MS,
                    READ_TIMEOUT_MS,
                )
            } catch (t: IOException) {
                if (isCancelled()) throw CloudCancelled()
                throw CloudException("Нет связи с провайдером: ${t.message ?: "ошибка сети"}", 0, "")
            }
            if (response.code in 200..299) {
                val accumulator = Accumulator(onDelta)
                try {
                    consume(response, compat, accumulator, isCancelled)
                } catch (t: IOException) {
                    // abort() disconnects the socket, so a stopped generation
                    // surfaces here as an IOException.
                    if (isCancelled()) throw CloudCancelled()
                    throw CloudException(
                        "Соединение прервалось: ${t.message ?: "ошибка сети"}",
                        0,
                        "",
                    )
                }
                return@withContext CloudChatResult(
                    text = accumulator.finish(),
                    promptTokens = accumulator.promptTokens,
                    completionTokens = accumulator.tokenCount,
                    compat = compat,
                    downgraded = downgraded,
                )
            }

            val errorBody = response.body
            val sent = CloudWire.optionalParams(body)
            val rejected = CloudWire.unsupportedParams(errorBody, sent)
            if (rejected.isNotEmpty() && attempt < MAX_ATTEMPTS - 1) {
                val next = CloudWire.downgrade(compat, rejected)
                if (next != compat) {
                    logger("downgrade $rejected")
                    compat = next
                    downgraded = true
                    continue
                }
            }
            if (compat.stream && attempt < MAX_ATTEMPTS - 1 &&
                CloudWire.looksLikeStreamProblem(errorBody) &&
                rejected.isEmpty()
            ) {
                logger("stream rejected, retrying without it")
                compat = compat.copy(stream = false)
                downgraded = true
                continue
            }
            throw CloudException(
                CloudWire.errorMessage(response.code, errorBody),
                response.code,
                errorBody,
            )
        }
        throw CloudException("Не удалось подобрать параметры запроса", 0, "")
    }

    /**
     * Explicit "check API" action: one non-streamed and one streamed request of
     * one token each, reported as rows and returned as a profile to persist.
     */
    suspend fun probe(
        baseUrl: String,
        apiKey: String,
        model: String,
        compat: CloudCompat,
    ): CloudProbeResult {
        val rows = ArrayList<CloudProbeRow>()
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "ping")
            })
        }
        val initial = CloudCompat(
            topK = true,
            temperature = true,
            topP = true,
            maxTokensField = MaxTokensField.Auto,
            stream = false,
            checkedAt = compat.checkedAt,
        )
        var checked = initial

        fun request(profile: CloudCompat) = CloudChatRequest(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            messages = messages,
            temperature = 0f,
            topP = 0.9f,
            topK = 1,
            maxTokens = 1,
            compat = profile,
        )

        val plain = runCatching { chat(request(initial)) }
        if (plain.isSuccess) {
            checked = plain.getOrThrow().compat
            rows += CloudProbeRow("Модель и сообщения", true, "ответ получен")
            rows += CloudProbeRow(
                "temperature",
                checked.temperature,
                if (checked.temperature) "отправляется" else "не отправляется",
            )
            rows += CloudProbeRow(
                "top_p",
                checked.topP,
                if (checked.topP) "отправляется" else "не отправляется",
            )
            rows += CloudProbeRow(
                "top_k",
                checked.topK,
                if (checked.topK) "отправляется" else "не поддерживается",
            )
            rows += CloudProbeRow(
                "max_tokens",
                checked.maxTokensField != MaxTokensField.None,
                when (checked.maxTokensField) {
                    MaxTokensField.Auto, MaxTokensField.MaxTokens -> "отправляется как max_tokens"
                    MaxTokensField.MaxCompletionTokens -> "используется max_completion_tokens"
                    MaxTokensField.None -> "не отправляется"
                },
            )
        }

        val streamRequest = request(checked.copy(stream = true))
        val streamed = runCatching { chat(streamRequest) }
        if (streamed.isSuccess) {
            checked = streamed.getOrThrow().compat
            rows += CloudProbeRow("Потоковый ответ", true, "stream работает")
            if (plain.isFailure) {
                rows += CloudProbeRow(
                    "Модель и сообщения",
                    true,
                    "ответ получен только в потоковом режиме",
                )
                rows += CloudProbeRow(
                    "Остальные параметры",
                    false,
                    "не проверены: без потока API отвечает ошибкой",
                )
            }
        } else if (plain.isSuccess) {
            checked = checked.copy(stream = false)
            rows += CloudProbeRow("Потоковый ответ", false, "stream не работает — ответы без потока")
        } else {
            val failure = plain.exceptionOrNull() ?: streamed.exceptionOrNull()
            rows += CloudProbeRow(
                "Модель и сообщения",
                false,
                (failure as? CloudException)?.message ?: failure?.message ?: "ошибка",
            )
        }

        return CloudProbeResult(
            reachable = rows.any { it.ok && it.label == "Модель и сообщения" },
            rows = rows,
            compat = checked.copy(checkedAt = System.currentTimeMillis()),
        )
    }

    // ------------------------------------------------------------------ inner

    private fun headers(apiKey: String, sse: Boolean): Map<String, String> = buildMap {
        put("User-Agent", "LlamaPort/1.0 (Android)")
        put("Accept", if (sse) "text/event-stream" else "application/json")
        if (apiKey.isNotBlank()) put("Authorization", "Bearer ${apiKey.trim()}")
    }

    private fun consume(
        response: CloudHttp,
        compat: CloudCompat,
        accumulator: Accumulator,
        isCancelled: () -> Boolean,
    ) {
        val stream = response.stream
        if (stream == null) {
            consumeJson(response.body, accumulator)
            return
        }
        try {
            if (!compat.stream) {
                val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
                consumeJson(text, accumulator)
                return
            }
            readSse(stream, accumulator, isCancelled)
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun readSse(
        stream: InputStream,
        accumulator: Accumulator,
        isCancelled: () -> Boolean,
    ) {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val sse = SseReader(reader)
        var sawEvent = false
        while (true) {
            if (isCancelled()) throw CloudCancelled()
            val event = sse.next() ?: break
            sawEvent = true
            event.failure?.let { message ->
                throw CloudException("Провайдер вернул ошибку: $message")
            }
            if (event.done) break
            event.reasoning?.let { accumulator.reasoning(it) }
            event.content?.let { accumulator.content(it) }
            if (event.promptTokens > 0) accumulator.promptTokens = event.promptTokens
            if (event.completionTokens > 0) accumulator.completionTokens = event.completionTokens
        }
        if (!sawEvent) {
            // The server ignored `stream` and answered with one JSON document.
            val raw = sse.rawText()
            if (raw.isNotBlank()) consumeJson(raw, accumulator)
        }
    }

    private fun consumeJson(body: String, accumulator: Accumulator) {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw CloudException("Некорректный ответ провайдера: " + CloudWire.sanitize(body))
        json.optJSONObject("error")?.let { error ->
            throw CloudException(
                "Провайдер вернул ошибку: " +
                    error.optString("message").ifBlank { CloudWire.sanitize(body) },
                200,
                body,
            )
        }
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val reasoning = CloudWire.contentOf(message, "reasoning_content")
            ?: CloudWire.contentOf(message, "reasoning")
        reasoning?.let { accumulator.reasoning(it) }
        CloudWire.contentOf(message, "content")?.let { accumulator.content(it) }
        json.optJSONObject("usage")?.let { usage ->
            accumulator.promptTokens = usage.optInt("prompt_tokens", 0)
            accumulator.completionTokens = usage.optInt("completion_tokens", 0)
        }
    }

    /**
     * Builds the visible answer. Reasoning deltas are wrapped in think tags so
     * the existing thinking splitter shows them, and they are never re-sent as
     * context (the ViewModel strips them like it does for local models).
     */
    private class Accumulator(private val onDelta: (String) -> Unit) {
        private val text = StringBuilder()
        private var reasoningOpen = false
        private var contentDeltas = 0
        var promptTokens = 0
        var completionTokens = 0

        val tokenCount: Int
            get() = if (completionTokens > 0) completionTokens else contentDeltas

        fun reasoning(chunk: String) {
            if (chunk.isEmpty()) return
            if (!reasoningOpen) {
                reasoningOpen = true
                emit(THINK_OPEN)
            }
            contentDeltas++
            emit(chunk)
        }

        fun content(chunk: String) {
            if (chunk.isEmpty()) return
            if (reasoningOpen) {
                reasoningOpen = false
                emit(THINK_CLOSE)
            }
            contentDeltas++
            emit(chunk)
        }

        fun finish(): String {
            if (reasoningOpen) {
                reasoningOpen = false
                text.append(THINK_CLOSE)
            }
            return text.toString()
        }

        private fun emit(chunk: String) {
            text.append(chunk)
            onDelta(chunk)
        }
    }
}
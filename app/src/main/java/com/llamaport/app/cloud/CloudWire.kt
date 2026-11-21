package com.llamaport.app.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.URI
import java.util.Locale

/** Invalid provider settings typed by the user; [message] is shown in the UI. */
class CloudConfigException(message: String) : IllegalArgumentException(message)

/** One step of a decoded SSE/JSON chat stream. */
data class SseEvent(
    val content: String? = null,
    val reasoning: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val done: Boolean = false,
    val failure: String? = null,
)

/**
 * Pure wire-format helpers for OpenAI-compatible APIs: URL normalisation,
 * request building, model-list parsing, error classification and SSE decoding.
 *
 * No Android imports: every rule below is covered by JVM unit tests, because
 * these are exactly the parts that cannot be checked on a phone without a key.
 */
object CloudWire {

    private val WHITESPACE = Regex("\\s+")

    /** Accepts "api.example.com/v1", "https://host/v1/" and normalises both. */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw CloudConfigException("Укажите адрес API")
        if (trimmed.any { it.isWhitespace() }) throw CloudConfigException("В адресе не должно быть пробелов")
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (t: Exception) {
            throw CloudConfigException("Некорректный адрес API")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw CloudConfigException("Разрешены только http и https")
        }
        if (uri.host.isNullOrBlank()) throw CloudConfigException("В адресе нет хоста")
        // A base URL must not carry a query or a fragment: those belong to the
        // concrete endpoint, which we append ourselves.
        val base = withScheme.substringBefore('#').substringBefore('?')
        return base.trimEnd('/')
    }

    /** "https://api.openai.com" → "https://api.openai.com/v1", or null when a path exists. */
    fun withV1(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().trim('/')
        if (path.isNotEmpty()) return null
        return baseUrl.trimEnd('/') + "/v1"
    }

    fun chatEndpoint(baseUrl: String): String = "${baseUrl.trimEnd('/')}/chat/completions"

    fun modelsEndpoint(baseUrl: String): String = "${baseUrl.trimEnd('/')}/models"

    fun buildChatBody(
        model: String,
        messages: JSONArray,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        compat: CloudCompat,
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("messages", messages)
        put("stream", compat.stream)
        if (compat.temperature) put("temperature", temperature.coerceIn(0f, 2f).toDouble())
        if (compat.topP) put("top_p", topP.coerceIn(0f, 1f).toDouble())
        if (compat.topK && topK > 0) put("top_k", topK)
        when (compat.maxTokensField) {
            MaxTokensField.Auto, MaxTokensField.MaxTokens ->
                if (maxTokens > 0) put("max_tokens", maxTokens)
            MaxTokensField.MaxCompletionTokens ->
                if (maxTokens > 0) put("max_completion_tokens", maxTokens)
            MaxTokensField.None -> Unit
        }
    }

    /** Optional (non-spec) fields present in a request body; the ones negotiable. */
    fun optionalParams(body: JSONObject): Set<String> {
        val core = setOf("model", "messages", "stream")
        return body.keys().asSequence().filterNot { it in core }.toSet()
    }

    /**
     * Finds which of our optional parameters an error body complains about.
     *
     * Only returns a match when the body also carries a rejection phrase, so a
     * plain "invalid api key" never causes a parameter to be dropped.
     */
    fun unsupportedParams(errorBody: String, sent: Set<String>): Set<String> {
        if (errorBody.isBlank() || sent.isEmpty()) return emptySet()
        val text = errorBody.lowercase(Locale.ROOT)
        val complains = REJECTION_HINTS.any { text.contains(it) }
        if (!complains) return emptySet()
        return sent.filterTo(LinkedHashSet()) { name ->
            Regex("(?<![a-z0-9_])" + Regex.escape(name.lowercase(Locale.ROOT)) + "(?![a-z0-9_])")
                .containsMatchIn(text)
        }
    }

    /** Removes the rejected parameters from the profile; never re-enables one. */
    fun downgrade(compat: CloudCompat, bad: Set<String>): CloudCompat {
        var next = compat
        if ("top_k" in bad) next = next.copy(topK = false)
        if ("temperature" in bad) next = next.copy(temperature = false)
        if ("top_p" in bad) next = next.copy(topP = false)
        if ("max_completion_tokens" in bad) {
            next = next.copy(
                maxTokensField = when (next.maxTokensField) {
                    MaxTokensField.Auto, MaxTokensField.MaxTokens -> MaxTokensField.MaxCompletionTokens
                    MaxTokensField.MaxCompletionTokens, MaxTokensField.None -> MaxTokensField.None
                },
            )
        }
        if ("max_tokens" in bad) {
            next = next.copy(
                maxTokensField = when (next.maxTokensField) {
                    MaxTokensField.Auto, MaxTokensField.MaxTokens -> MaxTokensField.MaxCompletionTokens
                    MaxTokensField.MaxCompletionTokens, MaxTokensField.None -> MaxTokensField.None
                },
            )
        }
        return next
    }

    /** True when a 400 looks like a streaming problem rather than a parameter one. */
    fun looksLikeStreamProblem(errorBody: String): Boolean =
        errorBody.lowercase(Locale.ROOT).contains("stream")

    /** `data[].id`, `models[].id|name`, or a plain string array. */
    fun parseModelIds(json: String): List<String> {
        val collected = LinkedHashSet<String>()
        val root = runCatching { JSONObject(json) }.getOrNull()
        if (root != null) {
            collectModels(root.optJSONArray("data"), collected)
            collectModels(root.optJSONArray("models"), collected)
        } else {
            val array = runCatching { JSONArray(json) }.getOrNull()
            if (array != null) collectModels(array, collected)
        }
        return collected.sortedBy { it.lowercase(Locale.ROOT) }
    }

    private fun collectModels(array: JSONArray?, into: MutableSet<String>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val id = when (val item = array.opt(i)) {
                is String -> item
                is JSONObject -> sequenceOf("id", "name", "model")
                    .map { item.optString(it) }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                else -> ""
            }.trim()
            if (id.isNotEmpty()) into += id
        }
    }

    /** Text of a content field that may be a string or an array of parts. */
    fun contentOf(obj: JSONObject?, key: String): String? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.opt(key)) {
            is String -> value.takeIf { it.isNotEmpty() }
            is JSONArray -> buildString {
                for (i in 0 until value.length()) {
                    val part = value.optJSONObject(i) ?: continue
                    append(part.optString("text"))
                }
            }.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    /** "message" of an OpenAI error body, if there is one. */
    fun providerError(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val error = root.optJSONObject("error") ?: return null
        val message = error.optString("message").ifBlank { error.optString("error") }
        return message.ifBlank { null }
    }

    fun sanitize(body: String): String =
        body.replace(WHITESPACE, " ").trim().take(MAX_ERROR_CHARS)

    /** Short Russian message for a failed HTTP call. */
    fun errorMessage(code: Int, body: String): String {
        val base = when (code) {
            400 -> "API отклонил запрос (HTTP 400)"
            401, 403 -> "Ключ не принят (HTTP $code)"
            404 -> "Не найден /chat/completions: проверьте base URL (HTTP 404)"
            405 -> "Метод не поддерживается этим адресом (HTTP 405)"
            408, 504 -> "Провайдер не ответил вовремя (HTTP $code)"
            413 -> "Запрос слишком большой (HTTP 413)"
            429 -> "Слишком много запросов (HTTP 429)"
            in 500..599 -> "Ошибка на стороне провайдера (HTTP $code)"
            else -> "Ошибка API (HTTP $code)"
        }
        val detail = providerError(body) ?: sanitize(body)
        return if (detail.isBlank()) base else "$base: $detail"
    }

    private const val MAX_ERROR_CHARS = 300

    private val REJECTION_HINTS = listOf(
        "unsupported",
        "not supported",
        "does not support",
        "unknown",
        "unrecognized",
        "unexpected",
        "invalid",
        "extra",
        "additional propert",
        "not permitted",
        "not allowed",
    )
}

/**
 * Decodes one `text/event-stream` body.
 *
 * Handles `data:` payloads, `[DONE]`, comments, blank separators and providers
 * that ignore `stream` and answer with a single JSON document (see [rawText]).
 */
class SseReader(private val reader: BufferedReader) {

    private val pending = StringBuilder()
    private val raw = StringBuilder()

    /** Whole body as read so far, for the non-SSE fallback. */
    fun rawText(): String = raw.toString()

    fun next(): SseEvent? {
        while (true) {
            val line = reader.readLine()
            if (line == null) return flush()
            if (raw.isNotEmpty()) raw.append('\n')
            raw.append(line)
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flush()?.let { return it }
                continue
            }
            if (trimmed.startsWith(":")) continue
            if (!trimmed.startsWith("data:")) continue // event:, id:, retry:, junk
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") return SseEvent(done = true)
            decode(payload)?.let { return it }
            // Not a complete JSON document: part of a multi-line data event.
            if (pending.isNotEmpty()) pending.append('\n')
            pending.append(payload)
        }
    }

    private fun flush(): SseEvent? {
        if (pending.isEmpty()) return null
        val payload = pending.toString()
        pending.setLength(0)
        return decode(payload)
    }

    private fun decode(payload: String): SseEvent? {
        if (payload.isBlank()) return null
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { error.toString() }
            return SseEvent(failure = message)
        }
        val usage = root.optJSONObject("usage")
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        val message = choice?.optJSONObject("message")
        val content = CloudWire.contentOf(delta, "content") ?: CloudWire.contentOf(message, "content")
        val reasoning = CloudWire.contentOf(delta, "reasoning_content")
            ?: CloudWire.contentOf(delta, "reasoning")
            ?: CloudWire.contentOf(message, "reasoning_content")
        return SseEvent(
            content = content,
            reasoning = reasoning,
            promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
        )
    }
}
package com.llamaport.app.cloud

/**
 * Data model for OpenAI-compatible providers ("cloud models").
 *
 * Everything in this file is plain Kotlin: no Android APIs, so the wire format,
 * the capability negotiation and the store are all unit-testable on the JVM.
 */

/**
 * Which JSON field carries the new-token limit.
 *
 * The classic OpenAI field is `max_tokens`; the newer reasoning models want
 * `max_completion_tokens`, and a few proxies reject both. [Auto] means
 * "max_tokens first, downgrade after the first 400 that names it".
 */
enum class MaxTokensField(val wire: String) {
    Auto("auto"),
    MaxTokens("max_tokens"),
    MaxCompletionTokens("max_completion_tokens"),
    None("none"),
    ;

    companion object {
        fun fromWire(raw: String?): MaxTokensField =
            entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: Auto
    }
}

/**
 * What this provider's API accepts. Learned lazily: the first request is sent
 * with the defaults, a 400 that names a parameter downgrades just that
 * parameter, and the result is persisted so the next launch starts correct.
 *
 * The profile is stored per provider rather than per model: providers mix
 * models with different support levels, and a conservative provider-wide answer
 * is better than a wrong optimistic one. The tradeoff is documented.
 */
data class CloudCompat(
    /** `top_k` is not part of the OpenAI spec; many servers reject it. */
    val topK: Boolean = true,
    /** Some reasoning models only accept the default temperature. */
    val temperature: Boolean = true,
    /** Some reasoning models reject `top_p` as well. */
    val topP: Boolean = true,
    val maxTokensField: MaxTokensField = MaxTokensField.Auto,
    /** `stream: true`; a handful of proxies only support non-streamed replies. */
    val stream: Boolean = true,
    /** Epoch millis of the last explicit compatibility check, 0 when never. */
    val checkedAt: Long = 0L,
)

/** A configured provider. [apiKey] may be blank for servers without auth. */
data class CloudProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val models: List<String> = emptyList(),
    val compat: CloudCompat = CloudCompat(),
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()
}

/** Identifies the active remote model. */
data class CloudModelRef(
    val providerId: String,
    val modelId: String,
)

/** Editor payload; [id] is null while a provider is being created. */
data class CloudProviderDraft(
    val id: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: List<String> = emptyList(),
)

/** One line of the "check API" report. */
data class CloudProbeRow(
    val label: String,
    val ok: Boolean,
    val detail: String,
)

/** Transient state of the provider editor: network results only, never saved as-is. */
data class CloudEditorUi(
    val fetching: Boolean = false,
    val fetchedModels: List<String> = emptyList(),
    /** Base URL corrected by the /v1 fallback, if the server needed it. */
    val resolvedBaseUrl: String? = null,
    val fetchError: String? = null,
    val probing: Boolean = false,
    val probe: List<CloudProbeRow> = emptyList(),
    /** Compat learned by the last probe, applied when the provider is saved. */
    val detectedCompat: CloudCompat? = null,
)

/** Human-readable key state; the key itself never leaves the provider form. */
fun maskKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return "без ключа"
    if (trimmed.length <= 8) return "…" + trimmed.takeLast(2)
    return trimmed.take(3) + "…" + trimmed.takeLast(4)
}

/** Short host for provider cards, e.g. "api.openai.com/v1". */
fun hostOf(baseUrl: String): String {
    val withoutScheme = baseUrl.substringAfter("://", baseUrl)
    return withoutScheme.trimEnd('/')
}

/** Text persisted in session summaries / the model chip for a remote model. */
fun cloudModelLabel(provider: CloudProvider, modelId: String): String = modelId

fun cloudInfoLine(provider: CloudProvider, modelId: String): String =
    "${provider.name} · $modelId · облако"
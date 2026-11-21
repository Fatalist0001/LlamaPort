package com.llamaport.app.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What [CloudStore] restores: configured providers plus the last active model. */
data class StoredCloud(
    val providers: List<CloudProvider> = emptyList(),
    val active: CloudModelRef? = null,
)

/**
 * Persists cloud providers in `filesDir/cloud.json`, written atomically at
 * meaningful checkpoints (add/edit/remove/select/extend), never per token.
 *
 * The file contains API keys in plain text inside the app's private sandbox and
 * is excluded from Android backup/transfer by the manifest rules, so the keys do
 * not leave the device. No Android imports here on purpose: the round trip is
 * covered by plain JVM tests.
 */
class CloudStore(
    filesDir: File,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) {

    companion object {
        const val FILE_NAME = "cloud.json"
        const val VERSION = 1
    }

    private val file = File(filesDir, FILE_NAME)
    private val lock = Any()

    fun load(): StoredCloud = synchronized(lock) {
        if (!file.exists()) return StoredCloud()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", 0) > VERSION) {
                onError("cloud.json is newer than this build", null)
            }
            val providers = ArrayList<CloudProvider>()
            val array = root.optJSONArray("providers") ?: JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val provider = providerFromJson(obj) ?: continue
                if (providers.any { it.id == provider.id }) continue
                providers += provider
            }
            val activeObj = root.optJSONObject("active")
            val active = activeObj?.let { obj ->
                val providerId = obj.optString("providerId")
                val modelId = obj.optString("modelId")
                providers.firstOrNull { it.id == providerId && modelId in it.models }
                    ?.let { CloudModelRef(providerId, modelId) }
            }
            StoredCloud(providers, active)
        }.onFailure {
            onError("failed to read ${file.name}", it)
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.bad")) }
        }.getOrDefault(StoredCloud())
    }

    fun save(providers: List<CloudProvider>, active: CloudModelRef?) = synchronized(lock) {
        runCatching {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put(
                "active",
                active?.let { ref ->
                    JSONObject().apply {
                        put("providerId", ref.providerId)
                        put("modelId", ref.modelId)
                    }
                } ?: JSONObject.NULL,
            )
            val array = JSONArray()
            for (provider in providers) {
                array.put(providerToJson(provider))
            }
            root.put("providers", array)

            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (file.exists()) file.delete()
            check(tmp.renameTo(file)) { "rename failed" }
        }.onFailure {
            onError("failed to save ${file.name}", it)
        }
    }

    private fun providerFromJson(obj: JSONObject): CloudProvider? {
        val id = obj.optString("id").trim()
        val name = obj.optString("name").trim()
        val baseUrl = obj.optString("baseUrl").trim()
        if (id.isEmpty() || name.isEmpty() || baseUrl.isEmpty()) return null
        val models = ArrayList<String>()
        val modelsArray = obj.optJSONArray("models") ?: JSONArray()
        for (i in 0 until modelsArray.length()) {
            val model = modelsArray.optString(i).trim()
            if (model.isNotEmpty() && model !in models) models += model
        }
        return CloudProvider(
            id = id,
            name = name,
            baseUrl = baseUrl,
            apiKey = obj.optString("apiKey"),
            models = models,
            compat = compatFromJson(obj.optJSONObject("compat")),
        )
    }

    private fun providerToJson(provider: CloudProvider): JSONObject = JSONObject().apply {
        put("id", provider.id)
        put("name", provider.name)
        put("baseUrl", provider.baseUrl)
        put("apiKey", provider.apiKey)
        val modelsArray = JSONArray()
        for (model in provider.models) modelsArray.put(model)
        put("models", modelsArray)
        put("compat", compatToJson(provider.compat))
    }

    private fun compatFromJson(obj: JSONObject?): CloudCompat {
        if (obj == null) return CloudCompat()
        return CloudCompat(
            topK = obj.optBoolean("topK", true),
            temperature = obj.optBoolean("temperature", true),
            topP = obj.optBoolean("topP", true),
            maxTokensField = MaxTokensField.fromWire(obj.optString("maxTokens", MaxTokensField.Auto.wire)),
            stream = obj.optBoolean("stream", true),
            checkedAt = obj.optLong("checkedAt", 0L),
        )
    }

    private fun compatToJson(compat: CloudCompat): JSONObject = JSONObject().apply {
        put("topK", compat.topK)
        put("temperature", compat.temperature)
        put("topP", compat.topP)
        put("maxTokens", compat.maxTokensField.wire)
        put("stream", compat.stream)
        put("checkedAt", compat.checkedAt)
    }
}
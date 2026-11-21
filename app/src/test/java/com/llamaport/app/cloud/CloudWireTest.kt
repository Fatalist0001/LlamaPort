package com.llamaport.app.cloud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudWireTest {

    // ------------------------------------------------------------ base url

    @Test
    fun `base url normalisation fills scheme and trims slashes`() {
        assertEquals("https://api.openai.com/v1", CloudWire.normalizeBaseUrl("https://api.openai.com/v1/"))
        assertEquals("https://api.openai.com/v1", CloudWire.normalizeBaseUrl("api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1", CloudWire.normalizeBaseUrl("  https://api.openai.com/v1///  "))
        assertEquals("http://192.168.1.10:8080/v1", CloudWire.normalizeBaseUrl("http://192.168.1.10:8080/v1"))
        assertEquals("https://host/v1", CloudWire.normalizeBaseUrl("https://host/v1?key=1"))
    }

    @Test
    fun `invalid base urls are rejected with a readable message`() {
        for (input in listOf("", "   ", "ftp://host/v1", "https://", "https://a b c/v1")) {
            try {
                CloudWire.normalizeBaseUrl(input)
                fail("expected failure for '$input'")
            } catch (e: CloudConfigException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `v1 fallback only applies to a bare host`() {
        assertEquals("https://api.openai.com/v1", CloudWire.withV1("https://api.openai.com"))
        assertEquals("https://api.openai.com/v1", CloudWire.withV1("https://api.openai.com/"))
        assertNull(CloudWire.withV1("https://api.openai.com/v1"))
        assertNull(CloudWire.withV1("http://192.168.1.5:8080/api"))
    }

    @Test
    fun `endpoints are appended to the base`() {
        assertEquals("https://x/v1/models", CloudWire.modelsEndpoint("https://x/v1"))
        assertEquals("https://x/v1/chat/completions", CloudWire.chatEndpoint("https://x/v1/"))
    }

    // ------------------------------------------------------------- request

    private fun messages() = JSONArray().put(
        JSONObject().apply {
            put("role", "user")
            put("content", "hi")
        },
    )

    @Test
    fun `default body carries every supported parameter`() {
        val body = CloudWire.buildChatBody(
            model = "gpt-4o",
            messages = messages(),
            temperature = 0.7f,
            topP = 0.95f,
            topK = 40,
            maxTokens = 256,
            compat = CloudCompat(),
        )
        assertEquals("gpt-4o", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(0.7, body.getDouble("temperature"), 0.0001)
        assertEquals(0.95, body.getDouble("top_p"), 0.0001)
        assertEquals(40, body.getInt("top_k"))
        assertEquals(256, body.getInt("max_tokens"))
        assertEquals(
            setOf("temperature", "top_p", "top_k", "max_tokens"),
            CloudWire.optionalParams(body),
        )
    }

    @Test
    fun `disabled capabilities are omitted from the body`() {
        val body = CloudWire.buildChatBody(
            model = "o3",
            messages = messages(),
            temperature = 0.7f,
            topP = 0.95f,
            topK = 40,
            maxTokens = 100,
            compat = CloudCompat(
                topK = false,
                temperature = false,
                topP = false,
                maxTokensField = MaxTokensField.MaxCompletionTokens,
                stream = false,
            ),
        )
        assertFalse(body.getBoolean("stream"))
        assertFalse(body.has("temperature"))
        assertFalse(body.has("top_p"))
        assertFalse(body.has("top_k"))
        assertEquals(100, body.getInt("max_completion_tokens"))
        assertEquals(setOf("max_completion_tokens"), CloudWire.optionalParams(body))
    }

    @Test
    fun `no limit field when the api rejects both`() {
        val body = CloudWire.buildChatBody(
            model = "m",
            messages = messages(),
            temperature = 0f,
            topP = 1f,
            topK = 0,
            maxTokens = 100,
            compat = CloudCompat(maxTokensField = MaxTokensField.None),
        )
        assertFalse(body.has("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
    }

    @Test
    fun `sampling values are clamped to what the api accepts`() {
        val body = CloudWire.buildChatBody("m", messages(), 3f, 2f, 10, 10, CloudCompat())
        assertEquals(2.0, body.getDouble("temperature"), 0.0001)
        assertEquals(1.0, body.getDouble("top_p"), 0.0001)
    }

    // --------------------------------------------------------- negotiation

    @Test
    fun `unsupported parameter is detected from an error body`() {
        val sent = setOf("temperature", "top_p", "top_k", "max_tokens")
        val openAi = """{"error":{"message":"Unsupported parameter: 'top_k' is not supported with this model."}}"""
        assertEquals(setOf("top_k"), CloudWire.unsupportedParams(openAi, sent))

        val deepSeek = """{"error":{"message":"max_tokens is not supported, use max_completion_tokens"}}"""
        assertEquals(setOf("max_tokens"), CloudWire.unsupportedParams(deepSeek, sent))

        val topP = """{"error":{"message":"unknown field `top_p`"}}"""
        assertEquals(setOf("top_p"), CloudWire.unsupportedParams(topP, sent))

        val extra = """{"error":{"message":"temperature: extra fields not permitted"}}"""
        assertEquals(setOf("temperature"), CloudWire.unsupportedParams(extra, sent))
    }

    @Test
    fun `plain auth errors never drop a parameter`() {
        val sent = setOf("temperature", "top_p", "top_k", "max_tokens")
        assertEquals(emptySet<String>(), CloudWire.unsupportedParams("""{"error":{"message":"Invalid API key"}}""", sent))
        assertEquals(emptySet<String>(), CloudWire.unsupportedParams("""{"error":{"message":"model not found"}}""", sent))
        assertEquals(emptySet<String>(), CloudWire.unsupportedParams("", sent))
    }

    @Test
    fun `downgrade walks the max tokens fallback chain`() {
        val defaults = CloudCompat()
        val topK = CloudWire.downgrade(defaults, setOf("top_k"))
        assertFalse(topK.topK)
        assertTrue(topK.temperature)

        val field = CloudWire.downgrade(defaults, setOf("max_tokens"))
        assertEquals(MaxTokensField.MaxCompletionTokens, field.maxTokensField)

        val none = CloudWire.downgrade(field, setOf("max_completion_tokens"))
        assertEquals(MaxTokensField.None, none.maxTokensField)

        val sampling = CloudWire.downgrade(defaults, setOf("temperature", "top_p"))
        assertFalse(sampling.temperature)
        assertFalse(sampling.topP)
    }

    @Test
    fun `downgrade never re-enables a rejected parameter`() {
        val already = CloudCompat(topK = false, temperature = false)
        assertEquals(already, CloudWire.downgrade(already, setOf("top_k", "temperature")))
    }

    // ----------------------------------------------------------- model list

    @Test
    fun `model ids are read from every common list shape`() {
        val openAi = """{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"}]}"""
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), CloudWire.parseModelIds(openAi))

        val named = """{"models":[{"name":"qwen3:8b"},{"id":"llama3.2"}]}"""
        assertEquals(listOf("llama3.2", "qwen3:8b"), CloudWire.parseModelIds(named))

        val plain = """["b-model","a-model","a-model","  "]"""
        assertEquals(listOf("a-model", "b-model"), CloudWire.parseModelIds(plain))
    }

    @Test
    fun `model list parsing tolerates junk`() {
        assertEquals(emptyList<String>(), CloudWire.parseModelIds("<html>not json</html>"))
        assertEquals(emptyList<String>(), CloudWire.parseModelIds("{}"))
        assertEquals(listOf("m"), CloudWire.parseModelIds("""{"data":[{"object":"model"},{"id":"m"}]}"""))
    }

    // -------------------------------------------------------------- errors

    @Test
    fun `http errors map to readable russian messages`() {
        assertTrue(CloudWire.errorMessage(401, "{}").startsWith("Ключ не принят"))
        assertTrue(CloudWire.errorMessage(429, "{}").contains("429"))
        assertTrue(CloudWire.errorMessage(503, "{}").contains("провайдер"))
        assertTrue(CloudWire.errorMessage(404, "{}").contains("base URL"))
        val detailed = CloudWire.errorMessage(400, """{"error":{"message":"bad request detail"}}""")
        assertTrue(detailed.contains("bad request detail"))
    }

    @Test
    fun `error bodies are flattened and truncated`() {
        val long = "x".repeat(1000)
        assertTrue(CloudWire.sanitize("a\n\nb   c $long").length <= 300)
        assertTrue(CloudWire.sanitize("a\n b").contains("a b"))
        assertNull(CloudWire.providerError("no json"))
        assertNotNull(CloudWire.providerError("""{"error":{"message":"m"}}"""))
    }
}
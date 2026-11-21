package com.llamaport.app.cloud

import com.llamaport.app.hasThinkingTags
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream

/**
 * Drives the full client logic through a scripted transport: streaming, SSE
 * reassembly, capability negotiation, cancellation and the probe report. No
 * device, no key and no network are involved.
 */
class CloudClientTest {

    private class FakeTransport : CloudTransport {
        val gets = ArrayDeque<CloudHttp>()
        val posts = ArrayDeque<CloudHttp>()
        val getUrls = ArrayList<String>()
        val postBodies = ArrayList<String>()
        val postHeaders = ArrayList<Map<String, String>>()
        var aborted = false

        override fun get(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): CloudHttp {
            getUrls += url
            return gets.removeFirstOrNull() ?: CloudHttp(500, "no scripted response")
        }

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): CloudHttp {
            postBodies += body
            postHeaders += headers
            return posts.removeFirstOrNull() ?: CloudHttp(500, "no scripted response")
        }

        override fun abort() {
            aborted = true
        }
    }

    /** Returns at most [chunk] bytes per read, so line assembly is exercised. */
    private class SlowInputStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position >= data.size) -1 else data[position++].toInt() and 0xff

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(chunk, length, data.size - position)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            return count
        }
    }

    private fun streamOf(text: String, chunk: Int = 3): InputStream =
        SlowInputStream(text.toByteArray(Charsets.UTF_8), chunk)

    private fun sse(vararg payloads: String): String =
        payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun delta(content: String? = null, reasoning: String? = null, finish: String? = null): String {
        val data = JSONObject()
        if (content != null) data.put("content", content)
        if (reasoning != null) data.put("reasoning_content", reasoning)
        val choice = JSONObject().put("delta", data)
        if (finish != null) choice.put("finish_reason", finish)
        return JSONObject().put("choices", JSONArray().put(choice)).toString()
    }

    private fun usage(prompt: Int, completion: Int): String = JSONObject()
        .put("choices", JSONArray())
        .put(
            "usage",
            JSONObject().put("prompt_tokens", prompt).put("completion_tokens", completion),
        )
        .toString()

    private val messages = JSONArray().put(
        JSONObject().put("role", "user").put("content", "hi"),
    )

    private fun request(
        compat: CloudCompat = CloudCompat(),
        apiKey: String = "sk-test",
        maxTokens: Int = 64,
    ) = CloudChatRequest(
        baseUrl = "https://api.test/v1",
        apiKey = apiKey,
        model = "test-model",
        messages = messages,
        temperature = 0.5f,
        topP = 0.9f,
        topK = 30,
        maxTokens = maxTokens,
        compat = compat,
    )

    // ------------------------------------------------------------- streaming

    @Test
    fun `streamed answer is assembled from deltas`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(
            200,
            stream = streamOf(sse(delta(content = "Hel"), delta(content = "lo"), delta(content = "!"))),
        )
        val chunks = ArrayList<String>()

        val result = CloudClient(transport).chat(request(), onDelta = { chunks += it })

        assertEquals("Hello!", result.text)
        assertEquals(listOf("Hel", "lo", "!"), chunks)
        assertEquals(3, result.completionTokens)
        assertFalse(result.downgraded)
    }

    @Test
    fun `usage chunk overrides the delta estimate`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(
            200,
            stream = streamOf(sse(delta(content = "ok"), usage(prompt = 12, completion = 5))),
        )

        val result = CloudClient(transport).chat(request())

        assertEquals(12, result.promptTokens)
        assertEquals(5, result.completionTokens)
    }

    @Test
    fun `reasoning deltas become a think block`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(
            200,
            stream = streamOf(
                sse(delta(reasoning = "step one"), delta(reasoning = " and two"), delta(content = "answer")),
            ),
        )

        val result = CloudClient(transport).chat(request())

        assertEquals("<think>step one and two</think>answer", result.text)
        assertTrue(hasThinkingTags(result.text))
    }

    @Test
    fun `server ignoring stream still yields an answer`() = runBlocking {
        val transport = FakeTransport()
        val plain = """{"choices":[{"message":{"content":"plain answer"}}]}"""
        transport.posts += CloudHttp(200, stream = streamOf(plain))

        val result = CloudClient(transport).chat(request())

        assertEquals("plain answer", result.text)
    }

    @Test
    fun `crlf line endings are accepted`() = runBlocking {
        val transport = FakeTransport()
        val body = "data: " + delta(content = "a") + "\r\n\r\n" +
            "data: " + delta(content = "b") + "\r\n\r\n" +
            "data: [DONE]\r\n\r\n"
        transport.posts += CloudHttp(200, stream = streamOf(body))

        assertEquals("ab", CloudClient(transport).chat(request()).text)
    }

    @Test
    fun `comments and keep-alives are ignored`() = runBlocking {
        val transport = FakeTransport()
        val body = ": keep-alive\n\n" + sse(delta(content = "x"))
        transport.posts += CloudHttp(200, stream = streamOf(body))

        assertEquals("x", CloudClient(transport).chat(request()).text)
    }

    // ----------------------------------------------------------- negotiation

    @Test
    fun `rejected top_k is dropped and the retry succeeds`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(
            400,
            """{"error":{"message":"Unsupported parameter: 'top_k' is not supported with this model."}}""",
        )
        transport.posts += CloudHttp(200, stream = streamOf(sse(delta(content = "ok"))))

        val result = CloudClient(transport).chat(request())

        assertEquals(2, transport.postBodies.size)
        assertTrue(transport.postBodies[0].contains("\"top_k\""))
        assertFalse(transport.postBodies[1].contains("\"top_k\""))
        assertFalse(result.compat.topK)
        assertTrue(result.downgraded)
    }

    @Test
    fun `max_tokens walks to max_completion_tokens and then to none`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(400, """{"error":{"message":"max_tokens is not supported"}}""")
        transport.posts += CloudHttp(
            400,
            """{"error":{"message":"max_completion_tokens is not supported either"}}""",
        )
        transport.posts += CloudHttp(200, stream = streamOf(sse(delta(content = "ok"))))

        val result = CloudClient(transport).chat(request())

        assertEquals(3, transport.postBodies.size)
        assertTrue(transport.postBodies[0].contains("\"max_tokens\""))
        assertTrue(transport.postBodies[1].contains("\"max_completion_tokens\""))
        assertFalse(transport.postBodies[2].contains("max_tokens"))
        assertFalse(transport.postBodies[2].contains("max_completion_tokens"))
        assertEquals(MaxTokensField.None, result.compat.maxTokensField)
    }

    @Test
    fun `unattributable error is reported without guessing`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(400, """{"error":{"message":"Invalid API key"}}""")

        try {
            CloudClient(transport).chat(request())
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertEquals(1, transport.postBodies.size)
            assertTrue(e.message!!.contains("API отклонил"))
        }
    }

    @Test
    fun `stream rejection retries without streaming`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(400, """{"error":{"message":"stream is not supported by this endpoint"}}""")
        transport.posts += CloudHttp(
            200,
            body = """{"choices":[{"message":{"content":"plain"}}]}""",
        )

        val result = CloudClient(transport).chat(request())

        assertEquals("plain", result.text)
        assertFalse(result.compat.stream)
        assertTrue(transport.postBodies[1].contains("\"stream\":false"))
    }

    @Test
    fun `authorization header follows the key`() = runBlocking {
        val withKey = FakeTransport()
        withKey.posts += CloudHttp(200, stream = streamOf(sse(delta(content = "x"))))
        CloudClient(withKey).chat(request(apiKey = "sk-test"))

        val withoutKey = FakeTransport()
        withoutKey.posts += CloudHttp(200, stream = streamOf(sse(delta(content = "x"))))
        CloudClient(withoutKey).chat(request(apiKey = "   "))

        assertEquals("Bearer sk-test", withKey.postHeaders[0]["Authorization"])
        assertFalse(withoutKey.postHeaders[0].containsKey("Authorization"))
        assertEquals("text/event-stream", withKey.postHeaders[0]["Accept"])
    }

    @Test
    fun `cancellation before the request sends nothing`() = runBlocking {
        val transport = FakeTransport()
        try {
            CloudClient(transport).chat(request(), isCancelled = { true })
            fail("expected CloudCancelled")
        } catch (expected: CloudCancelled) {
            assertTrue(transport.postBodies.isEmpty())
        }
    }

    @Test
    fun `cancellation mid stream stops reading`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(
            200,
            stream = streamOf(sse(delta(content = "a"), delta(content = "b"), delta(content = "c"))),
        )
        var received = 0
        try {
            CloudClient(transport).chat(
                request(),
                onDelta = { received++ },
                isCancelled = { received >= 1 },
            )
            fail("expected CloudCancelled")
        } catch (expected: CloudCancelled) {
            assertTrue(received >= 1)
        }
    }

    @Test
    fun `abort is forwarded to the transport`() {
        val transport = FakeTransport()
        CloudClient(transport).abort()
        assertTrue(transport.aborted)
    }

    // ------------------------------------------------------------ model list

    @Test
    fun `model list is fetched and sorted`() = runBlocking {
        val transport = FakeTransport()
        transport.gets += CloudHttp(200, """{"data":[{"id":"b-model"},{"id":"a-model"}]}""")

        val result = CloudClient(transport).listModels("https://api.test/v1", "sk-test")

        assertEquals(listOf("a-model", "b-model"), result.models)
        assertEquals("https://api.test/v1", result.baseUrl)
        assertEquals("https://api.test/v1/models", transport.getUrls[0])
    }

    @Test
    fun `bare host falls back to the v1 path`() = runBlocking {
        val transport = FakeTransport()
        transport.gets += CloudHttp(404, "not found")
        transport.gets += CloudHttp(200, """{"data":[{"id":"m"}]}""")

        val result = CloudClient(transport).listModels("https://api.test", "sk-test")

        assertEquals("https://api.test/v1", result.baseUrl)
        assertEquals(listOf("m"), result.models)
        assertEquals(2, transport.getUrls.size)
        assertTrue(transport.getUrls[1].endsWith("/v1/models"))
    }

    @Test
    fun `model list errors are readable`() = runBlocking {
        val unauthorized = FakeTransport()
        unauthorized.gets += CloudHttp(401, """{"error":{"message":"bad key"}}""")
        try {
            CloudClient(unauthorized).listModels("https://api.test/v1", "sk-bad")
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.message!!.startsWith("Ключ не принят"))
        }

        val empty = FakeTransport()
        empty.gets += CloudHttp(200, """{"object":"list"}""")
        try {
            CloudClient(empty).listModels("https://api.test/v1", "")
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("не вернул список"))
        }
    }

    // ----------------------------------------------------------------- probe

    @Test
    fun `probe reports working parameters`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(
            200,
            body = """{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":3,"completion_tokens":1}}""",
        )
        transport.posts += CloudHttp(200, stream = streamOf(sse(delta(content = "ok"))))

        val result = CloudClient(transport).probe(
            "https://api.test/v1",
            "sk-test",
            "test-model",
            CloudCompat(),
        )

        assertTrue(result.reachable)
        assertTrue(result.compat.stream)
        assertTrue(result.compat.checkedAt > 0)
        assertTrue(result.rows.any { it.label == "top_k" && it.ok })
        assertTrue(result.rows.any { it.label == "Потоковый ответ" && it.ok })
        assertEquals(2, transport.postBodies.size)
    }

    @Test
    fun `probe records a rejected parameter`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(400, """{"error":{"message":"Unsupported parameter: 'top_k'"}}""")
        transport.posts += CloudHttp(200, body = """{"choices":[{"message":{"content":"ok"}}]}""")
        transport.posts += CloudHttp(200, stream = streamOf(sse(delta(content = "ok"))))

        val result = CloudClient(transport).probe(
            "https://api.test/v1",
            "sk-test",
            "test-model",
            CloudCompat(),
        )

        assertTrue(result.reachable)
        assertFalse(result.compat.topK)
        assertTrue(result.rows.any { it.label == "top_k" && !it.ok })
    }

    @Test
    fun `probe on an unreachable provider reports the error`() = runBlocking {
        val transport = FakeTransport()
        transport.posts += CloudHttp(401, """{"error":{"message":"bad key"}}""")
        transport.posts += CloudHttp(401, """{"error":{"message":"bad key"}}""")

        val result = CloudClient(transport).probe(
            "https://api.test/v1",
            "sk-bad",
            "test-model",
            CloudCompat(),
        )

        assertFalse(result.reachable)
        assertTrue(result.rows.any { !it.ok && it.detail.contains("Ключ") })
    }
}
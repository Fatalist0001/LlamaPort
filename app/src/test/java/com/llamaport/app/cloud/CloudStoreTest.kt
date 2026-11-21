package com.llamaport.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloudStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(dir: File = folder.root) = CloudStore(dir)

    @Test
    fun `empty directory loads an empty state`() {
        val stored = store().load()
        assertTrue(stored.providers.isEmpty())
        assertNull(stored.active)
    }

    @Test
    fun `round trip preserves providers compat and active model`() {
        val provider = CloudProvider(
            id = "p1",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-secret-123456",
            models = listOf("gpt-4o", "gpt-4.1-mini"),
            compat = CloudCompat(
                topK = false,
                temperature = false,
                topP = false,
                maxTokensField = MaxTokensField.MaxCompletionTokens,
                stream = false,
                checkedAt = 1234L,
            ),
        )
        val second = CloudProvider(
            id = "p2",
            name = "Local",
            baseUrl = "http://192.168.1.5:8080/v1",
            apiKey = "",
            models = listOf("qwen3"),
        )
        val input = StoredCloud(
            providers = listOf(provider, second),
            active = CloudModelRef("p1", "gpt-4o"),
        )

        store().save(input.providers, input.active)
        val restored = store().load()

        assertEquals(2, restored.providers.size)
        assertEquals(provider, restored.providers[0])
        assertEquals(second, restored.providers[1])
        assertEquals(CloudModelRef("p1", "gpt-4o"), restored.active)
    }

    @Test
    fun `active model that no longer exists is dropped`() {
        val provider = CloudProvider(id = "p1", name = "P", baseUrl = "https://x/v1", models = listOf("a"))
        store().save(listOf(provider), CloudModelRef("p1", "gone"))
        assertNull(store().load().active)

        store().save(listOf(provider), CloudModelRef("missing", "a"))
        assertNull(store().load().active)
    }

    @Test
    fun `unknown compat values fall back to safe defaults`() {
        val dir = folder.root
        File(dir, CloudStore.FILE_NAME).writeText(
            """
            {
              "version": 1,
              "providers": [
                {
                  "id": "p1",
                  "name": "Weird",
                  "baseUrl": "https://api.example.com/v1",
                  "models": ["m"],
                  "compat": {"topK": "yes", "maxTokens": "shielded", "stream": 0}
                }
              ]
            }
            """.trimIndent(),
        )
        val compat = store().load().providers.single().compat
        assertEquals(CloudCompat(), compat)
    }

    @Test
    fun `corrupt file is moved away and starts empty`() {
        File(folder.root, CloudStore.FILE_NAME).writeText("{not json")
        val stored = store().load()
        assertTrue(stored.providers.isEmpty())
        assertTrue(File(folder.root, "${CloudStore.FILE_NAME}.bad").exists())
    }

    @Test
    fun `invalid providers are skipped and duplicates collapse`() {
        File(folder.root, CloudStore.FILE_NAME).writeText(
            """
            {
              "version": 1,
              "providers": [
                {"id": "", "name": "No id", "baseUrl": "https://a/v1"},
                {"id": "p1", "name": "", "baseUrl": "https://a/v1"},
                {"id": "p1", "name": "P", "baseUrl": "https://a/v1", "models": ["m", "m", " "]} ,
                {"id": "p1", "name": "P again", "baseUrl": "https://b/v1"}
              ]
            }
            """.trimIndent(),
        )
        val providers = store().load().providers
        assertEquals(1, providers.size)
        assertEquals(listOf("m"), providers.single().models)
    }

    @Test
    fun `key masking never reveals the whole secret`() {
        assertEquals("без ключа", maskKey(""))
        assertEquals("без ключа", maskKey("   "))
        assertEquals("sk-…3456", maskKey("sk-super-secret-key-123456"))
        assertEquals("123…6789", maskKey("123456789"))
        assertEquals("…rt", maskKey("short"))
    }
}
package com.llamaport.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A file attached to a user message. Paths point into the app's private storage. */
data class Attachment(
    val kind: String, // image | audio
    val path: String,
    val name: String,
    val mime: String,
)

data class SessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val preview: String = "",
)

data class StoredChats(
    val sessions: List<SessionSummary>,
    val messages: Map<String, List<ChatMessage>>,
    val activeId: String?,
)

/**
 * Persists chat sessions as a single JSON file. The file is small (text only),
 * written atomically and only at meaningful checkpoints, not on every token.
 */
class ChatStore(filesDir: File) {

    companion object {
        private const val TAG = "ChatStore"
        private const val FILE_NAME = "sessions.json"
        private const val VERSION = 1
    }

    private val file = File(filesDir, FILE_NAME)
    private val lock = Any()

    fun load(): StoredChats = synchronized(lock) {
        if (!file.exists()) return StoredChats(emptyList(), emptyMap(), null)
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", 0) > VERSION) {
                Log.w(TAG, "sessions file is newer than this build")
            }
            val sessions = ArrayList<SessionSummary>()
            val messages = HashMap<String, List<ChatMessage>>()
            val array = root.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                val sessionMessages = ArrayList<ChatMessage>()
                val msgs = obj.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until msgs.length()) {
                    val m = msgs.optJSONObject(j) ?: continue
                    sessionMessages += messageFromJson(m)
                }
                val summary = SessionSummary(
                    id = id,
                    title = obj.optString("title", "Новый чат").ifBlank { "Новый чат" },
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    messageCount = obj.optInt("messageCount", sessionMessages.size),
                    preview = obj.optString("preview", ""),
                )
                sessions += summary
                messages[id] = sessionMessages
            }
            if (sessions.isEmpty()) {
                StoredChats(emptyList(), emptyMap(), null)
            } else {
                val active = root.optString("activeId", "").takeIf { id ->
                    sessions.any { it.id == id }
                } ?: sessions.maxByOrNull { it.updatedAt }?.id
                StoredChats(sessions, messages, active)
            }
        }.onFailure {
            Log.e(TAG, "failed to read sessions: ${it.message}")
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.bad")) }
        }.getOrDefault(StoredChats(emptyList(), emptyMap(), null))
    }

    fun save(
        sessions: List<SessionSummary>,
        messages: Map<String, List<ChatMessage>>,
        activeId: String?,
    ) = synchronized(lock) {
        runCatching {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("activeId", activeId ?: JSONObject.NULL)
            val array = JSONArray()
            for (summary in sessions) {
                val obj = JSONObject()
                obj.put("id", summary.id)
                obj.put("title", summary.title)
                obj.put("createdAt", summary.createdAt)
                obj.put("updatedAt", summary.updatedAt)
                obj.put("messageCount", summary.messageCount)
                obj.put("preview", summary.preview)
                val msgs = JSONArray()
                for (message in messages[summary.id].orEmpty()) {
                    msgs.put(messageToJson(message))
                }
                obj.put("messages", msgs)
                array.put(obj)
            }
            root.put("sessions", array)

            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (file.exists()) file.delete()
            check(tmp.renameTo(file)) { "rename failed" }
        }.onFailure {
            Log.e(TAG, "failed to save sessions: ${it.message}")
        }
    }

    private fun messageToJson(message: ChatMessage): JSONObject = JSONObject().apply {
        put("id", message.id)
        put("role", message.role)
        put("text", message.text)
        put("stats", message.stats ?: JSONObject.NULL)
        put("isError", message.isError)
        put("toolName", message.toolName ?: JSONObject.NULL)
        put("toolArgs", message.toolArgs ?: JSONObject.NULL)
        put("toolResult", message.toolResult ?: JSONObject.NULL)
        val atts = JSONArray()
        for (attachment in message.attachments) {
            atts.put(JSONObject().apply {
                put("kind", attachment.kind)
                put("path", attachment.path)
                put("name", attachment.name)
                put("mime", attachment.mime)
            })
        }
        put("attachments", atts)
    }

    private fun messageFromJson(obj: JSONObject): ChatMessage {
        val attachments = ArrayList<Attachment>()
        val atts = obj.optJSONArray("attachments") ?: JSONArray()
        for (i in 0 until atts.length()) {
            val a = atts.optJSONObject(i) ?: continue
            val path = a.optString("path")
            if (path.isBlank()) continue
            attachments += Attachment(
                kind = a.optString("kind", "image"),
                path = path,
                name = a.optString("name", File(path).name),
                mime = a.optString("mime", ""),
            )
        }
        return ChatMessage(
            id = obj.optLong("id", System.nanoTime()),
            role = obj.optString("role", "user"),
            text = obj.optString("text", ""),
            streaming = false,
            stats = obj.optString("stats").takeIf { it.isNotBlank() && it != "null" },
            isError = obj.optBoolean("isError", false),
            toolName = obj.optString("toolName").takeIf { it.isNotBlank() && it != "null" },
            toolArgs = obj.optString("toolArgs").takeIf { it.isNotBlank() && it != "null" },
            toolResult = obj.optString("toolResult").takeIf { it.isNotBlank() && it != "null" },
            attachments = attachments,
        )
    }
}
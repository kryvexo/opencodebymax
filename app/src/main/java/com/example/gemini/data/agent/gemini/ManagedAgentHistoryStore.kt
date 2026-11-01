package com.example.gemini.data.agent.gemini

import android.content.Context
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** Private app storage for managed-agent transcripts and remote session IDs; never stores API credentials. */
class ManagedAgentHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class Entry(
        val conversation: Conversation,
        val messages: List<ChatMessage>,
        val interactionId: String?,
        val environmentId: String?
    )

    @Synchronized
    fun loadAll(): List<Entry> = runCatching {
        val array = JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        (0 until array.length()).mapNotNull { index ->
            runCatching { decode(array.getJSONObject(index)) }.getOrNull()
        }.sortedByDescending { it.conversation.updatedAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun load(id: String): Entry? = loadAll().firstOrNull { it.conversation.id == id }

    @Synchronized
    fun save(entry: Entry) {
        val serialized = JSONObject()
            .put("id", entry.conversation.id)
            .put("title", entry.conversation.title)
            .put("modelId", entry.conversation.modelId)
            .put("providerId", entry.conversation.providerId)
            .put("sessionId", entry.conversation.sessionId)
            .put("createdAt", entry.conversation.createdAt)
            .put("updatedAt", entry.conversation.updatedAt)
            .put("workspaceUri", entry.conversation.workspaceUri)
            .put("parentConversationId", entry.conversation.parentConversationId)
            .put("interactionId", entry.interactionId)
            .put("environmentId", entry.environmentId)
            .put("messages", json.encodeToString(ListSerializer(ChatMessage.serializer()), entry.messages))
        val previous = runCatching { JSONArray(prefs.getString(KEY_ENTRIES, "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        next.put(serialized)
        for (index in 0 until previous.length()) {
            val item = previous.optJSONObject(index) ?: continue
            if (item.optString("id") != entry.conversation.id && next.length() < MAX_ENTRIES) next.put(item)
        }
        prefs.edit().putString(KEY_ENTRIES, next.toString()).apply()
    }

    @Synchronized
    fun delete(id: String) {
        val previous = runCatching { JSONArray(prefs.getString(KEY_ENTRIES, "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        for (index in 0 until previous.length()) {
            val item = previous.optJSONObject(index) ?: continue
            if (item.optString("id") != id) next.put(item)
        }
        prefs.edit().putString(KEY_ENTRIES, next.toString()).apply()
    }

    private fun decode(item: JSONObject): Entry {
        val id = item.getString("id")
        val messagesRaw = item.optString("messages", "[]")
        val messages = json.decodeFromString(ListSerializer(ChatMessage.serializer()), messagesRaw)
        val conversation = Conversation(
            id = id,
            title = item.optString("title", "Antigravity Chat"),
            modelId = item.optString("modelId", ""),
            providerId = item.optString("providerId", ""),
            sessionId = item.optString("sessionId", id),
            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
            workspaceUri = item.optString("workspaceUri", ""),
            parentConversationId = item.optString("parentConversationId").takeIf { it.isNotBlank() },
            agent = AgentKind.AGY
        )
        return Entry(
            conversation = conversation,
            messages = messages,
            interactionId = item.optString("interactionId").takeIf { it.isNotBlank() },
            environmentId = item.optString("environmentId").takeIf { it.isNotBlank() }
        )
    }

    companion object {
        private const val PREFS = "managed_agent_chat_history"
        private const val KEY_ENTRIES = "entries_v1"
        private const val MAX_ENTRIES = 150
    }
}

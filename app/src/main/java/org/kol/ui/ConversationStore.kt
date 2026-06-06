package org.kol.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Describes conversation record values.
 */
data class ConversationRecord(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Represents the conversation store component.
 */
class ConversationStore(context: Context) {
    private val conversationsDir = File(context.filesDir, "conversations").apply { mkdirs() }
    private val indexFile = File(conversationsDir, "index.json")
    private val legacyFile = File(context.filesDir, "conversations.json")

    /**
     * Loads index.
     * @return The load index result.
     */
    fun loadIndex(): List<ConversationRecord> {
        migrateLegacyStoreIfNeeded()
        if (!indexFile.exists()) return emptyList()
        val raw = indexFile.readText()
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                add(array.getJSONObject(index).toIndexRecord())
            }
        }
    }

    /**
     * Loads conversation.
     * @param conversationId Supplies the conversation id value.
     * @return The load conversation result.
     */
    fun loadConversation(conversationId: String): ConversationRecord? {
        migrateLegacyStoreIfNeeded()
        val file = conversationFile(conversationId)
        if (!file.exists()) return null
        return file.readText().takeIf { it.isNotBlank() }?.let { raw ->
            JSONObject(raw).toRecord()
        }
    }

    /**
     * Saves conversation.
     * @param record Supplies the record value.
     */
    fun saveConversation(record: ConversationRecord) {
        saveIndex(upsertIndex(record))
        conversationFile(record.id).writeText(record.toJson().toString())
    }

    /**
     * Handles delete conversation.
     * @param conversationId Supplies the conversation id value.
     */
    fun deleteConversation(conversationId: String) {
        conversationFile(conversationId).delete()
        saveIndex(loadIndex().filterNot { it.id == conversationId })
    }

    /**
     * Saves index.
     * @param records Supplies the records value.
     */
    fun saveIndex(records: List<ConversationRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(record.toIndexJson())
        }
        indexFile.writeText(array.toString())
    }

    private fun upsertIndex(record: ConversationRecord): List<ConversationRecord> {
        val current = loadIndex().filterNot { it.id == record.id }
        return listOf(record.copy(messages = emptyList())) + current
    }

    private fun conversationFile(conversationId: String): File =
        File(conversationsDir, "$conversationId.json")

    private fun migrateLegacyStoreIfNeeded() {
        if (indexFile.exists() || !legacyFile.exists()) return
        val raw = legacyFile.readText()
        if (raw.isBlank()) return
        val array = JSONArray(raw)
        val migrated = buildList {
            for (i in 0 until array.length()) {
                val record = array.getJSONObject(i).toRecord()
                conversationFile(record.id).writeText(record.toJson().toString())
                add(record.copy(messages = emptyList()))
            }
        }
        saveIndex(migrated)
    }

    private fun JSONObject.toRecord(): ConversationRecord {
        val messagesArray = optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until messagesArray.length()) {
                val msg = messagesArray.getJSONObject(index)
                add(
                    ChatMessage(
                        id = msg.getLong("id"),
                        isUser = msg.getBoolean("isUser"),
                        text = msg.getString("text"),
                        timestampMs = msg.optLong("timestampMs", System.currentTimeMillis()),
                        isStreaming = msg.optBoolean("isStreaming", false)
                    )
                )
            }
        }
        return ConversationRecord(
            id = getString("id"),
            title = optString("title").ifBlank { "Conversation" },
            messages = messages,
            lastUpdated = optLong("lastUpdated", System.currentTimeMillis())
        )
    }

    private fun JSONObject.toIndexRecord(): ConversationRecord = ConversationRecord(
        id = getString("id"),
        title = optString("title").ifBlank { "Conversation" },
        lastUpdated = optLong("lastUpdated", System.currentTimeMillis())
    )

    private fun ConversationRecord.toJson(): JSONObject {
        val messagesArray = JSONArray()
        messages.forEach { message ->
            messagesArray.put(
                JSONObject()
                    .put("id", message.id)
                    .put("isUser", message.isUser)
                    .put("text", message.text)
                    .put("timestampMs", message.timestampMs)
                    .put("isStreaming", message.isStreaming)
            )
        }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("messages", messagesArray)
            .put("lastUpdated", lastUpdated)
    }

    private fun ConversationRecord.toIndexJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("lastUpdated", lastUpdated)
}

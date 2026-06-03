package com.voiceassistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceassistant.VoiceAssistantEngine
import com.voiceassistant.ui.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val engine = VoiceAssistantEngine(app)
    private val store = ConversationStore(app)
    val state: StateFlow<VoiceAssistantEngine.State> = engine.state
    val transcript: StateFlow<String> = engine.transcript
    val response: StateFlow<String> = engine.response
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()
    private val _conversations = MutableStateFlow<List<ConversationRecord>>(emptyList())
    val conversations: StateFlow<List<ConversationRecord>> = _conversations.asStateFlow()
    private var activeConversationId: String = "default"
    private var activeConversationMeta: ConversationRecord? = null
    private var userMessageId = 1L
    private var assistantMessageId = 2L
    private var lastUserText = ""
    private var lastAssistantText = ""
    private var pendingSaveJob: Job? = null

    init {
        val loaded = store.loadIndex()
        if (loaded.isNotEmpty()) {
            _conversations.value = loaded
            val first = loaded.first()
            activeConversationId = first.id
            activeConversationMeta = first
            store.loadConversation(first.id)?.let { conversation ->
                _chatMessages.value = conversation.messages
                syncIdsFromMessages(conversation.messages)
            }
        } else {
            val record = ConversationRecord(id = activeConversationId, title = "New chat")
            activeConversationMeta = record
            _conversations.value = listOf(record)
        }
    }

    fun start() {
        engine.setPreferredLanguage(AppSettings.getLanguage(getApplication()))
        engine.initialize()
    }

    fun setLanguage(language: String?) {
        AppSettings.setLanguage(getApplication(), language)
        engine.setPreferredLanguage(language)
    }

    fun currentLanguage(): String? = AppSettings.getLanguage(getApplication())

    fun clearHistory() {
        engine.clearHistory()
        _chatMessages.value = emptyList()
        lastUserText = ""
        lastAssistantText = ""
        val record = ConversationRecord(id = activeConversationId, title = "New chat", messages = emptyList())
        saveConversation(record, immediate = true)
    }

    fun recordUserMessage(text: String) {
        if (text.isBlank() || text == lastUserText) return
        lastUserText = text
        val updated = _chatMessages.value + ChatMessage(
            id = userMessageId++,
            isUser = true,
            text = text
        )
        _chatMessages.value = updated
        saveConversation(currentRecord(updated))
    }

    fun updateAssistantMessage(text: String) {
        if (text.isBlank()) return
        lastAssistantText = text
        val current = _chatMessages.value.toMutableList()
        val existingIndex = current.indexOfLast { !it.isUser }
        if (existingIndex >= 0) {
            current[existingIndex] = current[existingIndex].copy(text = text, isStreaming = true)
        } else {
            current += ChatMessage(
                id = assistantMessageId++,
                isUser = false,
                text = text,
                isStreaming = true
            )
        }
        _chatMessages.value = current
        saveConversation(currentRecord(current))
    }

    fun finishAssistantMessage() {
        val current = _chatMessages.value.toMutableList()
        val existingIndex = current.indexOfLast { !it.isUser }
        if (existingIndex >= 0) {
            current[existingIndex] = current[existingIndex].copy(isStreaming = false)
            _chatMessages.value = current
            saveConversation(currentRecord(current), immediate = true)
        }
        lastAssistantText = ""
    }

    fun selectConversation(conversationId: String) {
        val meta = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        activeConversationId = meta.id
        activeConversationMeta = meta
        val loaded = store.loadConversation(conversationId)
        val messages = loaded?.messages ?: meta.messages
        _chatMessages.value = messages
        syncIdsFromMessages(messages)
    }

    fun conversationTitles(): List<Pair<String, String>> =
        _conversations.value.map { it.id to it.title }

    fun engineGetGemmaProvider(): String = engine.gemmaProvider()
    fun engineGetVadProvider(): String = engine.vadProvider()
    fun engineGetTtsProvider(): String = engine.ttsProvider()

    override fun onCleared() {
        pendingSaveJob?.cancel()
        engine.release()
    }

    private fun currentRecord(messages: List<ChatMessage>): ConversationRecord {
        return (activeConversationMeta ?: ConversationRecord(id = activeConversationId, title = "New chat"))
            .copy(
                title = titleFor(messages),
                messages = messages,
                lastUpdated = System.currentTimeMillis()
            )
    }

    private fun saveConversation(record: ConversationRecord, immediate: Boolean = false) {
        activeConversationMeta = record
        val others = _conversations.value.filterNot { it.id == record.id }
        _conversations.value = listOf(record.copy(messages = emptyList())) + others
        pendingSaveJob?.cancel()
        pendingSaveJob = viewModelScope.launch {
            if (!immediate) {
                delay(350)
            }
            val snapshot = _conversations.value
            withContext(Dispatchers.IO) {
                store.saveConversation(record)
                store.saveIndex(snapshot)
            }
        }
    }

    private fun titleFor(messages: List<ChatMessage>): String {
        return messages.firstOrNull { it.isUser }?.text?.take(30)?.ifBlank { "New chat" } ?: "New chat"
    }

    private fun syncIdsFromMessages(messages: List<ChatMessage>) {
        userMessageId = (messages.maxOfOrNull { it.id } ?: 0L) + 1
        assistantMessageId = userMessageId + 1
    }
}

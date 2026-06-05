package com.voiceassistant.ui

import android.content.Context
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

    data class VoiceOption(
        val id: Int,
        val code: String,
        val emoji: String,
        val meaning: String
    )

    data class TtsQualityOption(
        val steps: Int,
        val label: String,
        val meaning: String
    )

    val engine = VoiceAssistantEngine(app)
    private val store = ConversationStore(app)
    val state: StateFlow<VoiceAssistantEngine.State> = engine.state
    val transcript: StateFlow<String> = engine.transcript
    // Emits the moment a turn starts (placeholder "…" then real text) so user bubble
    // is always inserted before the first assistant token.
    val pendingUserTranscript: StateFlow<String> = engine.pendingUserTranscript
    val response: StateFlow<String> = engine.response
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()
    private val _conversations = MutableStateFlow<List<ConversationRecord>>(emptyList())
    val conversations: StateFlow<List<ConversationRecord>> = _conversations.asStateFlow()
    private var activeConversationId: String = "default"
    private var activeConversationMeta: ConversationRecord? = null
    private var userMessageId = 1L
    private var assistantMessageId = 2L
    private var activeAssistantMessageId: Long? = null
    private var lastUserText = ""
    private var lastAssistantText = ""
    private var pendingSaveJob: Job? = null
    private val _voiceId = MutableStateFlow(6)
    val voiceId: StateFlow<Int> = _voiceId
    private val _ttsSteps = MutableStateFlow(16)
    val ttsSteps: StateFlow<Int> = _ttsSteps

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
        loadVoiceId(getApplication())
        loadTtsSteps(getApplication())
    }

    fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.setPreferredLanguage(AppSettings.getLanguage(getApplication()))
            engine.initialize()
        }
    }

    fun setLanguage(language: String?) {
        AppSettings.setLanguage(getApplication(), language)
        engine.setPreferredLanguage(language)
    }

    fun currentLanguage(): String? = AppSettings.getLanguage(getApplication())

    fun cycleVoice(context: Context) {
        selectVoice(context, (_voiceId.value + 1) % 10)
    }

    fun loadVoiceId(context: Context) {
        _voiceId.value = AppSettings.getVoiceId(context)
    }

    fun loadTtsSteps(context: Context) {
        _ttsSteps.value = AppSettings.getTtsSteps(context)
    }

    fun voiceBadge(id: Int): String =
        voiceOption(id).emoji

    fun voiceMeaning(id: Int): String =
        voiceOption(id).meaning

    fun ttsQualityLabel(steps: Int): String =
        when (steps) {
            8 -> "⚡ 8"
            16 -> "🎚 16"
            24 -> "✨ 24"
            else -> "${steps}"
        }

    fun ttsQualityMeaning(steps: Int): String =
        ttsQualityOptions().firstOrNull { it.steps == steps }?.meaning ?: "TTS quality"

    fun ttsQualityOptions(): List<TtsQualityOption> = listOf(
        TtsQualityOption(8, "⚡ Fast", "Faster output, a little rougher"),
        TtsQualityOption(16, "🎚 Balanced", "Good quality and reasonable speed"),
        TtsQualityOption(24, "✨ High", "Cleaner speech, slower synthesis")
    )

    fun selectTtsQuality(context: Context, steps: Int) {
        val normalized = when (steps) {
            8, 16, 24 -> steps
            else -> 16
        }
        _ttsSteps.value = normalized
        AppSettings.setTtsSteps(context, normalized)
    }

    fun voiceOption(id: Int): VoiceOption = when (id.coerceIn(0, 9)) {
        0 -> VoiceOption(0, "F1", "👩", "Calm, slightly low tone, composed")
        1 -> VoiceOption(1, "F2", "👧", "Bright, cheerful, playful, youthful")
        2 -> VoiceOption(2, "F3", "👩‍💼", "Clear, professional, broadcast-ready")
        3 -> VoiceOption(3, "F4", "👩‍🦰", "Crisp, confident, expressive")
        4 -> VoiceOption(4, "F5", "👵", "Kind, gentle, soft-spoken, soothing")
        5 -> VoiceOption(5, "M1", "👦", "Lively, upbeat, confident")
        6 -> VoiceOption(6, "M2", "👨", "Deep, calm, serious")
        7 -> VoiceOption(7, "M3", "🧔", "Polished, authoritative, trustworthy")
        8 -> VoiceOption(8, "M4", "🧑", "Soft, neutral, youthful, friendly")
        else -> VoiceOption(9, "M5", "👴", "Warm, soft-spoken, storytelling quality")
    }

    fun voiceOptions(): List<VoiceOption> = listOf(
        voiceOption(0),
        voiceOption(1),
        voiceOption(2),
        voiceOption(3),
        voiceOption(4),
        voiceOption(5),
        voiceOption(6),
        voiceOption(7),
        voiceOption(8),
        voiceOption(9)
    )

    fun selectVoice(context: Context, id: Int) {
        val normalized = id.coerceIn(0, 9)
        _voiceId.value = normalized
        AppSettings.setVoiceId(context, normalized)
        // MultilingualTTS reads speakerId directly from AppSettings
        // on the next synthesis call — no engine restart needed.
        viewModelScope.launch(Dispatchers.IO) {
            engine.previewVoice()
        }
    }

    fun clearHistory() {
        engine.clearHistory()
        _chatMessages.value = emptyList()
        lastUserText = ""
        lastAssistantText = ""
        activeAssistantMessageId = null
        val record = ConversationRecord(id = activeConversationId, title = "New chat", messages = emptyList())
        saveConversation(record, immediate = true)
    }

    fun recordUserMessage(text: String) {
        if (text.isBlank() || text == lastUserText) return
        lastUserText = text
        val updated = _chatMessages.value + ChatMessage(
            id = userMessageId++,
            isUser = true,
            text = text,
            timestampMs = System.currentTimeMillis()
        )
        _chatMessages.value = updated
        saveConversation(currentRecord(updated))
    }

    fun updateAssistantMessage(text: String) {
        if (text.isBlank()) return
        lastAssistantText = text
        val current = _chatMessages.value.toMutableList()
        val existingIndex = activeAssistantMessageId?.let { id ->
            current.indexOfLast { it.id == id }
        } ?: -1
        if (existingIndex >= 0) {
            current[existingIndex] = current[existingIndex].copy(text = text, isStreaming = true)
        } else {
            val message = ChatMessage(
                id = assistantMessageId++,
                isUser = false,
                text = text,
                timestampMs = System.currentTimeMillis(),
                isStreaming = true
            )
            current += message
            activeAssistantMessageId = message.id
        }
        _chatMessages.value = current
        saveConversation(currentRecord(current))
    }

    fun finishAssistantMessage() {
        val current = _chatMessages.value.toMutableList()
        val existingIndex = activeAssistantMessageId?.let { id ->
            current.indexOfLast { it.id == id }
        } ?: -1
        if (existingIndex >= 0) {
            current[existingIndex] = current[existingIndex].copy(isStreaming = false)
            _chatMessages.value = current
            saveConversation(currentRecord(current), immediate = true)
        }
        lastAssistantText = ""
        activeAssistantMessageId = null
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
        activeAssistantMessageId = messages.lastOrNull { !it.isUser && it.isStreaming }?.id
    }

    companion object {
        fun voiceLabel(id: Int): String =
            if (id < 5) "M${id + 1}" else "F${id - 4}"
    }
}

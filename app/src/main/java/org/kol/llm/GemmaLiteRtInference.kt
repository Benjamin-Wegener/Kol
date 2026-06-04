package com.voiceassistant.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.voiceassistant.ModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GemmaLiteRtInference(private val context: Context) {

    private val tag = "GemmaLiteRtInference"
    private val cancelRequested = AtomicBoolean(false)
    private val modelPath = ModelConfig.modelFile(context, ModelConfig.GEMMA_LITERTLM).absolutePath

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val conversationMutex = Mutex()
    @Volatile
    private var responseLanguage: String = "en"
    @Volatile
    private var activeBackendLabel: String = "uninitialized"
    @Volatile
    private var preferredLanguage: String? = null
    @Volatile
    private var conversationResetRequested = false

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (engine != null && conversation != null) return@withContext

        val backendAttempts = listOf(
            "gpu" to Backend.GPU(),
            "cpu" to Backend.CPU()
        )

        var lastError: Throwable? = null
        for ((label, backend) in backendAttempts) {
            try {
                enableSpeculativeDecoding()
                Log.d(tag, "Initializing Gemma LiteRT-LM with backend=$label model=$modelPath")
                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    audioBackend = Backend.CPU(),
                    maxNumTokens = ModelConfig.GEMMA_MAX_TOKENS,
                    cacheDir = context.cacheDir.absolutePath
                )
                val newEngine = Engine(engineConfig)
                newEngine.initialize()
                val newConversation = newEngine.createConversation(conversationConfig())
                engine = newEngine
                conversation = newConversation
                activeBackendLabel = label
                Log.d(tag, "Gemma LiteRT-LM initialized with backend=$label: $modelPath")
                Log.d(tag, "GEMMA SYSTEM PROMPT: ${ModelConfig.systemPromptForLog(preferredLanguage)}")
                return@withContext
            } catch (throwable: Throwable) {
                lastError = throwable
                Log.w(tag, "Gemma LiteRT-LM init failed with backend=$label")
                try {
                    engine?.close()
                } catch (_: Exception) { }
                engine = null
                conversation = null
            }
        }

        throw RuntimeException(
            "Failed to initialize Gemma LiteRT-LM with all backends",
            lastError
        )
    }

    @OptIn(ExperimentalApi::class)
    private fun enableSpeculativeDecoding() {
        ExperimentalFlags.enableSpeculativeDecoding = true
        Log.d(tag, "LiteRT-LM speculative decoding enabled")
    }

    suspend fun generateFromAudio(
        audioWavBytes: ByteArray,
        onToken: (String) -> Unit,
        onUserText: (String) -> Unit,
        onDone: () -> Unit,
        onLanguage: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        responseLanguage = "en"

        val rawBuffer = StringBuilder()
        var emittedVisibleLength = 0
        var completed = false
        Log.d(tag, "GEMMA AUDIO INPUT BYTES=${audioWavBytes.size}")
        Log.d(tag, "GEMMA AUDIO INPUT PROMPT: [audio bytes only; system prompt logged at init]")
        conversationMutex.withLock {
            val currentConversation = ensureLiveConversationLocked()
            if (currentConversation == null) {
                onDone()
                return@withLock
            }
            suspendCoroutine<Unit> { continuation ->
                currentConversation.sendMessageAsync(
                    Contents.of(Content.AudioBytes(audioWavBytes)),
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (cancelRequested.get() || completed) return
                            val thought = message.channels["thought"]
                            if (!thought.isNullOrBlank()) {
                                Log.d(tag, "Dropping hidden thought channel length=${thought.length}")
                            }
                            val chunk = message.toString()
                            rawBuffer.append(chunk)
                            Log.d(tag, "RAW GEMMA CHUNK: ${chunk.take(120)}")
                            Log.d(tag, "RAW GEMMA BUFFER: ${rawBuffer.toString().take(300)}")
                            val parsed = parseVisibleResponse(rawBuffer.toString())
                            if (parsed.language != null && parsed.language != responseLanguage) {
                                responseLanguage = parsed.language
                                onLanguage(responseLanguage)
                            }
                            if (parsed.userText != null) {
                                Log.d(tag, "RAW GEMMA USER TEXT: ${parsed.userText}")
                                onUserText(parsed.userText)
                            }
                            if (parsed.visibleText.length > emittedVisibleLength) {
                                val delta = parsed.visibleText.substring(emittedVisibleLength)
                                emittedVisibleLength = parsed.visibleText.length
                                if (delta.isNotBlank()) {
                                    onToken(delta)
                                }
                            }
                        }

                        override fun onDone() {
                            if (completed) return
                            completed = true
                            val parsed = parseVisibleResponse(rawBuffer.toString())
                            Log.d(tag, "RAW GEMMA FINAL: ${rawBuffer.toString().take(1000)}")
                            if (parsed.language != null && parsed.language != responseLanguage) {
                                responseLanguage = parsed.language
                                onLanguage(responseLanguage)
                            }
                            if (parsed.userText != null) {
                                Log.d(tag, "RAW GEMMA FINAL USER TEXT: ${parsed.userText}")
                                onUserText(parsed.userText)
                            }
                            if (parsed.visibleText.length > emittedVisibleLength) {
                                val delta = parsed.visibleText.substring(emittedVisibleLength)
                                emittedVisibleLength = parsed.visibleText.length
                                if (delta.isNotBlank()) {
                                    onToken(delta)
                                }
                            }
                            onDone()
                            Log.d(tag, "RAW LLM FINAL: ${rawBuffer.toString().take(500)}")
                            continuation.resume(Unit)
                        }

                        override fun onError(throwable: Throwable) {
                            if (completed) return
                            completed = true
                            Log.e(tag, "Gemma inference failed", throwable)
                            onDone()
                            continuation.resume(Unit)
                        }
                    },
                    emptyMap()
                )
            }
        }
    }

    suspend fun generateFromText(
        prompt: String,
        onToken: (String) -> Unit,
        onLanguage: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        responseLanguage = "en"

        val rawBuffer = StringBuilder()
        var emittedVisibleLength = 0
        var completed = false
        Log.d(tag, "GEMMA TEXT PROMPT: $prompt")

        conversationMutex.withLock {
            val currentConversation = ensureLiveConversationLocked() ?: return@withLock
            suspendCoroutine<Unit> { continuation ->
                currentConversation.sendMessageAsync(
                    Contents.of(Content.Text(prompt)),
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (cancelRequested.get() || completed) return
                            val chunk = message.toString()
                            rawBuffer.append(chunk)
                            Log.d(tag, "RAW GEMMA CHUNK: ${chunk.take(120)}")
                            val parsed = parseVisibleResponse(rawBuffer.toString())
                            Log.d(tag, "RAW GEMMA BUFFER: ${rawBuffer.toString().take(300)}")
                            if (parsed.language != null && parsed.language != responseLanguage) {
                                responseLanguage = parsed.language
                                onLanguage(responseLanguage)
                            }
                            if (parsed.visibleText.length > emittedVisibleLength) {
                                val delta = parsed.visibleText.substring(emittedVisibleLength)
                                emittedVisibleLength = parsed.visibleText.length
                                if (delta.isNotBlank()) {
                                    onToken(delta)
                                }
                            }
                        }

                        override fun onDone() {
                            if (completed) return
                            completed = true
                            val parsed = parseVisibleResponse(rawBuffer.toString())
                            Log.d(tag, "RAW GEMMA FINAL: ${rawBuffer.toString().take(1000)}")
                            if (parsed.language != null && parsed.language != responseLanguage) {
                                responseLanguage = parsed.language
                                onLanguage(responseLanguage)
                            }
                            if (parsed.visibleText.length > emittedVisibleLength) {
                                val delta = parsed.visibleText.substring(emittedVisibleLength)
                                emittedVisibleLength = parsed.visibleText.length
                                if (delta.isNotBlank()) {
                                    onToken(delta)
                                }
                            }
                            continuation.resume(Unit)
                        }

                        override fun onError(throwable: Throwable) {
                            if (completed) return
                            completed = true
                            Log.e(tag, "Gemma inference failed", throwable)
                            continuation.resume(Unit)
                        }
                    },
                    emptyMap()
                )
            }
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        conversation?.cancelProcess()
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        conversationMutex.withLock {
            recreateConversationLocked()
        }
    }

    private fun ensureLiveConversationLocked(): Conversation? {
        val currentConversation = conversation
        if (!conversationResetRequested && currentConversation != null && currentConversation.isAlive) {
            return currentConversation
        }
        if (conversationResetRequested) {
            Log.d(tag, "Recreating conversation because reset was requested")
        } else {
            Log.w(tag, "Recreating conversation because current conversation is not alive")
        }
        return recreateConversationLocked()
    }

    private fun recreateConversationLocked(): Conversation? {
        val currentEngine = engine ?: return null
        try {
            conversation?.close()
        } catch (_: Exception) { }
        val newConversation = currentEngine.createConversation(conversationConfig())
        conversation = newConversation
        conversationResetRequested = false
        return newConversation
    }

    private fun conversationConfig(): ConversationConfig {
        return ConversationConfig(
            systemInstruction = Contents.of(ModelConfig.systemPrompt(preferredLanguage)),
            samplerConfig = SamplerConfig(
                topK = ModelConfig.GEMMA_TOP_K,
                topP = ModelConfig.GEMMA_TOP_P.toDouble(),
                temperature = ModelConfig.GEMMA_TEMPERATURE.toDouble()
            )
        )
    }

    fun setPreferredLanguage(languageCode: String?) {
        val normalized = languageCode?.takeIf { it.isNotBlank() && it != "default" && it != "auto" }
        if (preferredLanguage == normalized) return
        preferredLanguage = normalized
        if (engine != null) {
            conversationResetRequested = true
        }
    }

    fun release() {
        try {
            conversation?.close()
        } catch (_: Exception) { }
        try {
            engine?.close()
        } catch (_: Exception) { }
        conversation = null
        engine = null
    }

    fun backend(): String = activeBackendLabel
    fun lastResponseLanguage(): String = responseLanguage

    private data class ParsedResponse(
        val userText: String?,
        val language: String?,
        val visibleText: String,
        val waitingForPrefixCompletion: Boolean
    )

    private fun parseVisibleResponse(text: String): ParsedResponse {
        val withoutThinking = text
            .replace(Regex("(?is)<think>.*?</think>"), " ")
            .trimStart()

        // Consume any leading [lang=xx] / lang=xx tags FIRST so that a model that emits
        // "[lang=de][user=hello]Hallo" is handled correctly.  The leading-lang result is
        // kept as a fallback in case no later lang tag is found.
        val leadingConsumed = consumeLeadingLanguageTags(withoutThinking)
        val leadingLanguage = leadingConsumed.language
        val afterLeadingLang = leadingConsumed.remaining

        // Match [user=...] anchored to the START of the text that remains after the leading lang
        // tags have been removed.  The ^ anchor is preserved – we do NOT allow [user=...] to
        // match later inside assistant-visible text.
        val userMatch = Regex("^\\[user=([^\\]]*)\\]").find(afterLeadingLang)
        val afterUser = if (userMatch != null) {
            afterLeadingLang.removeRange(userMatch.range).trimStart()
        } else {
            afterLeadingLang
        }

        // Consume language tags that follow the [user=...] prefix (or that appear right after the
        // leading lang when there was no user tag).  If none are present, fall back to the leading
        // language detected above so language detection is never lost.
        val consumedLanguageTags = consumeLeadingLanguageTags(afterUser)
        val language = consumedLanguageTags.language ?: leadingLanguage

        // waiting: either the lang-consumer says it’s mid-tag, or we have an open bracket at the
        // very start of the still-to-parse text that hasn’t been closed yet.
        val waitingForPrefixCompletion = consumedLanguageTags.waitingForTag ||
            leadingConsumed.waitingForTag ||
            (language == null && withoutThinking.startsWith("[") && !withoutThinking.contains("]"))

        val userText = userMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val visibleCandidate = when {
            language != null -> consumedLanguageTags.remaining
            waitingForPrefixCompletion -> ""
            afterUser.startsWith("[lang") -> ""
            else -> afterUser
        }
        val visible = stripLeadingBracketNoise(visibleCandidate)
        return ParsedResponse(userText, language, visible, waitingForPrefixCompletion)
    }

    private fun stripLeadingBracketNoise(text: String): String {
        var current = text.trimStart()
        while (current.startsWith("[")) {
            val closingBracket = current.indexOf(']')
            if (closingBracket < 0) {
                return ""
            }
            current = current.substring(closingBracket + 1).trimStart()
        }
        return current
    }

    private data class ConsumedLanguageTags(
        val language: String?,
        val remaining: String,
        val waitingForTag: Boolean
    )

    private fun consumeLeadingLanguageTags(text: String): ConsumedLanguageTags {
        var current = text.trimStart()
        var language: String? = null
        var consumedAny = false
        val completeTag = Regex(
            "^\\s*\\[?\\s*lang\\s*=?\\s*([a-zA-Z]{2,3})(?:\\s*\\])?(?=\\s|$|[A-ZÄÖÜ])",
            RegexOption.IGNORE_CASE
        )

        while (true) {
            val match = completeTag.find(current) ?: break
            language = match.groupValues[1].lowercase()
            current = current.substring(match.range.last + 1).trimStart()
            consumedAny = true
        }

        val waitingForTag = consumedAny &&
            Regex("^\\s*\\[?\\s*lang(?:\\s*=\\s*)?[a-zA-Z]{0,3}\\s*$", RegexOption.IGNORE_CASE)
                .matches(current)

        return ConsumedLanguageTags(
            language = language,
            remaining = if (waitingForTag) "" else current,
            waitingForTag = waitingForTag
        )
    }
}

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
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.voiceassistant.ModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GemmaLiteRtInference(private val context: Context) {

    private val tag = "GemmaLiteRtInference"
    private val cancelRequested = AtomicBoolean(false)
    private val modelPath = ModelConfig.modelFile(context, ModelConfig.GEMMA_LITERTLM).absolutePath

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile
    private var responseLanguage: String = "en"
    @Volatile
    private var activeBackendLabel: String = "uninitialized"
    @Volatile
    private var preferredLanguage: String? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (engine != null && conversation != null) return@withContext

        val backendAttempts = listOf(
            "gpu" to Backend.GPU(),
            "cpu" to Backend.CPU()
        )

        var lastError: Throwable? = null
        for ((label, backend) in backendAttempts) {
            try {
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
                val newConversation = newEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(ModelConfig.systemPrompt(preferredLanguage)),
                        samplerConfig = SamplerConfig(
                            topK = ModelConfig.GEMMA_TOP_K,
                            topP = ModelConfig.GEMMA_TOP_P.toDouble(),
                            temperature = ModelConfig.GEMMA_TEMPERATURE.toDouble()
                        )
                    )
                )
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

    suspend fun generateFromAudio(
        audioWavBytes: ByteArray,
        onToken: (String) -> Unit,
        onUserText: (String) -> Unit,
        onDone: () -> Unit,
        onLanguage: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        responseLanguage = "en"

        val currentConversation = conversation
        if (currentConversation == null) {
            onDone()
            return@withContext
        }

        val rawBuffer = StringBuilder()
        var emittedVisibleLength = 0
        var completed = false
        Log.d(tag, "GEMMA AUDIO INPUT BYTES=${audioWavBytes.size}")
        Log.d(tag, "GEMMA AUDIO INPUT PROMPT: [audio bytes only; system prompt logged at init]")
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

    suspend fun generateFromText(
        prompt: String,
        onToken: (String) -> Unit,
        onLanguage: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        responseLanguage = "en"

        val currentConversation = conversation ?: return@withContext
        val rawBuffer = StringBuilder()
        var emittedVisibleLength = 0
        var completed = false
        Log.d(tag, "GEMMA TEXT PROMPT: $prompt")

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

    fun cancel() {
        cancelRequested.set(true)
        conversation?.cancelProcess()
    }

    fun clearHistory() {
        val currentEngine = engine ?: return
        try {
            conversation?.close()
        } catch (_: Exception) { }
        conversation = currentEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(ModelConfig.systemPrompt(preferredLanguage)),
                samplerConfig = SamplerConfig(
                    topK = ModelConfig.GEMMA_TOP_K,
                    topP = ModelConfig.GEMMA_TOP_P.toDouble(),
                    temperature = ModelConfig.GEMMA_TEMPERATURE.toDouble()
                )
            )
        )
    }

    fun setPreferredLanguage(languageCode: String?) {
        val normalized = languageCode?.takeIf { it.isNotBlank() && it != "default" && it != "auto" }
        if (preferredLanguage == normalized) return
        preferredLanguage = normalized
        if (engine != null) {
            clearHistory()
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

        val userMatch = Regex("^\\[user=([^\\]]*)\\]").find(withoutThinking)
        val afterUser = if (userMatch != null) {
            withoutThinking.removeRange(userMatch.range).trimStart()
        } else {
            withoutThinking
        }

        val markerMatch = Regex("^\\[lang=([a-zA-Z-]{2,8})\\]").find(afterUser)
        val language = markerMatch?.groupValues?.getOrNull(1)?.lowercase()
        val waitingForPrefixCompletion = markerMatch == null &&
            withoutThinking.startsWith("[") &&
            !withoutThinking.contains("]")
        val userText = userMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val visibleCandidate = when {
            markerMatch != null -> afterUser.removeRange(markerMatch.range).trimStart()
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
}

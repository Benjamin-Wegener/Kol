package org.kol

import android.content.Context
import android.util.Log
import org.kol.audio.AudioCapture
import org.kol.audio.AudioPlayer
import org.kol.audio.VadDetector
import org.kol.llm.LiteRtNativeLoader
import org.kol.llm.GemmaLiteRtInference
import org.kol.ui.AppSettings
import org.kol.stt.WhisperTranscriber

import org.kol.tts.MultilingualTTS
import org.kol.tts.TtsTurnCoordinator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the full pipeline:
 *
 *   Mic → VAD → Gemma 4 E2B (LiteRT-LM) →
 *   SentenceBatcher → multilingual TTS → Speaker
 *
 * Barge-in: VAD fires during TTS playback → cancel LLM + flush audio → new utterance
 */
class VoiceAssistantEngine(private val context: Context) {

    private val TAG = "VoiceAssistantEngine"
    private val scope = CoroutineScope(Dispatchers.IO)

    // ── State ──────────────────────────────────────────────────────────────────

    /**
     * Handles state.
     */
    sealed class State {
        object Idle : State()
        object Listening : State()
        object Understanding : State()
        data class Thinking(val partial: String) : State()
        data class Speaking(val text: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript

    private val _response = MutableStateFlow("")
    val response: StateFlow<String> = _response

    // ── Components (lazy init after models are ready) ──────────────────────────

    private var vad: VadDetector? = null
    private var whisper: WhisperTranscriber? = null
    private var gemma: GemmaLiteRtInference? = null
    private var kokoro: MultilingualTTS? = null
    private var player: AudioPlayer? = null
    private var capture: AudioCapture? = null
    private var ttsUnavailable = false
    @Volatile
    private var preferredLanguageCode: String? = null
    private val whisperMutex = Mutex()
    private val gemmaMutex = Mutex()
    private val ttsMutex = Mutex()
    private val activeTurn = AtomicBoolean(false)
    private val llmSentenceQueue = Channel<FloatArray>(Channel.UNLIMITED)
    private val ttsSentenceQueue = Channel<TtsQueueItem>(Channel.UNLIMITED)
    private val ttsFeedLock = Any()
    private val ttsFeedBuffer = StringBuilder()
    private var ttsFeedTokenCount = 0
    private var ttsFeedStarted = false
    private val ttsTurnCoordinator = TtsTurnCoordinator()
    private val ttsInitialWordCap = 18
    private val ttsRollingWordCap = 24
    private val ttsInitialCharCap = 140
    private val ttsRollingCharCap = 180

    private fun resetTtsFeedBuffer() {
        synchronized(ttsFeedLock) {
            ttsFeedBuffer.clear()
            ttsFeedTokenCount = 0
            ttsFeedStarted = false
        }
    }

    private fun beginTtsTurn(turnId: Long, language: String) {
        synchronized(ttsFeedLock) {
            ttsTurnCoordinator.begin(turnId, language)
            ttsFeedBuffer.clear()
            ttsFeedTokenCount = 0
            ttsFeedStarted = false
        }
    }

    private fun updateTtsTurnLanguage(language: String) {
        ttsTurnCoordinator.updateLanguage(language)
    }

    /**
     * Removes thinking markup and leading lang-tag noise while preserving all whitespace so that
     * token spacing like " ist" is not destroyed.  Unlike stripThinkingMarkup() this helper does
     * NOT call trim() or collapse internal spaces.
     */
    private fun cleanTokenForBuffer(token: String): String {
        // Drop any complete <think>…</think> block but leave surrounding whitespace intact
        var t = token.replace(Regex("(?is)<think>.*?</think>"), "")
        // Drop standalone open/close think tags
        t = t.replace(Regex("(?im)^\\s*</?think>\\s*$"), "")
        t = t.replace(Regex("(?i)\\[/?think]"), "")
        // If an unclosed <think> starts here the whole token is markup – suppress it
        if (t.contains(Regex("(?i)<think")) && !t.contains(Regex("(?i)</think>"))) return ""
        // Strip a leading [lang=xx] / lang=xx / langXX noise tag that sometimes arrives as the
        // very first token.  The regex is anchored and does NOT collapse the rest of the string.
        t = t.replace(Regex("^\\[?lang\\s*=?\\s*[a-z]{2,3}\\s*]?", RegexOption.IGNORE_CASE), "")
        return t
    }

    private fun feedTokenToTts(token: String) {
        val cleanToken = cleanTokenForBuffer(token)
        // A token that becomes empty after stripping markup should be skipped entirely
        if (cleanToken.isEmpty()) return
        synchronized(ttsFeedLock) {
            // Keep the first assistant words moving quickly: if the model starts a new word
            // after we've already buffered enough text, flush the current chunk before appending.
            if (cleanToken.firstOrNull()?.isWhitespace() == true && shouldFlushAtWordBoundaryLocked()) {
                flushTtsFeedBufferLocked(final = false)
            }

            ttsFeedBuffer.append(cleanToken)
            ttsFeedTokenCount += 1

            // Flush as soon as we have a complete sentence prefix so the user hears the
            // answer start without waiting for the full completion.
            flushCompleteSentencePrefixesLocked()

            if (shouldFlushAfterTokenLocked()) {
                flushTtsFeedBufferLocked(final = false)
            }
        }
    }

    private fun shouldFlushAtWordBoundaryLocked(): Boolean {
        val current = ttsFeedBuffer.toString()
        val trimmed = current.trim()
        if (trimmed.length < 12) return false
        val words = countWords(trimmed)
        val wordCap = if (ttsFeedStarted) ttsRollingWordCap else ttsInitialWordCap
        val charCap = if (ttsFeedStarted) ttsRollingCharCap else ttsInitialCharCap
        return words >= wordCap || trimmed.length >= charCap
    }

    private fun shouldFlushAfterTokenLocked(): Boolean {
        val current = ttsFeedBuffer.toString()
        val trimmed = current.trim()
        val lastChar = trimmed.lastOrNull() ?: return false
        val sentenceEnding = lastChar in setOf('.', '?', '!', '。', '？', '！', '…')
        val clauseEnding = lastChar in setOf(',', ':', ';')
        val readyBySentence = sentenceEnding && trimmed.length >= 3 && trimmed.any { it.isLetterOrDigit() }
        val readyByClause = clauseEnding && trimmed.length >= 18
        return readyBySentence || readyByClause
    }

    private fun flushTtsFeedBufferLocked(final: Boolean) {
        val chunk = if (final) {
            cleanTtsChunk(stripThinkingMarkup(ttsFeedBuffer.toString()))
        } else {
            cleanTtsChunk(ttsFeedBuffer.toString())
        }
        enqueueTtsChunkLocked(chunk = chunk)
        ttsFeedBuffer.clear()
        ttsFeedTokenCount = 0
    }

    private fun flushCompleteSentencePrefixesLocked() {
        while (true) {
            val endIndex = firstCompleteSentencePrefixEndIndex(ttsFeedBuffer.toString())
            if (endIndex < 0) return

            val prefix = cleanTtsChunk(ttsFeedBuffer.substring(0, endIndex + 1))
            ttsFeedBuffer.delete(0, endIndex + 1)
            ttsFeedTokenCount = countWords(ttsFeedBuffer.toString())

            enqueueTtsChunkLocked(chunk = prefix)
        }
    }

    private fun firstCompleteSentencePrefixEndIndex(text: String): Int {
        val sentenceEndings = setOf('.', '?', '!', '。', '？', '！', '…')
        for (index in text.indices) {
            val char = text[index]
            if (char !in sentenceEndings) continue
            val nextChar = text.getOrNull(index + 1) ?: continue
            if (!nextChar.isWhitespace()) continue
            val prefix = cleanTtsChunk(text.substring(0, index + 1))
            if (prefix.length < 3 || !prefix.any { it.isLetterOrDigit() }) continue
            if (text.substring(index + 1).isNotBlank()) return index
        }
        return -1
    }

    private fun enqueueTtsChunkLocked(chunk: String) {
        if (chunk.isNotBlank()) {
            val turn = ttsTurnCoordinator.current() ?: return
            val queued = ttsSentenceQueue.trySend(
                TtsQueueItem.Sentence(
                    turnId = turn.turnId,
                    ticket = turn.ticket,
                    language = turn.language,
                    text = chunk
                )
            ).isSuccess
            if (queued) {
                ttsTurnCoordinator.markChunkQueued()
                ttsFeedStarted = true
            } else {
                Log.e(TAG, "turn#${turn.turnId} failed to enqueue TTS chunk")
            }
        }
    }

    private fun completeTtsTurn(responseText: String) {
        synchronized(ttsFeedLock) {
            flushTtsFeedBufferLocked(final = true)
            if (ttsTurnCoordinator.needsFullResponseFallback(responseText)) {
                val turnId = ttsTurnCoordinator.current()?.turnId ?: 0L
                val fallback = cleanTtsChunk(stripThinkingMarkup(responseText))
                if (fallback.isNotBlank()) {
                    Log.w(TAG, "turn#$turnId token batching queued no speech; using full-response fallback")
                    enqueueTtsChunkLocked(chunk = fallback)
                }
            }
            val turn = ttsTurnCoordinator.current() ?: return
            ttsSentenceQueue.trySend(
                TtsQueueItem.EndOfTurn(
                    turnId = turn.turnId,
                    ticket = turn.ticket
                )
            )
            ttsFeedStarted = false
        }
    }

    private fun cleanTtsChunk(text: String): String {
        return text
            .replace(Regex("(?i)^\\s*\\]?\\s*\\[?\\s*lang\\s*=?\\s*[a-z]{2,3}\\s*]?\\s*"), "")
            .replace(Regex("(?i)^\\s*[,;:]*\\s*(?:\\[\\s*\\]\\s*)*(?:\\[?\\s*lang\\s*=?\\s*[a-z]{2,3}\\s*]?\\s*)+"), "")
            .replace(Regex("^\\s*[,;:]*\\s*(?:\\[\\s*\\]\\s*)*]+\\s*"), "")
            .replace(Regex("^\\s*[,;:]*\\s*(?:\\[\\s*\\]\\s*)+"), "")
            .trim()
    }

    private fun countWords(text: String): Int {
        return Regex("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*")
            .findAll(text)
            .count()
    }

    private var detectedLanguage = "en"
    private var turnCounter = 0L
    private sealed class TtsQueueItem {
        data class Sentence(
            val turnId: Long,
            val ticket: Long,
            val language: String,
            val text: String
        ) : TtsQueueItem()

        data class EndOfTurn(
            val turnId: Long,
            val ticket: Long
        ) : TtsQueueItem()
    }

    private suspend fun ensureWhisper(): WhisperTranscriber? {
        val current = whisper
        if (current != null) return current
        return whisperMutex.withLock {
            val lockedCurrent = whisper
            if (lockedCurrent != null) return@withLock lockedCurrent
            try {
                WhisperTranscriber(context).also {
                    it.initialize()
                    whisper = it
                }
            } catch (e: Exception) {
                Log.w(TAG, "Whisper unavailable on demand; continuing without STT", e)
                null
            }
        }
    }

    private suspend fun ensureGemma(): GemmaLiteRtInference? {
        val current = gemma
        if (current != null) return current
        return gemmaMutex.withLock {
            val lockedCurrent = gemma
            if (lockedCurrent != null) return@withLock lockedCurrent
            try {
                LiteRtNativeLoader.ensureLoaded()
                GemmaLiteRtInference(context).also {
                    it.setPreferredLanguage(preferredLanguageCode)
                    it.initialize()
                    gemma = it
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemma LiteRT-LM unavailable on demand; continuing without LLM", e)
                null
            }
        }
    }

    private suspend fun ensureTts(): MultilingualTTS? {
        val current = kokoro
        if (current != null) return current
        if (ttsUnavailable) return null
        return ttsMutex.withLock {
            val lockedCurrent = kokoro
            if (lockedCurrent != null) return@withLock lockedCurrent
            if (ttsUnavailable) return@withLock null
            try {
                MultilingualTTS(context).also { kokoro = it }
            } catch (e: Exception) {
                ttsUnavailable = true
                Log.w(TAG, "Multilingual TTS unavailable on demand; continuing without TTS", e)
                null
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Handles initialize.
     */
    fun initialize() {
        scope.launch {
            try {
                vad     = VadDetector(context)
                player  = AudioPlayer().also { it.start() }

                capture = AudioCapture(
                    vad = vad!!,
                    onUtterance    = ::onUtteranceReady,
                    onUtteranceSegment = ::onUtteranceSegmentReady,
                    onSpeechStart  = ::onSpeechStart,
                    onSpeechEnd    = ::onSpeechEnd
                )

                scope.launch {
                    drainTtsQueue()
                }

                val whisperWarmup = async(Dispatchers.IO) {
                    ensureWhisper()
                }

                val gemmaWarmup = async(Dispatchers.IO) {
                    ensureGemma()
                }

                val ttsWarmup = async(Dispatchers.IO) {
                    ensureTts()?.warmUp(preferredLanguageCode ?: "de")
                }

                awaitAll(whisperWarmup, gemmaWarmup, ttsWarmup)

                _state.value = State.Listening
                capture!!.start()
            } catch (e: Exception) {
                Log.e(TAG, "Init failed", e)
                _state.value = State.Error("Failed to load models: ${e.message}")
            }
        }
    }

    private fun onUtteranceSegmentReady(samples: FloatArray) {
        if (samples.isNotEmpty()) {
            // Keep the legacy segment callback for now, but do not let it start a turn.
        }
    }

    private fun onSpeechStart() {
        // User started speaking — if we're currently speaking, barge in
        if (_state.value is State.Speaking) {
            bargeIn()
        }
        _state.value = State.Listening
    }

    private fun onSpeechEnd() {
        _state.value = State.Understanding
    }

    private fun onUtteranceReady(samples: FloatArray) {
        if (!activeTurn.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            try {
                processUtterance(samples)
            } finally {
                activeTurn.set(false)
            }
        }
    }

    private suspend fun drainLlmQueue() {
        for (samples in llmSentenceQueue) {
            try {
                processUtterance(samples)
            } finally {
                activeTurn.set(false)
            }
        }
    }

    private suspend fun processUtterance(samples: FloatArray) {
        try {
            turnCounter += 1
            val turnId = turnCounter
            _state.value = State.Understanding
            _transcript.value = ""
            _response.value = ""
            resetTtsFeedBuffer()
            val currentWhisper = ensureWhisper()
            if (currentWhisper == null) {
                Log.w(TAG, "Whisper unavailable on demand")
                _state.value = State.Error("repair_required:whisper")
                return
            }

            val transcription = currentWhisper.transcribe(samples)
            val userText = transcription.text.trim()
            if (userText.isBlank()) {
                _state.value = State.Listening
                return
            }

            _transcript.value = userText
            detectedLanguage = preferredLanguageCode
                ?: transcription.language
                ?: detectedLanguage
            beginTtsTurn(turnId, detectedLanguage)

            val currentGemma = ensureGemma()
            if (currentGemma == null) {
                Log.w(TAG, "Gemma LiteRT-LM unavailable on demand; keeping existing model file and continuing without LLM")
                _state.value = State.Error("repair_required:gemma")
                return
            }

            _state.value = State.Thinking("")
            try {
                currentGemma.generateFromText(
                    prompt = userText,
                    inputLanguage = transcription.language,
                    onToken = { token ->
                        val current = _response.value + token
                        _response.value = current
                        _state.value = State.Thinking(current)
                        feedTokenToTts(token)
                    },
                    onLanguage = { language ->
                        detectedLanguage = preferredLanguageCode ?: language
                        updateTtsTurnLanguage(detectedLanguage)
                    }
                )
            } finally {
                completeTtsTurn(_response.value)
            }
            if (_state.value !is State.Speaking) {
                _state.value = State.Listening
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            _state.value = State.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Handles set preferred language.
     * @param languageCode TODO(me 5): document this parameter.
     */
    fun setPreferredLanguage(languageCode: String?) {
        preferredLanguageCode = languageCode?.takeIf { it.isNotBlank() && it != "default" && it != "auto" }
        gemma?.setPreferredLanguage(preferredLanguageCode)
        detectedLanguage = preferredLanguageCode ?: detectedLanguage
    }

    /**
     * Returns preferred language.
     * @return preferred language result.
     */
    fun preferredLanguage(): String? = preferredLanguageCode

    /**
     * Synthesize a sentence on IO thread, enqueue audio chunks to player.
     * Runs in parallel with LLM generating the next sentence.
     */
    private suspend fun synthesizeAndPlay(sentence: String) {
        val spokenSentence = stripThinkingMarkup(sentence)
        if (spokenSentence.isBlank()) {
            return
        }
        if (spokenSentence != sentence) {
        }
        try {
            val ttsEngine = ensureTts()
            if (ttsEngine == null) {
                Log.w(TAG, "Skipping TTS because multilingual TTS is unavailable")
                _state.value = State.Listening
                return
            }
            applyTtsSettings(ttsEngine)
            val spokenLanguage = preferredLanguageCode ?: detectedLanguage
            val samples = ttsEngine.synthesize(spokenSentence, spokenLanguage)
            if (samples.isNotEmpty()) {
                val playbackDurationMs = ((samples.size * 1000L) / ttsEngine.sampleRate)
                    .coerceAtLeast(250L)
                val micResumeDelayMs = playbackDurationMs + 150L
                capture?.suspendProcessing(micResumeDelayMs)
                _state.value = State.Speaking(spokenSentence)
                var offset = 0
                while (offset < samples.size) {
                    val end = (offset + 2048).coerceAtMost(samples.size)
                    player?.enqueue(samples.copyOfRange(offset, end))
                    offset = end
                }
                delay(micResumeDelayMs)
                if (_state.value is State.Speaking) {
                    _state.value = State.Listening
                }
            } else {
                Log.w(TAG, "TTS produced no samples")
                _state.value = State.Listening
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS error", e)
            _state.value = State.Listening
        }
    }

    /**
     * Barge-in: user interrupted. Cancel LLM, flush audio immediately.
     */
    private fun bargeIn() {
        synchronized(ttsFeedLock) {
            ttsTurnCoordinator.invalidate()
            while (ttsSentenceQueue.tryReceive().isSuccess) { /* discard stale speech */ }
        }
        gemma?.cancel()
        player?.flush()
        resetTtsFeedBuffer()
        _response.value = ""
    }

    private suspend fun drainTtsQueue() {
        for (item in ttsSentenceQueue) {
            when (item) {
                is TtsQueueItem.Sentence -> {
                    if (!ttsTurnCoordinator.isCurrent(item.ticket)) {
                        continue
                    }
                    synthesizeAndStream(item)
                }
                is TtsQueueItem.EndOfTurn -> {
                    if (ttsTurnCoordinator.isCurrent(item.ticket)) {
                    }
                }
            }
        }
    }

    private suspend fun synthesizeAndStream(item: TtsQueueItem.Sentence) {
        val spokenSentence = stripThinkingMarkup(item.text)
        if (spokenSentence.isBlank()) {
            return
        }
        val ttsEngine = ensureTts()
        if (ttsEngine == null) {
            _state.value = State.Listening
            return
        }
        applyTtsSettings(ttsEngine)
        val spokenLanguage = preferredLanguageCode ?: item.language
        val estimatedDurationMs = estimateSpeechDurationMs(spokenSentence)
        capture?.suspendProcessing(estimatedDurationMs + 500L)
        _state.value = State.Speaking(spokenSentence)
        val samples = ttsEngine.synthesizeStreaming(spokenSentence, spokenLanguage) { chunk ->
            if (!ttsTurnCoordinator.isCurrent(item.ticket)) {
                return@synthesizeStreaming false
            }
            enqueueTtsSamples(chunk)
            true
        }
        if (samples.isEmpty()) {
            Log.w(TAG, "Streaming TTS produced no samples")
            _state.value = State.Listening
            return
        }
        val playbackDurationMs = ((samples.size * 1000L) / ttsEngine.sampleRate).coerceAtLeast(250L)
        capture?.suspendProcessing(playbackDurationMs + 150L)
        scope.launch {
            delay(playbackDurationMs + 150L)
            if (ttsTurnCoordinator.isCurrent(item.ticket) && _state.value is State.Speaking) {
                _state.value = State.Listening
            }
        }
    }

    /**
     * Handles preview voice.
     */
    suspend fun previewVoice() {
        try {
            val ttsEngine = ensureTts()
            if (ttsEngine == null) {
                Log.w(TAG, "Skipping voice preview because multilingual TTS is unavailable")
                return
            }
            applyTtsSettings(ttsEngine)
            val previewText = "Hello."
            val spokenLanguage = preferredLanguageCode ?: detectedLanguage
            val samples = ttsEngine.synthesize(previewText, spokenLanguage)
            if (samples.isEmpty()) {
                Log.w(TAG, "Voice preview produced no samples")
                return
            }
            player?.flush()
            capture?.suspendProcessing(((samples.size * 1000L) / ttsEngine.sampleRate).coerceAtLeast(250L) + 150L)
            _state.value = State.Speaking(previewText)
            enqueueTtsSamples(samples)
            val playbackDurationMs = ((samples.size * 1000L) / ttsEngine.sampleRate).coerceAtLeast(250L)
            delay(playbackDurationMs + 150L)
            if (_state.value is State.Speaking) {
                _state.value = State.Listening
            }
        } catch (e: Exception) {
            Log.e(TAG, "Voice preview error", e)
            _state.value = State.Listening
        }
    }

    private fun enqueueTtsSamples(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val end = (offset + 2048).coerceAtMost(samples.size)
            player?.enqueue(samples.copyOfRange(offset, end))
            offset = end
        }
    }

    private fun estimateSpeechDurationMs(text: String): Long {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return ((words * 360L) + 400L).coerceAtLeast(900L)
    }

    /**
     * Handles clear history.
     */
    fun clearHistory() {
        scope.launch {
            ensureGemma()?.clearHistory()
        }
        _transcript.value = ""
        _response.value = ""
    }

    /**
     * Returns gemma provider.
     * @return gemma provider result.
     */
    fun gemmaProvider(): String = gemma?.backend() ?: "cpu"
    /**
     * Returns stt provider.
     * @return stt provider result.
     */
    fun sttProvider(): String = whisper?.provider ?: "cpu"
    /**
     * Returns vad provider.
     * @return vad provider result.
     */
    fun vadProvider(): String = vad?.provider ?: "cpu"
    /**
     * Returns tts provider.
     * @return tts provider result.
     */
    fun ttsProvider(): String = kokoro?.provider ?: "cpu"

    /**
     * Handles release.
     */
    fun release() {
        capture?.stop()
        player?.stop()
        whisper?.release()
        gemma?.release()
        kokoro?.release()
        vad?.release()
    }

    private fun stripThinkingMarkup(text: String): String {
        if (text.contains(Regex("(?i)<think")) && !text.contains(Regex("(?i)</think>"))) {
            return ""
        }
        var result = text
        result = result.replace(Regex("(?is)<think>.*?</think>"), " ")
        result = result.replace(Regex("(?im)^\\s*<think>\\s*$"), " ")
        result = result.replace(Regex("(?im)^\\s*</think>\\s*$"), " ")
        result = result.replace(Regex("(?i)\\[/??think\\]"), " ")
        return result
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun applyTtsSettings(ttsEngine: MultilingualTTS) {
        ttsEngine.speakerId = AppSettings.getVoiceId(context)
        ttsEngine.numSteps = AppSettings.getTtsSteps(context)
    }

}

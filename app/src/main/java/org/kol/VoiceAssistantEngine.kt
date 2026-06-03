package com.voiceassistant

import android.content.Context
import android.util.Log
import com.voiceassistant.audio.AudioCapture
import com.voiceassistant.audio.AudioPlayer
import com.voiceassistant.audio.VadDetector
import com.voiceassistant.llm.GemmaLiteRtInference
import com.voiceassistant.llm.SentenceBatcher
import com.voiceassistant.tts.MultilingualTTS
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var gemma: GemmaLiteRtInference? = null
    private var kokoro: MultilingualTTS? = null
    private var player: AudioPlayer? = null
    private var capture: AudioCapture? = null
    private var ttsUnavailable = false
    @Volatile
    private var preferredLanguageCode: String? = null
    private val gemmaMutex = Mutex()
    private val ttsMutex = Mutex()
    private val llmSentenceQueue = Channel<FloatArray>(Channel.UNLIMITED)
    private val ttsSentenceQueue = Channel<String>(Channel.UNLIMITED)

    private val sentenceBatcher = SentenceBatcher(
        onSentenceReady = { sentence ->
            ttsSentenceQueue.trySend(sentence)
        }
    )

    private var detectedLanguage = "en"
    private var turnCounter = 0L

    private suspend fun ensureGemma(): GemmaLiteRtInference? {
        val current = gemma
        if (current != null) return current
        return gemmaMutex.withLock {
            val lockedCurrent = gemma
            if (lockedCurrent != null) return@withLock lockedCurrent
            try {
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

    fun initialize() {
        scope.launch {
            try {
                Log.d(TAG, "Initializing models...")
                vad     = VadDetector(context)
                player  = AudioPlayer().also { it.start() }

                capture = AudioCapture(
                    vad = vad!!,
                    onUtterance    = ::onUtteranceReady,
                    onUtteranceSegment = ::onUtteranceSegmentReady,
                    onSpeechStart  = ::onSpeechStart,
                    onSpeechEnd    = ::onSpeechEnd
                )

                capture!!.start()
                _state.value = State.Listening
                Log.d(TAG, "Engine ready")
                scope.launch {
                    Log.d(TAG, "Prewarming Gemma LiteRT-LM")
                    ensureGemma()
                }
                scope.launch {
                    Log.d(TAG, "Prewarming multilingual TTS")
                    ensureTts()
                }
                scope.launch {
                    drainLlmQueue()
                }
                scope.launch {
                    drainTtsQueue()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Init failed", e)
                _state.value = State.Error("Failed to load models: ${e.message}")
            }
        }
    }

    private fun onUtteranceSegmentReady(samples: FloatArray) {
        if (samples.isNotEmpty()) {
            Log.d(TAG, "onUtteranceSegmentReady samples=${samples.size} approxMs=${1000.0 * samples.size / ModelConfig.STT_SAMPLE_RATE}")
            llmSentenceQueue.trySend(samples)
        }
    }

    private fun onSpeechStart() {
        // User started speaking — if we're currently speaking, barge in
        if (_state.value is State.Speaking) {
            return
        }
        _state.value = State.Listening
    }

    private fun onSpeechEnd() {
        _state.value = State.Understanding
    }

    private fun onUtteranceReady(samples: FloatArray) {
        Log.d(TAG, "onUtteranceReady samples=${samples.size} approxMs=${1000.0 * samples.size / ModelConfig.STT_SAMPLE_RATE}")
        llmSentenceQueue.trySend(samples)
    }

    private suspend fun drainLlmQueue() {
        for (samples in llmSentenceQueue) {
            Log.d(TAG, "drainLlmQueue received samples=${samples.size}")
            processUtterance(samples)
        }
    }

    private suspend fun processUtterance(samples: FloatArray) {
        try {
            turnCounter += 1
            val turnId = turnCounter
            val wavBytes = toWavBytes(samples, ModelConfig.STT_SAMPLE_RATE)
            Log.d(TAG, "turn#$turnId audio ready samples=${samples.size} bytes=${wavBytes.size} durationMs=${1000.0 * samples.size / ModelConfig.STT_SAMPLE_RATE}")
            Log.d(TAG, "turn#$turnId audio sample preview=${samples.take(8)} ... ${samples.takeLast(8)}")

            _state.value = State.Thinking("")
            _response.value = ""
            sentenceBatcher.reset()
            Log.d(TAG, "turn#$turnId starting new assistant turn")

            val currentGemma = ensureGemma()
            if (currentGemma == null) {
                Log.w(TAG, "Gemma LiteRT-LM unavailable on demand; keeping existing model file and continuing without LLM")
                _state.value = State.Error("repair_required:gemma")
                return
            }

            Log.d(TAG, "turn#$turnId calling Gemma generateFromAudio")
            currentGemma.generateFromAudio(
                audioWavBytes = wavBytes,
                onToken = { token ->
                    Log.d(TAG, "turn#$turnId GEMMA TOKEN: ${token.take(120)}")
                    val current = _response.value + token
                    _response.value = current
                    _state.value = State.Thinking(current)
                    sentenceBatcher.addToken(token)
                },
                onUserText = { userText ->
                    if (userText.isNotBlank()) {
                        _transcript.value = userText
                        Log.d(TAG, "turn#$turnId GEMMA USER TRANSCRIPT=$userText")
                    }
                },
                onLanguage = { language ->
                    detectedLanguage = preferredLanguageCode ?: language
                    Log.d(TAG, "turn#$turnId GEMMA LANGUAGE=$language")
                },
                onDone = {
                    Log.d(TAG, "turn#$turnId Gemma done; responseBytes=${_response.value.toByteArray().size} responseChars=${_response.value.length}")
                    sentenceBatcher.done()
                    if (_state.value !is State.Speaking) {
                        _state.value = State.Listening
                    }
                }
            )
            currentGemma.clearHistory()
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            _state.value = State.Error(e.message ?: "Unknown error")
        }
    }

    fun setPreferredLanguage(languageCode: String?) {
        preferredLanguageCode = languageCode?.takeIf { it.isNotBlank() && it != "default" && it != "auto" }
        gemma?.setPreferredLanguage(preferredLanguageCode)
        detectedLanguage = preferredLanguageCode ?: detectedLanguage
    }

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
            Log.d(TAG, "Filtered TTS text")
        }
        try {
            val ttsEngine = ensureTts()
            if (ttsEngine == null) {
                Log.w(TAG, "Skipping TTS because multilingual TTS is unavailable")
                _state.value = State.Listening
                return
            }
            val spokenLanguage = preferredLanguageCode ?: detectedLanguage
            Log.d(TAG, "TTS speak lang=$spokenLanguage text=${spokenSentence.take(120)}")
            val samples = ttsEngine.synthesize(spokenSentence, spokenLanguage)
            if (samples.isNotEmpty()) {
                Log.d(TAG, "TTS produced samples=${samples.size} approxMs=${1000.0 * samples.size / ttsEngine.sampleRate}")
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
        Log.d(TAG, "bargeIn")
        gemma?.cancel()
        player?.flush()
        sentenceBatcher.reset()
        _response.value = ""
    }

    private suspend fun drainTtsQueue() {
        var pendingAudio: Deferred<Pair<String, FloatArray>>? = null
        for (sentence in ttsSentenceQueue) {
            val nextAudio = scope.async(Dispatchers.IO) {
                synthesizeTtsSentence(sentence)
            }
            val previousAudio = pendingAudio
            pendingAudio = nextAudio
            if (previousAudio != null) {
                playSynthesized(previousAudio.await())
            }
        }
        pendingAudio?.let { playSynthesized(it.await()) }
    }

    private suspend fun synthesizeTtsSentence(sentence: String): Pair<String, FloatArray> {
        val spokenSentence = stripThinkingMarkup(sentence)
        if (spokenSentence.isBlank()) {
            return spokenSentence to FloatArray(0)
        }
        val ttsEngine = ensureTts()
        if (ttsEngine == null) {
            return spokenSentence to FloatArray(0)
        }
        val spokenLanguage = preferredLanguageCode ?: detectedLanguage
        Log.d(TAG, "TTS speak lang=$spokenLanguage text=${spokenSentence.take(120)}")
        return spokenSentence to ttsEngine.synthesize(spokenSentence, spokenLanguage)
    }

    private suspend fun playSynthesized(synthesized: Pair<String, FloatArray>) {
        val (spokenSentence, samples) = synthesized
        if (spokenSentence.isBlank() || samples.isEmpty()) {
            return
        }
        val ttsEngine = ensureTts() ?: return
        Log.d(TAG, "TTS produced samples=${samples.size} approxMs=${1000.0 * samples.size / ttsEngine.sampleRate}")
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
    }

    fun clearHistory() {
        scope.launch {
            ensureGemma()?.clearHistory()
        }
        _transcript.value = ""
        _response.value = ""
    }

    fun gemmaProvider(): String = gemma?.backend() ?: "cpu"
    fun vadProvider(): String = vad?.provider ?: "cpu"
    fun ttsProvider(): String = kokoro?.provider ?: "cpu"

    fun release() {
        capture?.stop()
        player?.stop()
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

    private fun toWavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        var pcmIndex = 0
        for (sample in samples) {
            val shortSample = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            pcm[pcmIndex++] = (shortSample.toInt() and 0xff).toByte()
            pcm[pcmIndex++] = ((shortSample.toInt() shr 8) and 0xff).toByte()
        }

        val header = ByteArray(44)
        val pcmDataSize = pcm.size
        val wavFileSize = pcmDataSize + 36
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (wavFileSize and 0xff).toByte()
        header[5] = (wavFileSize shr 8 and 0xff).toByte()
        header[6] = (wavFileSize shr 16 and 0xff).toByte()
        header[7] = (wavFileSize shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = channels.toByte()
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[34] = bitsPerSample.toByte()
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataSize and 0xff).toByte()
        header[41] = (pcmDataSize shr 8 and 0xff).toByte()
        header[42] = (pcmDataSize shr 16 and 0xff).toByte()
        header[43] = (pcmDataSize shr 24).toByte()

        return header + pcm
    }
}

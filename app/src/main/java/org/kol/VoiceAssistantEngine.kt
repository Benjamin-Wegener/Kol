package com.voiceassistant

import android.content.Context
import android.util.Log
import com.voiceassistant.audio.AudioCapture
import com.voiceassistant.audio.AudioPlayer
import com.voiceassistant.audio.VadDetector
import com.voiceassistant.llm.LiteRtNativeLoader
import com.voiceassistant.llm.GemmaLiteRtInference
import com.voiceassistant.ui.AppSettings
import com.voiceassistant.tts.MultilingualTTS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Orchestrates the full pipeline:
 *
 *   Mic → VAD → Gemma 4 E2B (LiteRT-LM) → multilingual TTS → Speaker
 *
 * One turn at a time.  processUtterance guards with tryLock; if a turn is
 * already running the new utterance is dropped.  The pipeline is a single
 * linear suspend function — no queues, no drain loops, no timers.
 *
 * Barge-in: VAD fires during TTS playback → cancel LLM + flush audio → new utterance.
 */
class VoiceAssistantEngine(private val context: Context) {

    private val TAG = "VoiceAssistantEngine"
    private val scope = CoroutineScope(Dispatchers.IO)

    // ── State ──────────────────────────────────────────────────────────────────

    sealed class State {
        object Idle          : State()
        object Listening     : State()
        object Understanding : State()
        data class Thinking(val partial: String) : State()
        data class Speaking(val text: String)    : State()
        data class Error(val message: String)    : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript

    // Emits a non-blank placeholder the moment a turn starts (before any LLM token),
    // so the user bubble is always inserted before the assistant bubble.
    private val _pendingUserTranscript = MutableStateFlow("")
    val pendingUserTranscript: StateFlow<String> = _pendingUserTranscript

    private val _response = MutableStateFlow("")
    val response: StateFlow<String> = _response

    // ── Components ────────────────────────────────────────────────────────────

    private var vad: VadDetector? = null
    private var gemma: GemmaLiteRtInference? = null
    private var kokoro: MultilingualTTS? = null
    private var player: AudioPlayer? = null
    private var capture: AudioCapture? = null
    private var ttsUnavailable = false

    @Volatile
    private var preferredLanguageCode: String? = null
    private var detectedLanguage = "en"

    // One active turn at a time.  tryLock in processUtterance; unlock in finally.
    private val activeTurnLock = Mutex()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun initialize() {
        scope.launch {
            try {
                Log.d(TAG, "Initializing models...")
                vad    = VadDetector(context)
                player = AudioPlayer().also { it.start() }

                capture = AudioCapture(
                    vad           = vad!!,
                    onUtterance   = { samples -> scope.launch { processUtterance(samples) } },
                    onSpeechStart = ::onSpeechStart,
                    onSpeechEnd   = ::onSpeechEnd
                )

                Log.d(TAG, "Prewarming TTS")
                ensureTts()?.warmUp(preferredLanguageCode ?: "de")

                ensureGemma()

                _state.value = State.Listening
                Log.d(TAG, "Engine ready")
                capture!!.start()
            } catch (e: Exception) {
                Log.e(TAG, "Init failed", e)
                _state.value = State.Error("Failed to load models: ${e.message}")
            }
        }
    }

    // ── Speech callbacks ──────────────────────────────────────────────────────

    private fun onSpeechStart() {
        if (_state.value is State.Speaking) {
            bargeIn()
        }
        _state.value = State.Understanding
    }

    private fun onSpeechEnd() {
        // State is owned by processUtteranceLocked.  Nothing to do here.
    }

    // ── Turn gate ─────────────────────────────────────────────────────────────

    private suspend fun processUtterance(samples: FloatArray) {
        if (!activeTurnLock.tryLock()) {
            Log.d(TAG, "turn already active, dropping utterance")
            return
        }
        try {
            processUtteranceLocked(samples)
        } finally {
            activeTurnLock.unlock()
        }
    }

    // ── Main pipeline ─────────────────────────────────────────────────────────
    //
    // One straight line from mic samples to speaker drain.
    // State transitions happen exactly here, in order, nowhere else.

    private suspend fun processUtteranceLocked(samples: FloatArray) {
        capture?.mute()
        try {
            _pendingUserTranscript.value = "…"
            _state.value = State.Understanding

            val wavBytes = toWavBytes(samples, ModelConfig.STT_SAMPLE_RATE)
            _state.value = State.Thinking("")
            _response.value = ""

            val currentGemma = ensureGemma()
            if (currentGemma == null) {
                Log.w(TAG, "Gemma unavailable")
                _state.value = State.Error("repair_required:gemma")
                return
            }

            val ttsEngine = ensureTts()
            if (ttsEngine != null) applyTtsSettings(ttsEngine)

            val responseBuffer = StringBuilder()
            val ttsBuffer = StringBuilder()
            var ttsTokenCount = 0

            fun speakChunk(chunk: String) {
                if (chunk.isBlank()) return
                val engine = ttsEngine ?: return
                Log.d(TAG, "TTS CHUNK READY raw=${chunk.take(240)}")
                _state.value = State.Speaking(chunk)
                val spoken = stripThinkingMarkup(chunk)
                if (spoken.isBlank()) {
                    Log.d(TAG, "TTS CHUNK DROPPED after markup stripping")
                    return
                }
                val lang = preferredLanguageCode ?: detectedLanguage
                Log.d(TAG, "TTS synthesize lang=$lang text=${spoken.take(120)}")
                engine.synthesizeStreaming(spoken, lang) { samples ->
                    enqueueTtsSamples(samples)
                    true
                }
            }

            fun flushTtsBuffer() {
                val raw = cleanTtsChunk(ttsBuffer.toString())
                ttsBuffer.clear()
                ttsTokenCount = 0
                speakChunk(raw)
            }

            fun hasShortSentence(text: String): Boolean {
                val trimmed = text.trimEnd()
                if (trimmed.isEmpty()) return false
                return trimmed.last() in charArrayOf('.', '?', '!', '。', '？', '！')
            }

            currentGemma.generateFromAudio(
                audioWavBytes = wavBytes,
                onToken = { token ->
                    Log.d(TAG, "ENGINE VISIBLE TOKEN: ${token.take(240)}")
                    val clean = cleanTokenForTts(token)
                    Log.d(TAG, "ENGINE CLEAN TTS TOKEN: ${clean.take(240)}")
                    responseBuffer.append(token)
                    _response.value = responseBuffer.toString()
                    _state.value    = State.Thinking(responseBuffer.toString())

                    if (clean.isNotEmpty()) {
                        ttsBuffer.append(clean)
                        ttsTokenCount += Regex("[\\p{L}\\p{N}]+(?:[''\\-][\\p{L}\\p{N}]+)*").findAll(clean).count().coerceAtLeast(1)
                        if (hasShortSentence(ttsBuffer.toString()) || ttsTokenCount >= 5) {
                            flushTtsBuffer()
                        }
                    }
                },
                onUserText = { userText ->
                    if (userText.isNotBlank()) {
                        Log.d(TAG, "ENGINE USER TRANSCRIPT: ${userText.take(240)}")
                        _transcript.value            = userText
                        _pendingUserTranscript.value = userText
                    }
                },
                onLanguage = { language ->
                    Log.d(TAG, "ENGINE LANGUAGE: $language")
                    detectedLanguage = preferredLanguageCode ?: language
                },
                onDone = {
                    Log.d(TAG, "ENGINE GEMMA DONE")
                }
            )
            currentGemma.clearHistory()
            if (ttsBuffer.isNotEmpty()) {
                Log.d(TAG, "ENGINE FINAL TTS FLUSH buffer=${ttsBuffer.toString().take(240)}")
                flushTtsBuffer()
            }

            player?.awaitDrained()
            _state.value = State.Listening
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            _state.value = State.Error(e.message ?: "Unknown error")
        } finally {
            capture?.unmute()
        }
    }

    // ── Voice preview ─────────────────────────────────────────────────────────

    suspend fun previewVoice() {
        val ttsEngine = ensureTts()
        if (ttsEngine == null) {
            Log.w(TAG, "Skipping voice preview — TTS unavailable")
            return
        }
        try {
            applyTtsSettings(ttsEngine)
            val previewText    = "Test."
            val spokenLanguage = preferredLanguageCode ?: detectedLanguage
            val samples        = ttsEngine.synthesize(previewText, spokenLanguage)
            if (samples.isEmpty()) {
                Log.w(TAG, "Voice preview produced no samples")
                return
            }
            player?.flush()
            capture?.mute()
            try {
                _state.value = State.Speaking(previewText)
                enqueueTtsSamples(samples)
                player?.awaitDrained()
                _state.value = State.Listening
            } finally {
                capture?.unmute()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Voice preview error", e)
            _state.value = State.Listening
        }
    }

    // ── Barge-in ──────────────────────────────────────────────────────────────

    private fun bargeIn() {
        Log.d(TAG, "bargeIn")
        gemma?.cancel()
        player?.flush()
        _response.value = ""
    }

    // ── TTS helpers ───────────────────────────────────────────────────────────

    /**
     * Cleans a raw Gemma token for the TTS accumulator.
     * Strips thinking markup and the leading lang-tag noise token.
     * Does NOT trim or collapse whitespace so that " ist" style spacing is preserved.
     */
    private fun cleanTokenForTts(token: String): String {
        var t = token.replace(Regex("(?is)<think>.*?</think>"), "")
        t = t.replace(Regex("(?im)^\\s*</?think>\\s*$"), "")
        t = t.replace(Regex("(?i)\\[/?think]"), "")
        if (t.contains(Regex("(?i)<think")) && !t.contains(Regex("(?i)</think>"))) return ""
        t = t.replace(Regex("^\\[?lang\\s*=?\\s*[a-z]{2,3}\\s*]?", RegexOption.IGNORE_CASE), "")
        return t
    }

    private fun enqueueTtsSamples(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val end = (offset + 2048).coerceAtMost(samples.size)
            player?.enqueue(samples.copyOfRange(offset, end))
            offset = end
        }
    }

    private fun cleanTtsChunk(text: String): String {
        return text
            .replace(Regex("(?i)^\\s*\\]?\\s*\\[?\\s*lang\\s*=?\\s*[a-z]{2,3}\\s*]?\\s*"), "")
            .replace(Regex("^\\s*]+\\s*"), "")
            .trim()
    }

    private fun stripThinkingMarkup(text: String): String {
        if (text.contains(Regex("(?i)<think")) && !text.contains(Regex("(?i)</think>"))) return ""
        var r = text
        r = r.replace(Regex("(?is)<think>.*?</think>"), " ")
        r = r.replace(Regex("(?im)^\\s*<think>\\s*$"), " ")
        r = r.replace(Regex("(?im)^\\s*</think>\\s*$"), " ")
        r = r.replace(Regex("(?i)\\[/??think\\]"), " ")
        return r.replace(Regex("\\s+"), " ").trim()
    }

    private fun applyTtsSettings(ttsEngine: MultilingualTTS) {
        ttsEngine.speakerId = AppSettings.getVoiceId(context)
        ttsEngine.numSteps  = AppSettings.getTtsSteps(context)
    }

    // ── Model init helpers ────────────────────────────────────────────────────

    private suspend fun ensureGemma(): GemmaLiteRtInference? {
        gemma?.let { return it }
        return try {
            LiteRtNativeLoader.ensureLoaded()
            GemmaLiteRtInference(context).also {
                it.setPreferredLanguage(preferredLanguageCode)
                it.initialize()
                gemma = it
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemma unavailable", e)
            null
        }
    }

    private suspend fun ensureTts(): MultilingualTTS? {
        kokoro?.let { return it }
        if (ttsUnavailable) return null
        return try {
            MultilingualTTS(context).also { kokoro = it }
        } catch (e: Exception) {
            ttsUnavailable = true
            Log.w(TAG, "TTS unavailable", e)
            null
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setPreferredLanguage(languageCode: String?) {
        preferredLanguageCode = languageCode
            ?.takeIf { it.isNotBlank() && it != "default" && it != "auto" }
        gemma?.setPreferredLanguage(preferredLanguageCode)
        detectedLanguage = preferredLanguageCode ?: detectedLanguage
    }

    fun preferredLanguage(): String? = preferredLanguageCode

    fun clearHistory() {
        scope.launch { ensureGemma()?.clearHistory() }
        _transcript.value            = ""
        _pendingUserTranscript.value = ""
        _response.value              = ""
    }

    fun gemmaProvider(): String = gemma?.backend() ?: "cpu"
    fun vadProvider():   String = vad?.provider    ?: "cpu"
    fun ttsProvider():   String = kokoro?.provider ?: "cpu"

    fun release() {
        capture?.stop()
        player?.stop()
        gemma?.release()
        kokoro?.release()
        vad?.release()
    }

    // ── WAV encoding ──────────────────────────────────────────────────────────

    private fun toWavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        var pcmIndex = 0
        for (sample in samples) {
            val s = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            pcm[pcmIndex++] = (s.toInt() and 0xff).toByte()
            pcm[pcmIndex++] = ((s.toInt() shr 8) and 0xff).toByte()
        }
        val channels      = 1
        val bitsPerSample = 16
        val byteRate      = sampleRate * channels * bitsPerSample / 8
        val pcmDataSize   = pcm.size
        val wavFileSize   = pcmDataSize + 36
        val header = ByteArray(44)
        header[0]  = 'R'.code.toByte(); header[1]  = 'I'.code.toByte()
        header[2]  = 'F'.code.toByte(); header[3]  = 'F'.code.toByte()
        header[4]  = (wavFileSize         and 0xff).toByte()
        header[5]  = (wavFileSize  shr  8 and 0xff).toByte()
        header[6]  = (wavFileSize  shr 16 and 0xff).toByte()
        header[7]  = (wavFileSize  shr 24         ).toByte()
        header[8]  = 'W'.code.toByte(); header[9]  = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = channels.toByte()
        header[24] = (sampleRate         and 0xff).toByte()
        header[25] = (sampleRate  shr  8 and 0xff).toByte()
        header[26] = (sampleRate  shr 16 and 0xff).toByte()
        header[27] = (sampleRate  shr 24         ).toByte()
        header[28] = (byteRate           and 0xff).toByte()
        header[29] = (byteRate    shr  8 and 0xff).toByte()
        header[30] = (byteRate    shr 16 and 0xff).toByte()
        header[31] = (byteRate    shr 24         ).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[34] = bitsPerSample.toByte()
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmDataSize         and 0xff).toByte()
        header[41] = (pcmDataSize  shr  8 and 0xff).toByte()
        header[42] = (pcmDataSize  shr 16 and 0xff).toByte()
        header[43] = (pcmDataSize  shr 24         ).toByte()
        return header + pcm
    }
}

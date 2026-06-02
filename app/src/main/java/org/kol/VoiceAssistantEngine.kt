package com.voiceassistant

import android.content.Context
import android.util.Log
import com.voiceassistant.audio.AudioCapture
import com.voiceassistant.audio.AudioPlayer
import com.voiceassistant.audio.VadDetector
import com.voiceassistant.llm.LlamaInference
import com.voiceassistant.llm.SentenceBatcher
import com.voiceassistant.stt.WhisperSTT
import com.voiceassistant.tts.MultilingualTTS
import com.voiceassistant.llm.LlamaInference.Companion.isNativeAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates the full pipeline:
 *
 *   Mic → VAD → Whisper → Qwen3 (streaming) → SentenceBatcher → multilingual TTS → Speaker
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
        object Transcribing : State()
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
    private var whisper: WhisperSTT? = null
    private var llama: LlamaInference? = null
    private var kokoro: MultilingualTTS? = null
    private var player: AudioPlayer? = null
    private var capture: AudioCapture? = null
    private var ttsUnavailable = false
    private val ttsMutex = Mutex()

    private val sentenceBatcher = SentenceBatcher(
        onSentenceReady = { sentence ->
            Log.d(TAG, "Sentence ready for TTS: $sentence")
            synthesizeAndPlay(sentence)
        }
    )

    private var detectedLanguage = "en"
    private var currentUserText = ""

    private fun ensureLlama(): LlamaInference? {
        val current = llama
        if (current != null) return current
        if (!isNativeAvailable) return null
        return try {
            LlamaInference(context).also { llama = it }
        } catch (e: Exception) {
            Log.w(TAG, "LLM unavailable on demand; continuing without LLM", e)
            null
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
                whisper = WhisperSTT(context)
                player  = AudioPlayer().also { it.start() }

                capture = AudioCapture(
                    vad = vad!!,
                    onUtterance    = ::onUtteranceReady,
                    onSpeechStart  = ::onSpeechStart,
                    onSpeechEnd    = ::onSpeechEnd
                )

                capture!!.start()
                _state.value = State.Listening
                Log.d(TAG, "Engine ready")
            } catch (e: Exception) {
                Log.e(TAG, "Init failed", e)
                _state.value = State.Error("Failed to load models: ${e.message}")
            }
        }
    }

    private fun onSpeechStart() {
        // User started speaking — if we're currently speaking, barge in
        if (_state.value is State.Speaking || _state.value is State.Thinking) {
            Log.d(TAG, "Barge-in detected")
            bargeIn()
        }
        _state.value = State.Listening
    }

    private fun onSpeechEnd() {
        _state.value = State.Transcribing
    }

    private fun onUtteranceReady(samples: FloatArray) {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. STT
                val sttResult = whisper!!.transcribe(samples)
                val text = sttResult.text
                detectedLanguage = sttResult.language

                if (text.isBlank()) {
                    _state.value = State.Listening
                    return@launch
                }

                currentUserText = text
                _transcript.value = text
                Log.d(TAG, "STT [$detectedLanguage]: $text")

                // 2. LLM (streaming)
                _state.value = State.Thinking("")
                _response.value = ""
                sentenceBatcher.reset()

                val currentLlama = ensureLlama()
                if (currentLlama == null) {
                    _response.value = "LLM unavailable on this build."
                    _state.value = State.Listening
                    return@launch
                }

                currentLlama.generate(
                    userText = text,
                    language = detectedLanguage,
                    onToken = { token ->
                        Log.d(TAG, "LLM token: ${token.take(40)}")
                        val current = _response.value + token
                        _response.value = current
                        _state.value = State.Thinking(current)
                        sentenceBatcher.addToken(token)
                    },
                    onDone = {
                        Log.d(TAG, "LLM done; flushing remaining response")
                        sentenceBatcher.done()
                        val fullResponse = sentenceBatcher.getFullResponse()
                        Log.d(TAG, "LLM full response length=${fullResponse.length}")
                        if (fullResponse.isNotBlank()) {
                            currentLlama.appendToHistory(currentUserText, fullResponse)
                        }
                        if (_state.value !is State.Speaking) {
                            _state.value = State.Listening
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error", e)
                _state.value = State.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Synthesize a sentence on IO thread, enqueue audio chunks to player.
     * Runs in parallel with LLM generating the next sentence.
     */
    private fun synthesizeAndPlay(sentence: String) {
        val spokenSentence = stripThinkingMarkup(sentence)
        if (spokenSentence.isBlank()) {
            Log.d(TAG, "Skipping empty/non-spoken sentence")
            return
        }
        if (spokenSentence != sentence) {
            Log.d(TAG, "Filtered sentence for TTS: $spokenSentence")
        }
        Log.d(TAG, "Synthesizing sentence: $spokenSentence")
        scope.launch(Dispatchers.IO) {
            try {
                val ttsEngine = ensureTts()
                if (ttsEngine == null) {
                    Log.w(TAG, "Skipping TTS because multilingual TTS is unavailable")
                    _state.value = State.Listening
                    return@launch
                }
                val samples = ttsEngine.synthesize(spokenSentence, detectedLanguage)
                if (samples.isNotEmpty()) {
                    val playbackDurationMs = ((samples.size * 1000L) / ttsEngine.sampleRate)
                        .coerceAtLeast(250L)
                    val micResumeDelayMs = playbackDurationMs + 500L
                    Log.d(TAG, "TTS produced ${samples.size} samples for language=$detectedLanguage")
                    capture?.suspendProcessing(micResumeDelayMs)
                    Log.d(TAG, "Suspending mic/VAD for ${micResumeDelayMs}ms during playback")
                    _state.value = State.Speaking(spokenSentence)
                    // Stream in 2048-sample chunks so AudioTrack stays fed
                    samples.toList().chunked(2048).forEach { chunk ->
                        if (llama?.cancelRequested?.get() != true) {
                            player!!.enqueue(chunk.toFloatArray())
                        }
                    }
                    Log.d(TAG, "Queued ${samples.size} samples to AudioPlayer")
                    scope.launch {
                        delay(micResumeDelayMs)
                        if (_state.value is State.Speaking) {
                            _state.value = State.Listening
                        }
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
    }

    /**
     * Barge-in: user interrupted. Cancel LLM, flush audio immediately.
     */
    private fun bargeIn() {
        llama?.cancel()
        player?.flush()
        sentenceBatcher.reset()
        _response.value = ""
    }

    fun clearHistory() {
        ensureLlama()?.clearHistory()
        _transcript.value = ""
        _response.value = ""
    }

    fun whisperProvider(): String = whisper?.provider ?: "cpu"
    fun vadProvider(): String = vad?.provider ?: "cpu"
    fun ttsProvider(): String = kokoro?.provider ?: "cpu"

    fun release() {
        capture?.stop()
        player?.stop()
        whisper?.release()
        llama?.release()
        kokoro?.release()
        vad?.release()
    }

    private fun stripThinkingMarkup(text: String): String {
        if (text.contains(Regex("(?i)<think")) && !text.contains(Regex("(?i)</think>"))) {
            Log.d(TAG, "Dropping incomplete thinking block before TTS")
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
}

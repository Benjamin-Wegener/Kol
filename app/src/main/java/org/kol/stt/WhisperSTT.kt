package com.voiceassistant.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.voiceassistant.ModelConfig
import com.voiceassistant.ai.RuntimeProviders

/**
 * Offline speech recognition using Whisper-tiny via sherpa-onnx.
 * Returns transcript + detected language tag (e.g. "en", "de", "zh").
 */
class WhisperSTT(context: Context) {

    data class Result(val text: String, val language: String)

    private val recognizer: OfflineRecognizer
    val provider: String

    init {
        val modelsDir = ModelConfig.modelsDir(context).absolutePath

        val whisperConfig = OfflineWhisperModelConfig(
            encoder = "$modelsDir/${ModelConfig.WHISPER_ENCODER}",
            decoder = "$modelsDir/${ModelConfig.WHISPER_DECODER}",
            language = "",           // empty = auto-detect language
            task = "transcribe",
            tailPaddings = -1
        )

        val featureConfig = FeatureConfig(
            sampleRate = ModelConfig.WHISPER_SAMPLE_RATE,
            featureDim = 80
        )

        var selectedProvider = "cpu"
        recognizer = RuntimeProviders.tryProviders(RuntimeProviders.speechCandidates) { provider ->
            selectedProvider = provider
            val modelConfig = OfflineModelConfig(
                whisper = whisperConfig,
                tokens = "$modelsDir/${ModelConfig.WHISPER_TOKENS}",
                numThreads = 2,
                provider = provider,
                debug = false
            )

            val config = OfflineRecognizerConfig(
                featConfig = featureConfig,
                modelConfig = modelConfig
            )

            OfflineRecognizer(config = config)
        }
        provider = selectedProvider
    }

    /**
     * Transcribe a complete utterance (float32 PCM at 16kHz).
     * Blocking call — run on IO dispatcher.
     */
    fun transcribe(samples: FloatArray): Result {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, sampleRate = ModelConfig.WHISPER_SAMPLE_RATE)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()

        val text = result.text.trim()
        val lang = result.lang.ifEmpty { "en" }
        return Result(text = text, language = lang)
    }

    fun release() {
        recognizer.release()
    }
}

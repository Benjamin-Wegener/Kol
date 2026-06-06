package org.kol.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import org.kol.ModelConfig
import org.kol.ai.RuntimeProviders

/**
 * Thin wrapper around sherpa-onnx Silero VAD.
 * Call isSpeech() with each 512-sample float chunk.
 */
class VadDetector(context: Context) {

    private val vad: Vad
    val provider: String

    init {
        val modelPath = ModelConfig.modelFile(context, ModelConfig.SILERO_VAD).absolutePath

        val sileroConfig = SileroVadModelConfig(
            model = modelPath,
            threshold = ModelConfig.VAD_THRESHOLD,
            minSilenceDuration = ModelConfig.VAD_MIN_SILENCE_MS / 1000f,
            minSpeechDuration = 0.1f,
            windowSize = ModelConfig.STT_CHUNK_SIZE,
            maxSpeechDuration = 30f
        )

        // assetManager is nullable; passing null uses newFromFile path
        var selectedProvider = "cpu"
        vad = RuntimeProviders.tryProviders(RuntimeProviders.speechCandidates) { provider ->
            selectedProvider = provider
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = sileroConfig,
                sampleRate = ModelConfig.STT_SAMPLE_RATE,
                numThreads = 1,
                provider = provider,
                debug = false
            )

            Vad(assetManager = null, config = vadConfig)
        }
        provider = selectedProvider
    }

    /**
     * Returns whether speech.
     * @param samples Supplies the samples value.
     * @return The is speech result.
     */
    fun isSpeech(samples: FloatArray): Boolean {
        vad.acceptWaveform(samples)
        return vad.isSpeechDetected()
    }

    /**
     * Handles release.
     */
    fun release() {
        vad.release()
    }
}

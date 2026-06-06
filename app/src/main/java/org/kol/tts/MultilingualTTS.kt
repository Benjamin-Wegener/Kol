package org.kol.tts

import android.content.Context
import android.util.Log
import android.util.Log.d
import java.io.File
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import org.kol.ModelConfig
import org.kol.ai.RuntimeProviders
import kotlin.math.abs

/**
 * Text-to-speech using the sherpa-onnx multilingual TTS model.
 * Synthesizes text to FloatArray PCM.
 *
 * Voice is selected based on the detected language from STT.
 * This is the shared multilingual TTS path.
 */
class MultilingualTTS(context: Context) {

    private val TAG = "MultilingualTTS"
    private val modelsDir = ModelConfig.modelsDir(context).absolutePath
    private val tts: OfflineTts
    val provider: String

    @Volatile var speakerId: Int = 6
    @Volatile var numSteps: Int = ModelConfig.TTS_NUM_STEPS
    private val supportedLanguages = setOf(
        "en", "zh", "de", "fr", "es", "pt", "hi", "it", "ar", "ru", "ja", "ko"
    )

    init {
        val ttsModelConfig = OfflineTtsSupertonicModelConfig(
            durationPredictor = "$modelsDir/${ModelConfig.SUPERTONIC_DURATION_PREDICTOR}",
            textEncoder = "$modelsDir/${ModelConfig.SUPERTONIC_TEXT_ENCODER}",
            vectorEstimator = "$modelsDir/${ModelConfig.SUPERTONIC_VECTOR_ESTIMATOR}",
            vocoder = "$modelsDir/${ModelConfig.SUPERTONIC_VOCODER}",
            ttsJson = "$modelsDir/${ModelConfig.SUPERTONIC_TTS_JSON}",
            unicodeIndexer = "$modelsDir/${ModelConfig.SUPERTONIC_UNICODE_INDEXER}",
            voiceStyle = "$modelsDir/${ModelConfig.SUPERTONIC_VOICE_STYLE}"
        )

        var selectedProvider = "cpu"
        tts = RuntimeProviders.tryProviders(RuntimeProviders.speechCandidates) { provider ->
            selectedProvider = provider
            val modelConfig = OfflineTtsModelConfig(
                supertonic = ttsModelConfig,
                numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                provider = provider,
                debug = false
            )

            val config = OfflineTtsConfig(model = modelConfig)
            OfflineTts(config = config)
        }
        provider = selectedProvider
        d(TAG, "Multilingual TTS loaded, sampleRate=${tts.sampleRate()}")
    } // init

    /**
     * Synthesize text to float PCM samples.
     * @param text      sentence to speak
     * @param language  language code from STT (e.g. "de", "zh")
     * @return float32 PCM at ModelConfig.TTS_SAMPLE_RATE
     */
    fun synthesize(text: String, language: String): FloatArray {
        if (text.isBlank()) return FloatArray(0)

        val normalizedLanguage = normalizeLanguage(language)
        if (normalizedLanguage != language) {
            d(TAG, "Language normalized: $language -> $normalizedLanguage")
        }
        val startedAt = System.currentTimeMillis()
        val result = tts.generateWithConfig(
            text = text,
            config = GenerationConfig(
                sid = speakerId,
                speed = ModelConfig.TTS_SPEED,
                numSteps = numSteps,
                extra = mapOf("lang" to normalizedLanguage)
            )
        )
        val samples = result.samples
        var peak = 0f
        var nonZero = 0
        for (sample in samples) {
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            if (magnitude > 0.0001f) nonZero++
        }
        return samples
    }

    fun synthesizeStreaming(
        text: String,
        language: String,
        onSamples: (FloatArray) -> Boolean
    ): FloatArray {
        if (text.isBlank()) return FloatArray(0)

        val normalizedLanguage = normalizeLanguage(language)
        if (normalizedLanguage != language) {
            d(TAG, "Language normalized: $language -> $normalizedLanguage")
        }
        val startedAt = System.currentTimeMillis()
        val callback = StreamingTtsCallback(TAG, startedAt) { samples ->
            onSamples(samples)
        }
        val result = tts.generateWithConfigAndCallback(
            text = text,
            config = GenerationConfig(
                sid = speakerId,
                speed = ModelConfig.TTS_SPEED,
                numSteps = numSteps,
                extra = mapOf("lang" to normalizedLanguage)
            ),
            callback = callback
        )
        val durationMs = System.currentTimeMillis() - startedAt
        return result.samples
    }

    val sampleRate: Int get() = tts.sampleRate()

    /**
     * Handles warm up.
     * @param language Supplies the language value.
     */
    fun warmUp(language: String = "de") {
        val startedAt = System.currentTimeMillis()
        synthesize("Ja.", language)
    }

    /**
     * Handles release.
     */
    fun release() {
        tts.release()
    }

    private fun describeFile(file: File): String {
        return if (!file.exists()) {
            "missing"
        } else {
            "exists,size=${file.length()},path=${file.absolutePath}"
        }
    }

    private fun normalizeLanguage(language: String): String {
        val normalized = when (language.lowercase()) {
            "zh-cn", "zh-tw", "cmn" -> "zh"
            "pt-br", "pt-pt" -> "pt"
            "default", "auto" -> "en"
            "jp" -> "ja"
            "kr" -> "ko"
            else -> language.lowercase()
        }
        return if (supportedLanguages.contains(normalized)) normalized else "en"
    }
}

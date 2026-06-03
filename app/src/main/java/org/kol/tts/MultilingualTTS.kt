package com.voiceassistant.tts

import android.content.Context
import android.util.Log
import android.util.Log.d
import java.io.File
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.voiceassistant.ModelConfig
import com.voiceassistant.ai.RuntimeProviders
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

    private val speakerId = 6
    private val supportedLanguages = setOf(
        "en", "zh", "de", "fr", "es", "pt", "hi", "it", "ar", "ru", "ja", "ko"
    )

    init {
        val durationPredictorFile = File(modelsDir, ModelConfig.SUPERTONIC_DURATION_PREDICTOR)
        val textEncoderFile = File(modelsDir, ModelConfig.SUPERTONIC_TEXT_ENCODER)
        val vectorEstimatorFile = File(modelsDir, ModelConfig.SUPERTONIC_VECTOR_ESTIMATOR)
        val vocoderFile = File(modelsDir, ModelConfig.SUPERTONIC_VOCODER)
        val ttsJsonFile = File(modelsDir, ModelConfig.SUPERTONIC_TTS_JSON)
        val unicodeIndexerFile = File(modelsDir, ModelConfig.SUPERTONIC_UNICODE_INDEXER)
        val voiceStyleFile = File(modelsDir, ModelConfig.SUPERTONIC_VOICE_STYLE)
        Log.d(
            TAG,
            "TTS files duration=${describeFile(durationPredictorFile)} textEncoder=${describeFile(textEncoderFile)} vector=${describeFile(vectorEstimatorFile)} vocoder=${describeFile(vocoderFile)} ttsJson=${describeFile(ttsJsonFile)} unicode=${describeFile(unicodeIndexerFile)} voice=${describeFile(voiceStyleFile)}"
        )

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
                numThreads = 2,
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
            Log.d(TAG, "Normalized TTS language $language -> $normalizedLanguage")
        }
        val startedAt = System.currentTimeMillis()
        val result = tts.generateWithConfig(
            text = text,
            config = GenerationConfig(
                sid = speakerId,
                speed = ModelConfig.TTS_SPEED,
                numSteps = ModelConfig.TTS_NUM_STEPS,
                extra = mapOf("lang" to normalizedLanguage)
            )
        )
        val samples = result.samples
        val durationMs = System.currentTimeMillis() - startedAt
        var peak = 0f
        var nonZero = 0
        for (sample in samples) {
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            if (magnitude > 0.0001f) nonZero++
        }
        Log.d(
            TAG,
            "Generated ${samples.size} samples in ${durationMs}ms peak=$peak nonZero=$nonZero sampleRate=${result.sampleRate} lang=$normalizedLanguage sid=$speakerId"
        )
        return samples
    }

    val sampleRate: Int get() = tts.sampleRate()

    fun warmUp(language: String = "de") {
        val startedAt = System.currentTimeMillis()
        synthesize("Ja.", language)
        Log.d(TAG, "TTS warm-up completed in ${System.currentTimeMillis() - startedAt}ms")
    }

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

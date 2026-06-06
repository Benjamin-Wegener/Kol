package org.kol.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.kol.ModelConfig
import org.kol.ai.RuntimeProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Represents the whisper transcriber component.
 */
class WhisperTranscriber(private val context: Context) {

    /**
     * Describes result values.
     */
    data class Result(
        val text: String,
        val language: String?,
        val elapsedMs: Long
    )

    private val tag = "WhisperTranscriber"
    private val lock = ReentrantLock()
    private var recognizer: OfflineRecognizer? = null
    private var released = false

    @Volatile
    var provider: String = "uninitialized"
        private set

    /**
     * Handles initialize.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!released) { "Whisper transcriber has been released" }
            if (recognizer != null) return@withLock
            recognizer = createRecognizer()
        }
    }

    /**
     * Returns transcribe.
     * @param samples Supplies the samples value.
     * @return The transcribe result.
     */
    suspend fun transcribe(samples: FloatArray): Result = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext Result("", null, 0L)

        lock.withLock {
            check(!released) { "Whisper transcriber has been released" }
            val currentRecognizer = recognizer ?: createRecognizer().also { recognizer = it }
            val startedAt = System.currentTimeMillis()
            val stream = currentRecognizer.createStream()
            try {
                stream.acceptWaveform(samples, ModelConfig.STT_SAMPLE_RATE)
                currentRecognizer.decode(stream)
                val result = currentRecognizer.getResult(stream)
                val elapsedMs = System.currentTimeMillis() - startedAt
                val language = normalizeWhisperLanguage(result.lang)
                val text = result.text.trim()
                Result(text = text, language = language, elapsedMs = elapsedMs)
            } finally {
                stream.release()
            }
        }
    }

    /**
     * Handles release.
     */
    fun release() {
        lock.withLock {
            if (released) return
            released = true
            recognizer?.release()
            recognizer = null
        }
    }

    private fun createRecognizer(): OfflineRecognizer {
        val encoder = requireModelFile(ModelConfig.WHISPER_ENCODER)
        val decoder = requireModelFile(ModelConfig.WHISPER_DECODER)
        val tokens = requireModelFile(ModelConfig.WHISPER_TOKENS)
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        return RuntimeProviders.tryProviders(RuntimeProviders.speechCandidates) { candidate ->
            val modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = "",
                    task = "transcribe"
                ),
                numThreads = numThreads,
                debug = false,
                provider = candidate,
                tokens = tokens.absolutePath
            )
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = ModelConfig.STT_SAMPLE_RATE,
                    featureDim = 80
                ),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )
            OfflineRecognizer(assetManager = null, config = config).also {
                provider = candidate
            }
        }
    }

    private fun requireModelFile(name: String): File {
        return ModelConfig.modelFile(context, name).also { file ->
            require(file.exists() && file.length() > 1024) {
                "Missing Whisper model file: ${file.absolutePath}"
            }
        }
    }

}

/**
 * Returns normalize whisper language.
 * @param language Supplies the language value.
 * @return The normalize whisper language result.
 */
internal fun normalizeWhisperLanguage(language: String): String? {
    return language
        .trim()
        .removePrefix("<|")
        .removeSuffix("|>")
        .lowercase()
        .takeIf { it.matches(Regex("[a-z]{2,3}")) }
}

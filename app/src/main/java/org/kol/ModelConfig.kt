package org.kol

import android.content.Context
import java.io.File

/**
 * Holds model config configuration and related helpers.
 */
object ModelConfig {

    // ── Model filenames (downloaded into app's filesDir) ──────────────────────

    // VAD: Silero VAD v4
    const val SILERO_VAD        = "silero_vad.onnx"

    const val WHISPER_DIR = "sherpa-onnx-whisper-small"
    const val WHISPER_ENCODER = "$WHISPER_DIR/small-encoder.int8.onnx"
    const val WHISPER_DECODER = "$WHISPER_DIR/small-decoder.int8.onnx"
    const val WHISPER_TOKENS = "$WHISPER_DIR/small-tokens.txt"

    // Multimodal voice model: Gemma 4 E2B for LiteRT-LM
    const val GEMMA_LITERTLM    = "gemma-4-E2B-it.litertlm"

    // TTS: multilingual SupertonicTTS bundle (31 languages)
    const val SUPERTONIC_DIR = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    const val SUPERTONIC_ARCHIVE = "$SUPERTONIC_DIR.tar.bz2"
    const val SUPERTONIC_DURATION_PREDICTOR = "$SUPERTONIC_DIR/duration_predictor.int8.onnx"
    const val SUPERTONIC_TEXT_ENCODER = "$SUPERTONIC_DIR/text_encoder.int8.onnx"
    const val SUPERTONIC_VECTOR_ESTIMATOR = "$SUPERTONIC_DIR/vector_estimator.int8.onnx"
    const val SUPERTONIC_VOCODER = "$SUPERTONIC_DIR/vocoder.int8.onnx"
    const val SUPERTONIC_TTS_JSON = "$SUPERTONIC_DIR/tts.json"
    const val SUPERTONIC_UNICODE_INDEXER = "$SUPERTONIC_DIR/unicode_indexer.bin"
    const val SUPERTONIC_VOICE_STYLE = "$SUPERTONIC_DIR/voice.bin"

    // ── HuggingFace download URLs ─────────────────────────────────────────────

    val DOWNLOAD_URLS = linkedMapOf(
        SILERO_VAD       to "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        WHISPER_ENCODER   to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
        WHISPER_DECODER   to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
        WHISPER_TOKENS    to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
        GEMMA_LITERTLM   to "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        SUPERTONIC_ARCHIVE to "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
    )

    // ── Runtime config ─────────────────────────────────────────────────────────

    // Gemma / LiteRT-LM
    // Raised to allow longer spoken answers without premature truncation.
    const val GEMMA_MAX_TOKENS    = 8192
    const val GEMMA_TOP_K         = 20
    const val GEMMA_TOP_P         = 0.95f
    const val GEMMA_TEMPERATURE   = 0.7f
    private const val DEFAULT_LANGUAGE_ID = "default"
    private const val AUTO_LANGUAGE_ID = "auto"
    private const val SAME_AS_USER_LANGUAGE_INSTRUCTION = "Reply language: same as the user"
    private const val REPLY_LANGUAGE_PREFIX = "Reply language: "
    private val ISO_LANGUAGE_CODE_REGEX = Regex("[a-z]{2,3}")

    /**
     * Describes language option values.
     */
    data class LanguageOption(
        val id: String,
        val label: String,
        val languageCode: String?,
        val flag: String
    )

    val LANGUAGE_OPTIONS = listOf(
        LanguageOption(DEFAULT_LANGUAGE_ID, "Default", null, "🌐"),
        LanguageOption("en", "English", "en", "🇬🇧"),
        LanguageOption("de", "German", "de", "🇩🇪"),
        LanguageOption("fr", "French", "fr", "🇫🇷"),
        LanguageOption("es", "Spanish", "es", "🇪🇸"),
        LanguageOption("zh", "Chinese", "zh", "🇨🇳")
    )

    /**
     * Returns system prompt.
     * @return system prompt result.
     */
    fun systemPrompt(): String = """You are a helpful voice assistant.
The user's messages are text transcripts of speech.
Reply exactly as [lang=xx]spoken reply, where xx is the ISO language code you used.
Follow any "Reply language:" instruction at the start of a user message without mentioning it.
Be concise by default. Do not use markdown, placeholders, hidden memory, summaries, or reasoning text."""

    /**
     * Returns system prompt for log.
     * @return system prompt for log result.
     */
    fun systemPromptForLog(): String = systemPrompt()

    fun textTurnPrompt(
        userText: String,
        preferredLanguage: String?,
        detectedLanguage: String?
    ): String {
        val language = preferredLanguage
            ?.takeIf { it.isNotBlank() && it != DEFAULT_LANGUAGE_ID && it != AUTO_LANGUAGE_ID }
            ?: detectedLanguage?.takeIf { it.matches(ISO_LANGUAGE_CODE_REGEX) }
        val instruction = language?.let { "$REPLY_LANGUAGE_PREFIX$it" }
            ?: SAME_AS_USER_LANGUAGE_INSTRUCTION
        return "$instruction\n$userText"
    }

    // STT
    const val STT_SAMPLE_RATE = 16000
    const val STT_CHUNK_SIZE  = 512   // frames per chunk

    // VAD
    const val VAD_THRESHOLD       = 0.5f
    const val VAD_MIN_SILENCE_MS  = 350   // ms of silence to end utterance
    const val VAD_SPEECH_PAD_MS   = 300   // ms pre-roll buffer

    // TTS
    const val TTS_SAMPLE_RATE     = 44100
    const val TTS_SPEED           = 1.0f
    const val TTS_NUM_STEPS       = 16

    // Sentence batcher — flush to TTS on these chars
    val SENTENCE_ENDINGS = setOf('.', '?', '!', '。', '？', '！', '…')

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns models dir.
     * @param context TODO(me 5): document this parameter.
     * @return models dir result.
     */
    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").also { it.mkdirs() }

    /**
     * Returns model file.
     * @param context TODO(me 5): document this parameter.
     * @param name TODO(me 5): document this parameter.
     * @return model file result.
     */
    fun modelFile(context: Context, name: String): File =
        File(modelsDir(context), name)

    /**
     * Returns all models present.
     * @param context TODO(me 5): document this parameter.
     * @return all models present result.
     */
    fun allModelsPresent(context: Context): Boolean {
        val required = listOf(
            SILERO_VAD,
            WHISPER_ENCODER,
            WHISPER_DECODER,
            WHISPER_TOKENS,
            GEMMA_LITERTLM,
            SUPERTONIC_DURATION_PREDICTOR,
            SUPERTONIC_TEXT_ENCODER,
            SUPERTONIC_VECTOR_ESTIMATOR,
            SUPERTONIC_VOCODER,
            SUPERTONIC_TTS_JSON,
            SUPERTONIC_UNICODE_INDEXER,
            SUPERTONIC_VOICE_STYLE
        )
        return required.all { modelFile(context, it).exists() }
    }
}

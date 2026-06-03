package com.voiceassistant

import android.content.Context
import java.io.File

object ModelConfig {

    // ── Model filenames (downloaded into app's filesDir) ──────────────────────

    // VAD: Silero VAD v4
    const val SILERO_VAD        = "silero_vad.onnx"

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
        GEMMA_LITERTLM   to "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        SUPERTONIC_ARCHIVE to "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
    )

    // ── Runtime config ─────────────────────────────────────────────────────────

    // Gemma / LiteRT-LM
    const val GEMMA_MAX_TOKENS    = 512
    const val GEMMA_TOP_K         = 20
    const val GEMMA_TOP_P         = 0.95f
    const val GEMMA_TEMPERATURE   = 0.7f

    data class LanguageOption(
        val id: String,
        val label: String,
        val languageCode: String?,
        val flag: String
    )

    val LANGUAGE_OPTIONS = listOf(
        LanguageOption("default", "Default", null, "🌐"),
        LanguageOption("en", "English", "en", "🇬🇧"),
        LanguageOption("de", "German", "de", "🇩🇪"),
        LanguageOption("fr", "French", "fr", "🇫🇷"),
        LanguageOption("es", "Spanish", "es", "🇪🇸"),
        LanguageOption("zh", "Chinese", "zh", "🇨🇳")
    )

    fun systemPrompt(preferredLanguage: String? = null): String {
        val languageInstruction = when (preferredLanguage) {
            null, "default", "auto" -> "Reply in the same language the user speaks."
            "en" -> "Reply only in English."
            "de" -> "Reply only in German."
            "fr" -> "Reply only in French."
            "es" -> "Reply only in Spanish."
            "zh" -> "Reply only in Chinese."
            else -> "Reply in the same language the user speaks."
        }
        return """You are a helpful voice assistant.
The user's message may be audio.
${languageInstruction}
First write a faithful short transcript of what the user said as [user=...].
Then write the spoken reply as [lang=xx]... where xx is the ISO language code.
Do not think aloud, explain reasoning, reveal hidden thoughts, or use a thought channel.
Be concise by default, but give a fuller answer when the user asks for one or the topic needs it.
No markdown, no bullet points, no code unless asked."""
    }

    fun systemPromptForLog(preferredLanguage: String? = null): String = systemPrompt(preferredLanguage)

    // STT
    const val STT_SAMPLE_RATE = 16000
    const val STT_CHUNK_SIZE  = 512   // frames per chunk

    // VAD
    const val VAD_THRESHOLD       = 0.5f
    const val VAD_MIN_SILENCE_MS  = 500   // ms of silence to end utterance
    const val VAD_SPEECH_PAD_MS   = 300   // ms pre-roll buffer

    // TTS
    const val TTS_SAMPLE_RATE     = 44100
    const val TTS_SPEED           = 1.0f

    // Sentence batcher — flush to TTS on these chars
    val SENTENCE_ENDINGS = setOf('.', '?', '!', '。', '？', '！', '…')

    // ── Helpers ────────────────────────────────────────────────────────────────

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").also { it.mkdirs() }

    fun modelFile(context: Context, name: String): File =
        File(modelsDir(context), name)

    fun allModelsPresent(context: Context): Boolean {
        val required = listOf(
            SILERO_VAD, GEMMA_LITERTLM,
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

package com.voiceassistant

import android.content.Context
import java.io.File

object ModelConfig {

    // ── Model filenames (downloaded into app's filesDir) ──────────────────────

    // STT: Whisper tiny multilingual via sherpa-onnx
    const val WHISPER_ENCODER   = "whisper-tiny-encoder.int8.onnx"
    const val WHISPER_DECODER   = "whisper-tiny-decoder.int8.onnx"
    const val WHISPER_TOKENS    = "whisper-tiny-tokens.txt"
    const val WHISPER_LANG_FILE = "whisper-tiny-multilingual.onnx"   // optional language-id model

    // VAD: Silero VAD v4
    const val SILERO_VAD        = "silero_vad.onnx"

    // LLM: Unsloth Qwen3.5 0.8B UD-IQ3_XXS (~380 MB GGUF)
    const val QWEN_GGUF         = "Qwen3.5-0.8B-UD-IQ3_XXS.gguf"

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
        WHISPER_ENCODER  to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
        WHISPER_DECODER  to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
        WHISPER_TOKENS   to "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
        SILERO_VAD       to "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        QWEN_GGUF        to "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-UD-IQ3_XXS.gguf",
        SUPERTONIC_ARCHIVE to "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
    )

    // ── Runtime config ─────────────────────────────────────────────────────────

    // LLM
    const val LLM_CONTEXT_SIZE    = 2048
    const val LLM_THREADS         = 4
    const val LLM_TEMPERATURE     = 0.7f
    const val LLM_TOP_K           = 20
    const val LLM_TOP_P           = 0.95f
    const val LLM_PRESENCE_PENALTY = 1.5f

    // System prompt: respond conversationally in the user's language, no markdown
    const val SYSTEM_PROMPT = """You are a helpful voice assistant. 
Always reply in the same language the user speaks.
Keep answers concise and conversational.
Do not think aloud, do not explain your reasoning, and do not output hidden reasoning.
No bullet points, no markdown, no code.
Answer in at most one short sentence unless the user explicitly asks for more detail."""

    // STT
    const val WHISPER_SAMPLE_RATE = 16000
    const val WHISPER_CHUNK_SIZE  = 512   // frames per chunk

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
            WHISPER_ENCODER, WHISPER_DECODER, WHISPER_TOKENS,
            SILERO_VAD, QWEN_GGUF,
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

package com.voiceassistant.llm

import android.content.Context
import android.util.Log
import com.voiceassistant.ModelConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GGUF-backed LLM inference bridge.
 *
 * This uses a native llama.cpp build so we can load the existing Qwen GGUF model directly.
 * Generation runs on CPU for now, and the native layer returns the full completion text.
 */
class LlamaInference(context: Context) {

    private val tag = "LlamaInference"
    private val nativeAvailable = Companion.isNativeAvailable

    val cancelRequested = AtomicBoolean(false)

    private var modelPtr: Long = 0L
    private val modelPath = ModelConfig.modelFile(context, ModelConfig.QWEN_GGUF).absolutePath

    private val history = mutableListOf<Pair<String, String>>()
    private var detectedLanguage = "en"

    init {
        if (nativeAvailable) {
            modelPtr = llamaLoadModel(
                modelPath = modelPath,
                nGpuLayers = 0,
                seed = 42
            )
            Log.d(tag, "Model loaded: $modelPath")
        }
    }

    fun generate(
        userText: String,
        language: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit
    ) {
        cancelRequested.set(false)
        detectedLanguage = language
        val prompt = buildPrompt(userText, language)

        Thread {
            try {
                val response = llamaGenerateText(
                    model = modelPtr,
                    prompt = prompt,
                    temp = ModelConfig.LLM_TEMPERATURE,
                    topK = ModelConfig.LLM_TOP_K,
                    topP = ModelConfig.LLM_TOP_P,
                    presencePenalty = ModelConfig.LLM_PRESENCE_PENALTY,
                    maxTokens = 48
                )
                val visibleResponse = stripThinkingOutput(response)
                if (visibleResponse != response) {
                    Log.d(tag, "Stripped hidden thinking from LLM response")
                }
                streamText(visibleResponse, onToken)
            } catch (e: Throwable) {
                Log.e(tag, "Generation failed", e)
            } finally {
                onDone()
            }
        }.apply { isDaemon = true }.start()
    }

    fun cancel() {
        cancelRequested.set(true)
    }

    fun appendToHistory(user: String, assistant: String) {
        history.add(Pair(user, assistant))
        if (history.size > 10) history.removeAt(0)
    }

    fun clearHistory() {
        history.clear()
    }

    fun release() {
        if (modelPtr != 0L) {
            llamaFreeModel(modelPtr)
            modelPtr = 0L
        }
    }

    private fun streamText(text: String, onToken: (String) -> Unit) {
        if (text.isEmpty()) return
        val chunkSize = 6
        var index = 0
        while (index < text.length && !cancelRequested.get()) {
            val end = minOf(index + chunkSize, text.length)
            onToken(text.substring(index, end))
            index = end
        }
    }

    private fun buildPrompt(userText: String, language: String): String {
        val sb = StringBuilder()

        sb.append("<|im_start|>system\n")
        sb.append(ModelConfig.SYSTEM_PROMPT)
        sb.append("\n/no_think")
        sb.append("\nRespond in language: $language")
        sb.append("\n<|im_end|>\n")

        history.takeLast(4).forEach { (user, assistant) ->
            sb.append("<|im_start|>user\n$user\n<|im_end|>\n")
            sb.append("<|im_start|>assistant\n$assistant\n<|im_end|>\n")
        }

        sb.append("<|im_start|>user\n$userText\n/no_think\n<|im_end|>\n")
        sb.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        return sb.toString()
    }

    private fun stripThinkingOutput(text: String): String {
        if (text.isBlank()) return ""
        val closed = Regex("(?is)<think>.*?</think>").replace(text, " ")
        val withoutOpenThink = if (closed.contains(Regex("(?i)<think"))) {
            closed.substringBefore("<think", "")
        } else {
            closed
        }
        return withoutOpenThink
            .replace(Regex("(?im)^\\s*</think>\\s*$"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private external fun llamaLoadModel(modelPath: String, nGpuLayers: Int, seed: Int): Long
    private external fun llamaGenerateText(
        model: Long,
        prompt: String,
        temp: Float,
        topK: Int,
        topP: Float,
        presencePenalty: Float,
        maxTokens: Int
    ): String

    private external fun llamaFreeModel(model: Long)

    companion object {
        @Volatile
        var isNativeAvailable: Boolean = false
            private set

        init {
            isNativeAvailable = try {
                System.loadLibrary("llama-android")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e("LlamaInference", "Native LLM library missing", e)
                false
            }
        }
    }
}

package com.voiceassistant.ai

import android.content.Context
import android.util.Log
import com.voiceassistant.ModelConfig

object RuntimeProviders {
    const val TAG = "RuntimeProviders"

    val speechCandidates: List<String> = listOf("cpu")

    fun preferredSpeechProvider(): String = speechCandidates.first()

    fun providerLabel(provider: String): String = when (provider) {
        "nnapi" -> "NNAPI"
        else -> provider.uppercase()
    }

    inline fun <T> tryProviders(
        providers: List<String>,
        crossinline create: (String) -> T
    ): T {
        var lastError: Throwable? = null
        for (provider in providers) {
            try {
                Log.d(TAG, "Trying provider=$provider")
                return create(provider)
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Provider $provider unavailable, falling back", t)
            }
        }
        throw IllegalStateException("No supported runtime provider found", lastError)
    }

    fun modelsDir(context: Context) = ModelConfig.modelsDir(context)
}

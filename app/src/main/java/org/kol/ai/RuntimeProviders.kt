package org.kol.ai

import android.content.Context
import android.util.Log
import org.kol.ModelConfig

/**
 * Holds runtime providers helpers and state.
 */
object RuntimeProviders {
    const val TAG = "RuntimeProviders"

    val speechCandidates: List<String> = listOf("cpu")

    /**
     * Returns preferred speech provider.
     * @return The preferred speech provider result.
     */
    fun preferredSpeechProvider(): String = speechCandidates.first()

    /**
     * Returns provider label.
     * @param provider Supplies the provider value.
     * @return The provider label result.
     */
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
                return create(provider)
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Provider $provider unavailable, falling back", t)
            }
        }
        throw IllegalStateException("No supported runtime provider found", lastError)
    }

    /**
     * Handles models dir.
     * @param context Supplies the context value.
     */
    fun modelsDir(context: Context) = ModelConfig.modelsDir(context)
}

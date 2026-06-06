package org.kol.ui

import android.content.Context

/**
 * Holds app settings helpers and state.
 */
object AppSettings {
    private const val PREFS = "kol_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_VOICE_ID = "voice_id"
    private const val KEY_TTS_STEPS = "tts_steps"

    /**
     * Returns language.
     * @param context Supplies the context value.
     * @return The get language result.
     */
    fun getLanguage(context: Context): String? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "default")
        return value?.takeIf { it != "default" }
    }

    /**
     * Handles set language.
     * @param context Supplies the context value.
     * @param language Supplies the language value.
     */
    fun setLanguage(context: Context, language: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language ?: "default")
            .apply()
    }

    /**
     * Returns voice id.
     * @param context Supplies the context value.
     * @return The get voice id result.
     */
    fun getVoiceId(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VOICE_ID, 6)

    /**
     * Handles set voice id.
     * @param context Supplies the context value.
     * @param id Supplies the id value.
     */
    fun setVoiceId(context: Context, id: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VOICE_ID, id)
            .apply()
    }

    /**
     * Returns tts steps.
     * @param context Supplies the context value.
     * @return The get tts steps result.
     */
    fun getTtsSteps(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TTS_STEPS, 16)

    /**
     * Handles set tts steps.
     * @param context Supplies the context value.
     * @param steps Supplies the steps value.
     */
    fun setTtsSteps(context: Context, steps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TTS_STEPS, steps)
            .apply()
    }
}

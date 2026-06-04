package com.voiceassistant.ui

import android.content.Context

object AppSettings {
    private const val PREFS = "kol_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_VOICE_ID = "voice_id"
    private const val KEY_TTS_STEPS = "tts_steps"

    fun getLanguage(context: Context): String? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "default")
        return value?.takeIf { it != "default" }
    }

    fun setLanguage(context: Context, language: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language ?: "default")
            .apply()
    }

    fun getVoiceId(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VOICE_ID, 6)

    fun setVoiceId(context: Context, id: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VOICE_ID, id)
            .apply()
    }

    fun getTtsSteps(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TTS_STEPS, 16)

    fun setTtsSteps(context: Context, steps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TTS_STEPS, steps)
            .apply()
    }
}

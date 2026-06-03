package com.voiceassistant.ui

import android.content.Context

object AppSettings {
    private const val PREFS = "kol_settings"
    private const val KEY_LANGUAGE = "language"

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
}

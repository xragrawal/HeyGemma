package com.example.gemmaapp

import android.content.Context

object TelegramConfig {
    private const val PREFS     = "telegram_prefs"
    private const val KEY_TOKEN = "bot_token"

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)

    fun setToken(context: Context, token: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token.trim()).apply()

    fun clearToken(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TOKEN).apply()
}

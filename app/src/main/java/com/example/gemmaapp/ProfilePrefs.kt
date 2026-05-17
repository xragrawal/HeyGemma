package com.example.gemmaapp

import android.content.Context

object ProfilePrefs {

    private const val PREFS             = "user_profile"
    private const val KEY_NAME          = "user_name"
    private const val KEY_KEYWORD       = "emergency_keyword"
    private const val KEY_REPEAT        = "emergency_repeat_count"
    private const val KEY_CONTACT_NAME     = "contact_name"
    private const val KEY_CONTACT_ID       = "contact_chat_id"
    private const val KEY_CONTACT_USERNAME = "contact_username"
    private const val KEY_ONBOARDING    = "onboarding_complete"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var userName: String
        get() = prefs().getString(KEY_NAME, "") ?: ""
        set(v) { prefs().edit().putString(KEY_NAME, v.trim()).apply() }

    // User-defined trigger phrase, stored lowercase
    var emergencyKeyword: String
        get() = prefs().getString(KEY_KEYWORD, "emergency") ?: "emergency"
        set(v) { prefs().edit().putString(KEY_KEYWORD, v.lowercase().trim()).apply() }

    // How many consecutive detections fire the alert (default 3 per UX decision)
    var emergencyRepeatCount: Int
        get() = prefs().getInt(KEY_REPEAT, 3)
        set(v) { prefs().edit().putInt(KEY_REPEAT, v).apply() }

    var contactName: String
        get() = prefs().getString(KEY_CONTACT_NAME, "") ?: ""
        set(v) { prefs().edit().putString(KEY_CONTACT_NAME, v.trim()).apply() }

    // Telegram chat ID of the emergency contact (0L = not set)
    var contactChatId: Long
        get() = prefs().getLong(KEY_CONTACT_ID, 0L)
        set(v) { prefs().edit().putLong(KEY_CONTACT_ID, v).apply() }

    // Raw @username entered by user — kept for display and re-resolution
    var contactUsername: String
        get() = prefs().getString(KEY_CONTACT_USERNAME, "") ?: ""
        set(v) { prefs().edit().putString(KEY_CONTACT_USERNAME, v.trim()).apply() }

    var onboardingComplete: Boolean
        get() = prefs().getBoolean(KEY_ONBOARDING, false)
        set(v) { prefs().edit().putBoolean(KEY_ONBOARDING, v).apply() }

    /** True only when the minimum required fields are set for emergency to work. */
    fun isConfigured(): Boolean = contactChatId != 0L && userName.isNotBlank()
}

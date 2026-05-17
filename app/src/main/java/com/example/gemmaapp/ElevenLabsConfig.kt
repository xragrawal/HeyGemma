package com.example.gemmaapp

import android.content.Context

object ElevenLabsConfig {

    // Keys come from local.properties (gitignored) via BuildConfig.
    // Fallback order: local.properties → SharedPrefs (set via ElevenLabs settings screen) → Android TTS
    //
    // Popular voice IDs:
    //   Rachel (calm female) : 21m00Tcm4TlvDq8ikWAM  ← default
    //   Josh   (deep male)   : TxGEqnHWrfWFTfGW9XjX
    //   Adam   (neutral male): pNInz6obpgDQGcFmaJgB
    //   Bella  (soft female) : EXAVITQu4vr4xnSDxMaL

    private const val PREFS        = "elevenlabs_prefs"
    private const val KEY_API_KEY  = "api_key"
    private const val KEY_VOICE_ID = "voice_id"

    fun getApiKey(context: Context): String? {
        BuildConfig.ELEVENLABS_API_KEY.takeIf { it.isNotBlank() }?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun getVoiceId(context: Context): String {
        BuildConfig.ELEVENLABS_VOICE_ID.takeIf { it.isNotBlank() }?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VOICE_ID, "21m00Tcm4TlvDq8ikWAM") ?: "21m00Tcm4TlvDq8ikWAM"
    }

    fun setApiKey(context: Context, key: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key).apply()

    fun setVoiceId(context: Context, id: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_VOICE_ID, id).apply()
}

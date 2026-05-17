package com.example.gemmaapp

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

object TtsManager {

    private const val TAG = "TtsManager"
    private const val ELEVENLABS_BASE = "https://api.elevenlabs.io/v1/text-to-speech"

    private lateinit var appContext: Context
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentPlayer: MediaPlayer? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        androidTts = TextToSpeech(appContext) { status ->
            androidTtsReady = (status == TextToSpeech.SUCCESS)
            if (androidTtsReady) {
                androidTts?.language = Locale.getDefault()
                // Pick the highest-quality available voice as fallback
                val best = androidTts?.voices
                    ?.filter { it.locale.language == Locale.getDefault().language && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality }
                if (best != null) androidTts?.voice = best
            }
        }
    }

    suspend fun speak(text: String) {
        val apiKey = ElevenLabsConfig.getApiKey(appContext)
        if (apiKey != null) {
            speakElevenLabs(text, apiKey)
        } else {
            speakAndroid(text)
        }
    }

    private suspend fun speakElevenLabs(text: String, apiKey: String) = withContext(Dispatchers.IO) {
        try {
            val voiceId = ElevenLabsConfig.getVoiceId(appContext)
            val body = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_turbo_v2_5")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                    put("style", 0.3)
                    put("use_speaker_boost", true)
                })
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$ELEVENLABS_BASE/$voiceId")
                .addHeader("xi-api-key", apiKey)
                .addHeader("Accept", "audio/mpeg")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "ElevenLabs error ${response.code}: ${response.body?.string()}")
                speakAndroid(text)
                return@withContext
            }

            val bytes = response.body?.bytes() ?: run {
                speakAndroid(text)
                return@withContext
            }

            val tmpFile = File(appContext.cacheDir, "tts_out.mp3")
            tmpFile.writeBytes(bytes)
            playMp3(tmpFile)
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs speak failed", e)
            speakAndroid(text)
        }
    }

    private fun playMp3(file: File) {
        currentPlayer?.release()
        currentPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
            setOnCompletionListener { it.release() }
        }
    }

    private fun speakAndroid(text: String) {
        if (!androidTtsReady) return
        androidTts?.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        currentPlayer?.stop()
        currentPlayer?.release()
        currentPlayer = null
        androidTts?.stop()
    }

    fun shutdown() {
        stop()
        androidTts?.shutdown()
        androidTts = null
        androidTtsReady = false
    }
}

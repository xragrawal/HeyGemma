package com.example.gemmaapp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

object WakeWordEngine {
    private const val TAG = "WakeWordEngine"
    private const val SAMPLE_RATE = 16000

    private var model: Model? = null

    @Volatile private var isListening = false
    @Volatile var isLoaded = false
        private set

    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread? = null

    // ── Load ─────────────────────────────────────────────────────────────────

    suspend fun load(modelPath: String): String? = withContext(Dispatchers.IO) {
        Log.i(TAG, "load() called with: $modelPath")
        try {
            if (!File(modelPath).exists()) {
                val msg = "Vosk model path not found: $modelPath"
                Log.e(TAG, msg)
                return@withContext msg
            }
            Log.i(TAG, "Model directory exists. Loading...")
            model = Model(modelPath)
            isLoaded = true
            Log.i(TAG, "Vosk model loaded OK.")
            null
        } catch (e: Exception) {
            val msg = "Failed to load Vosk model: ${e.message}"
            Log.e(TAG, msg, e)
            msg
        }
    }

    // ── Listen ────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startListening(onWakeWordDetected: (String) -> Unit): Boolean {
        Log.i(TAG, "startListening() called. isLoaded=$isLoaded, isListening=$isListening, model=${model != null}")

        if (!isLoaded) { Log.e(TAG, "Vosk model not loaded yet"); return false }
        if (isListening) { Log.w(TAG, "Already listening, skipping"); return true }
        if (model == null) { Log.e(TAG, "Model is null"); return false }

        // Create a fresh recognizer for each session
        val recognizer = try {
            Recognizer(model, SAMPLE_RATE.toFloat()).also {
                Log.i(TAG, "Recognizer created OK")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Recognizer: ${e.message}", e)
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        Log.i(TAG, "Min buffer size: $minBuf bytes")

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, 8192)
        )

        Log.i(TAG, "AudioRecord state=${record.state} (1=INITIALIZED)")
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord NOT initialized – permission denied or hw busy")
            record.release()
            recognizer.close()
            return false
        }

        audioRecord = record
        isListening = true
        record.startRecording()
        Log.i(TAG, "AudioRecord.startRecording() called. recordingState=${record.recordingState} (3=RECORDING)")

        listenThread = Thread({
            Log.i(TAG, "Listen thread started")
            val buffer = ShortArray(4096)
            var frameCount = 0
            while (isListening) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    frameCount++
                    if (frameCount % 50 == 0) Log.d(TAG, "Read $frameCount frames, last=$read samples")
                    if (recognizer.acceptWaveForm(buffer, read)) {
                        val result = recognizer.result
                        Log.d(TAG, "Final result: $result")
                        checkResult(result, onWakeWordDetected)
                    } else {
                        val partial = recognizer.partialResult
                        checkResult(partial, onWakeWordDetected)
                    }
                } else {
                    Log.w(TAG, "AudioRecord.read returned $read – possible error")
                }
            }
            Log.i(TAG, "Listen thread exiting after $frameCount frames")
            try { record.stop(); record.release() } catch (_: Exception) {}
            recognizer.close()
        }, "WakeWord-Thread").also { it.isDaemon = true }

        listenThread!!.start()
        return true
    }

    // ── Check result ──────────────────────────────────────────────────────────

    private fun checkResult(jsonStr: String, onWakeWordDetected: (String) -> Unit) {
        try {
            val json = JSONObject(jsonStr)
            val text    = json.optString("text",    "").lowercase().trim()
            val partial = json.optString("partial", "").lowercase().trim()
            val content = if (text.isNotBlank()) text else partial
            if (content.isBlank()) return

            Log.d(TAG, "Vosk heard: [$content]")

            val isGemma = content.contains("hey gemma") ||
                          content.contains("hey jemma") ||
                          content.contains("hey emma")  ||
                          content.contains("hey jem")   ||
                          content.contains("hey jama")  ||
                          content.contains("a gemma")   ||
                          content.contains("gemma")      // broad fallback

            val isEmergency = content.contains("emergency emergency emergency") ||
                              content.contains("emergency emergency")

            when {
                isGemma     -> { Log.i(TAG, "★ Wake word HEY GEMMA detected!"); stopListening(); onWakeWordDetected("hey gemma") }
                isEmergency -> { Log.i(TAG, "★ Wake word EMERGENCY detected!"); stopListening(); onWakeWordDetected("emergency") }
            }
        } catch (_: Exception) {}
    }

    // ── Stop / Release ────────────────────────────────────────────────────────

    fun stopListening() {
        Log.i(TAG, "stopListening() called. isListening=$isListening")
        isListening = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        listenThread?.interrupt()
        listenThread = null
    }

    fun release() {
        stopListening()
        model?.close(); model = null
        isLoaded = false
        Log.i(TAG, "WakeWordEngine released")
    }
}

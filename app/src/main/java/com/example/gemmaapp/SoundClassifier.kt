package com.example.gemmaapp

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

/**
 * Continuous background audio event classifier using an ONNX model bundled in assets.
 *
 * Audio sharing: SoundClassifier does NOT own an AudioRecord. Instead, WakeWordEngine
 * calls feedAudio() from its read loop so both engines share one mic session.
 * ChatViewModel starts SoundClassifier after WakeWordEngine succeeds.
 */
object SoundClassifier {

    private const val TAG                    = "SoundClassifier"
    private const val SAMPLE_RATE            = 16000
    private const val WINDOW_SAMPLES         = 32000   // 2-second windows with 50% overlap
    private const val CONFIDENCE_THRESH      = 0.12f
    private const val ALERT_COOLDOWN_MS      = 30_000L
    private const val MAX_CONSECUTIVE_ERRORS = 5

    enum class SoundEvent(val label: String, val icon: String) {
        AMBULANCE_SIREN("Ambulance Siren",  "🚑"),
        BABY_CRYING    ("Baby Crying",      "👶"),
        FIRE_ALARM     ("Fire Alarm",       "🔥"),
        SMOKE_ALARM    ("Smoke Alarm",      "💨"),
        SIREN          ("Siren",            "🚨"),
        CAR_ALARM      ("Car Alarm",        "🚗"),
        GLASS_BREAK    ("Glass Breaking",   "🪟"),
        CRYING         ("Crying",           "😭")
    }

    // Maps AudioSet class index → SoundEvent. Populated at load() time from labels file.
    private val emergencyClassIndices = mutableMapOf<Int, SoundEvent>()

    @Volatile var isRunning = false
        private set

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val lastAlertMs = mutableMapOf<SoundEvent, Long>()
    private var detectionCallback: ((SoundEvent) -> Unit)? = null

    // Rolling accumulation buffer fed by WakeWordEngine via feedAudio()
    private val sampleBuf = ShortArray(WINDOW_SAMPLES)
    @Volatile private var filled = 0

    // Prevents overlapping inferences — skips a window if previous is still running
    private val inferenceInProgress = AtomicBoolean(false)

    // Protects ortSession/ortEnv: inference holds a read lock, release() holds the write lock.
    private val sessionLock = ReentrantReadWriteLock()

    @Volatile private var consecutiveErrors = 0

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load(context: Context): String? {
        return try {
            // Build keyword → SoundEvent mapping from labels file
            val labels = context.assets.open("sound_classifier_labels.txt")
                .bufferedReader().readLines()

            val keywordMap = mapOf(
                "ambulance"      to SoundEvent.AMBULANCE_SIREN,
                "baby cry"       to SoundEvent.BABY_CRYING,
                "infant cry"     to SoundEvent.BABY_CRYING,
                "baby laugh"     to SoundEvent.BABY_CRYING,   // baby laughter → still needs attention
                "fire alarm"     to SoundEvent.FIRE_ALARM,
                "smoke detector" to SoundEvent.SMOKE_ALARM,
                "smoke alarm"    to SoundEvent.SMOKE_ALARM,
                "siren"          to SoundEvent.SIREN,
                "car alarm"      to SoundEvent.CAR_ALARM,
                "glass"          to SoundEvent.GLASS_BREAK,
                "breaking"       to SoundEvent.GLASS_BREAK,
                "crying"         to SoundEvent.CRYING,
                "sobbing"        to SoundEvent.CRYING,
                "wail"           to SoundEvent.CRYING,        // "Wail, moan" index 25 — common for baby cry
                "moan"           to SoundEvent.CRYING,
                "whimper"        to SoundEvent.CRYING
            )

            emergencyClassIndices.clear()
            labels.forEachIndexed { idx, label ->
                val lower = label.lowercase()
                for ((keyword, event) in keywordMap) {
                    if (lower.contains(keyword)) {
                        emergencyClassIndices[idx] = event
                        break
                    }
                }
            }

            // Extract asset to cache on first run so ONNX can memory-map the file
            // instead of loading the entire model into heap via readBytes().
            val modelPath = extractAssetToCache(context, "sound_classifier.onnx")
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            ortSession = env.createSession(
                modelPath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            )

            consecutiveErrors = 0
            Log.i(TAG, "Loaded OK — ${emergencyClassIndices.size} emergency class mappings")
            null
        } catch (e: Exception) {
            val msg = "SoundClassifier load failed: ${e.message}"
            Log.e(TAG, msg, e)
            msg
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun startListening(onDetection: (SoundEvent) -> Unit): Boolean {
        if (ortSession == null) { Log.e(TAG, "Model not loaded"); return false }
        if (isRunning) return true
        detectionCallback = onDetection
        lastAlertMs.clear()
        consecutiveErrors = 0
        filled = 0
        isRunning = true
        Log.i(TAG, "Started (audio-feed mode — receiving from WakeWordEngine)")
        return true
    }

    fun stopListening() {
        isRunning = false
        // Don't reset filled here — feedAudio() may be mid-write on the WakeWord thread.
        // startListening() resets it safely before the next session begins.
        Log.i(TAG, "Stopped")
    }

    /**
     * Called by WakeWordEngine from its read loop to share raw PCM data.
     * Accumulates samples into a 2-second rolling window; when full, runs inference
     * on a daemon thread (skipped if a previous inference is still in progress).
     */
    fun feedAudio(shorts: ShortArray, length: Int) {
        if (!isRunning) return
        var offset = 0
        while (offset < length) {
            val toCopy = minOf(length - offset, WINDOW_SAMPLES - filled)
            System.arraycopy(shorts, offset, sampleBuf, filled, toCopy)
            filled += toCopy
            offset += toCopy
            if (filled >= WINDOW_SAMPLES) {
                if (inferenceInProgress.compareAndSet(false, true)) {
                    val window = sampleBuf.copyOf()
                    Thread({
                        try { runInference(window) } finally { inferenceInProgress.set(false) }
                    }, "SoundClassifier-Inference").also { it.isDaemon = true }.start()
                }
                // 50% overlap: keep last half regardless of whether inference ran
                System.arraycopy(sampleBuf, WINDOW_SAMPLES / 2, sampleBuf, 0, WINDOW_SAMPLES / 2)
                filled = WINDOW_SAMPLES / 2
            }
        }
    }

    fun release() {
        stopListening()
        // Write lock waits for any in-progress runInference() (which holds a read lock)
        // to complete before closing the ONNX session — prevents a native crash.
        sessionLock.write {
            try { ortSession?.close(); ortEnv?.close() } catch (_: Exception) {}
            ortSession = null
            ortEnv = null
        }
        detectionCallback = null
        emergencyClassIndices.clear()
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    private fun runInference(shorts: ShortArray) {
        // Non-blocking tryLock: if release() holds the write lock, skip this window
        // rather than inferring on a session that's being closed.
        if (!sessionLock.readLock().tryLock()) return
        try {
            val session = ortSession ?: return
            val env     = ortEnv     ?: return

            // Kaldi-style fbank matching the sherpa-onnx zipformer training pipeline:
            // Povey window + preemphasis + Slaney mel scale + natural log + mean norm.
            val melFlat = MelSpectrogram.computeKaldiFbank(shorts)
            // N_FFT=400, HOP=160 → (32000-400)/160+1 = 198 frames for a 2-second window
            val nFrames = ((shorts.size - 400) / 160 + 1).coerceAtMost(3000)

            // Transpose [80, nFrames] → [nFrames, 80]
            val melT = FloatArray(nFrames * 80)
            for (t in 0 until nFrames) {
                for (m in 0 until 80) {
                    melT[t * 80 + m] = melFlat[m * 3000 + t]
                }
            }

            val inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(melT),
                longArrayOf(1L, nFrames.toLong(), 80L)   // [batch, time, mel_bins]
            )
            // x_lens: number of valid frames per batch item
            val lensTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(nFrames.toLong())),
                longArrayOf(1L)
            )

            val results = session.run(mapOf("x" to inputTensor, "x_lens" to lensTensor))
            @Suppress("UNCHECKED_CAST")
            val scores = (results[0].value as Array<FloatArray>)[0]

            // Log all emergency class scores every window so threshold can be tuned from logcat
            val scoreStr = emergencyClassIndices.entries
                .filter { it.key < scores.size }
                .joinToString(" ") { (idx, evt) -> "${evt.name.take(4)}=${String.format("%.3f", scores[idx])}" }
            Log.d(TAG, "scores: $scoreStr")

            val now = System.currentTimeMillis()
            for ((classIdx, event) in emergencyClassIndices) {
                if (classIdx < scores.size && scores[classIdx] >= CONFIDENCE_THRESH) {
                    maybeFireAlert(event, scores[classIdx], now)
                }
            }
            inputTensor.close()
            lensTensor.close()
            results.close()
            consecutiveErrors = 0   // reset on success
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                Log.e(TAG, "Too many consecutive inference errors — stopping classifier")
                stopListening()
            }
        } finally {
            sessionLock.readLock().unlock()
        }
    }

    private fun maybeFireAlert(event: SoundEvent, score: Float, now: Long) {
        val last = lastAlertMs[event] ?: 0L
        if (now - last >= ALERT_COOLDOWN_MS) {
            lastAlertMs[event] = now
            Log.i(TAG, "ALERT: $event (score=$score)")
            detectionCallback?.invoke(event)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Copies [assetName] from assets to the app's cache directory on first run,
     * then returns the file path. ONNX Runtime can memory-map a file path,
     * avoiding the large heap allocation that readBytes() would cause.
     */
    private fun extractAssetToCache(context: Context, assetName: String): String {
        val cacheFile = File(context.cacheDir, assetName)
        if (!cacheFile.exists()) {
            context.assets.open(assetName).use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Extracted $assetName to cache (${cacheFile.length() / 1024}KB)")
        }
        return cacheFile.absolutePath
    }
}

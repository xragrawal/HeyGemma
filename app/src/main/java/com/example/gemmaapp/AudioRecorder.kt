package com.example.gemmaapp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Records audio at 16kHz mono and implements basic Voice Activity Detection (VAD)
 * to automatically stop recording when silence is detected after speaking.
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val SILENCE_THRESHOLD = 2000 // Increased from 500 to avoid ambient noise
        private const val MAX_SILENCE_DURATION_MS = 1500 // Stop after 1.5s of silence
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2 // Double buffer size for safety

    @SuppressLint("MissingPermission") // Handled in MainActivity
    suspend fun recordWithVad(onStateChange: (Boolean) -> Unit): ShortArray = withContext(Dispatchers.IO) {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        val recordedData = mutableListOf<ShortArray>()
        val buffer = ShortArray(bufferSize)
        
        audioRecord?.startRecording()
        isRecording = true
        withContext(Dispatchers.Main) { onStateChange(true) }

        var speakingStarted = false
        var silenceStartTime = 0L

        try {
            while (isRecording) {
                val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readResult > 0) {
                    val validData = buffer.copyOfRange(0, readResult)
                    recordedData.add(validData)

                    // Basic VAD
                    val maxAmplitude = validData.maxOfOrNull { abs(it.toInt()) } ?: 0
                    if (maxAmplitude > SILENCE_THRESHOLD) {
                        speakingStarted = true
                        silenceStartTime = 0L // Reset silence timer
                    } else if (speakingStarted) {
                        if (silenceStartTime == 0L) {
                            silenceStartTime = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - silenceStartTime > MAX_SILENCE_DURATION_MS) {
                            // Silence exceeded threshold, stop recording
                            isRecording = false
                        }
                    }
                }
            }
        } finally {
            stopRecordingInternal()
            withContext(Dispatchers.Main) { onStateChange(false) }
        }

        // Flatten the data
        val totalSize = recordedData.sumOf { it.size }
        val result = ShortArray(totalSize)
        var offset = 0
        for (chunk in recordedData) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        result
    }

    fun stopRecording() {
        isRecording = false
    }

    private fun stopRecordingInternal() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        } finally {
            audioRecord = null
        }
    }
}

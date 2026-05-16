package com.example.gemmaapp

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Computes a log-mel spectrogram from raw audio samples.
 * Designed specifically for Whisper's expected input:
 * 16kHz audio, 80 mel bins, 25ms window, 10ms stride.
 */
object MelSpectrogram {

    private const val SAMPLE_RATE = 16000
    private const val N_FFT = 400 // 25ms window at 16kHz
    private const val HOP_LENGTH = 160 // 10ms stride at 16kHz
    private const val N_MELS = 80
    private const val TARGET_FRAMES = 3000 // 30 seconds

    /**
     * Computes the mel spectrogram and returns a flattened float array of shape [1, 80, 3000].
     * The input is padded or truncated to exactly 3000 frames.
     */
    fun compute(samples: ShortArray): FloatArray {
        // Convert ShortArray to FloatArray and normalize to [-1.0, 1.0]
        val audio = FloatArray(samples.size) { samples[it] / 32768.0f }

        // Number of frames
        val numFrames = (audio.size - N_FFT) / HOP_LENGTH + 1
        val framesToProcess = minOf(numFrames, TARGET_FRAMES)

        val window = hanningWindow(N_FFT)
        val melFilters = computeMelFilters()
        
        // Output tensor: [1, N_MELS, TARGET_FRAMES], flattened
        val output = FloatArray(N_MELS * TARGET_FRAMES)

        // Process each frame
        for (i in 0 until framesToProcess) {
            val start = i * HOP_LENGTH
            val frame = FloatArray(N_FFT)
            for (j in 0 until N_FFT) {
                frame[j] = audio[start + j] * window[j]
            }

            // Pad frame to next power of 2 for FFT
            val fftSize = 512
            val paddedFrame = FloatArray(fftSize)
            System.arraycopy(frame, 0, paddedFrame, 0, N_FFT)

            val magnitudes = computeMagnitudeSpectrum(paddedFrame)

            // Apply mel filters and log10
            for (m in 0 until N_MELS) {
                var melEnergy = 0.0f
                for (k in 0 until (fftSize / 2 + 1)) {
                    melEnergy += melFilters[m][k] * magnitudes[k]
                }
                // Log compression with clamping
                val logEnergy = log10(maxOf(melEnergy, 1e-10f))
                
                // Store in output, transposing to [mel_bin, frame] layout
                output[m * TARGET_FRAMES + i] = logEnergy
            }
        }

        // Apply Whisper-specific dynamic range compression (optional but recommended)
        val maxEnergy = output.maxOrNull() ?: 0f
        for (i in output.indices) {
            output[i] = maxOf(output[i], maxEnergy - 8.0f)
            output[i] = (output[i] + 4.0f) / 4.0f // Normalize
        }

        return output
    }

    private fun hanningWindow(size: Int): FloatArray {
        val window = FloatArray(size)
        for (i in 0 until size) {
            window[i] = 0.5f * (1.0f - cos(2.0 * Math.PI * i / (size - 1))).toFloat()
        }
        return window
    }

    private fun computeMelFilters(): Array<FloatArray> {
        val fMin = 0.0f
        val fMax = SAMPLE_RATE / 2.0f
        val numFftBins = 512 / 2 + 1
        
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = FloatArray(N_MELS + 2) { i ->
            melMin + i * (melMax - melMin) / (N_MELS + 1)
        }
        
        val hzPoints = FloatArray(melPoints.size) { i -> melToHz(melPoints[i]) }
        val binPoints = IntArray(hzPoints.size) { i ->
            Math.floor((512 + 1) * hzPoints[i] / SAMPLE_RATE.toDouble()).toInt()
        }

        val filters = Array(N_MELS) { FloatArray(numFftBins) }
        for (i in 0 until N_MELS) {
            val left = binPoints[i]
            val center = binPoints[i + 1]
            val right = binPoints[i + 2]

            for (j in left until center) {
                filters[i][j] = (j - left).toFloat() / (center - left).toFloat()
            }
            for (j in center until right) {
                filters[i][j] = (right - j).toFloat() / (right - center).toFloat()
            }
        }
        return filters
    }

    private fun hzToMel(hz: Float): Float = 2595.0f * log10(1.0f + hz / 700.0f)
    private fun melToHz(mel: Float): Float = 700.0f * (10.0f.pow(mel / 2595.0f) - 1.0f)

    private fun computeMagnitudeSpectrum(realInput: FloatArray): FloatArray {
        val n = realInput.size
        var m = 0
        while (1 shl m < n) m++

        val real = realInput.copyOf()
        val imag = FloatArray(n)

        // Bit reversal
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // Cooley-Tukey FFT
        for (s in 1..m) {
            val m2 = 1 shl s
            val m1 = m2 shr 1
            val theta = -2.0 * Math.PI / m2
            val wmReal = cos(theta).toFloat()
            val wmImag = sin(theta).toFloat()

            for (k in 0 until n step m2) {
                var wReal = 1.0f
                var wImag = 0.0f
                for (j in 0 until m1) {
                    val tReal = wReal * real[k + j + m1] - wImag * imag[k + j + m1]
                    val tImag = wReal * imag[k + j + m1] + wImag * real[k + j + m1]
                    val uReal = real[k + j]
                    val uImag = imag[k + j]

                    real[k + j] = uReal + tReal
                    imag[k + j] = uImag + tImag
                    real[k + j + m1] = uReal - tReal
                    imag[k + j + m1] = uImag - tImag

                    val nextWReal = wReal * wmReal - wImag * wmImag
                    val nextWImag = wReal * wmImag + wImag * wmReal
                    wReal = nextWReal
                    wImag = nextWImag
                }
            }
        }

        val magnitudes = FloatArray(n / 2 + 1)
        for (i in magnitudes.indices) {
            magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }
        return magnitudes
    }
}

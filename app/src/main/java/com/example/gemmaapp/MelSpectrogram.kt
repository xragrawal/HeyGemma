package com.example.gemmaapp

import kotlin.math.cos
import kotlin.math.ln
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
     * Computes log-mel features without Whisper normalization, returning a flat array
     * laid out as [80, nFrames] where nFrames = (samples.size - N_FFT) / HOP_LENGTH + 1
     * clamped to TARGET_FRAMES.  Used by SoundClassifier — the caller transposes to [T, 80].
     */
    fun computeForClassifier(samples: ShortArray): FloatArray {
        val audio = FloatArray(samples.size) { samples[it] / 32768.0f }
        val numFrames = (audio.size - N_FFT) / HOP_LENGTH + 1
        val framesToProcess = minOf(numFrames, TARGET_FRAMES)
        val window = hanningWindow(N_FFT)
        val melFilters = computeMelFilters()
        val output = FloatArray(N_MELS * TARGET_FRAMES)

        for (i in 0 until framesToProcess) {
            val start = i * HOP_LENGTH
            val frame = FloatArray(N_FFT)
            for (j in 0 until N_FFT) frame[j] = audio[start + j] * window[j]
            val paddedFrame = FloatArray(512)
            System.arraycopy(frame, 0, paddedFrame, 0, N_FFT)
            val magnitudes = computeMagnitudeSpectrum(paddedFrame)
            for (m in 0 until N_MELS) {
                var energy = 0.0f
                for (k in 0 until (512 / 2 + 1)) energy += melFilters[m][k] * magnitudes[k]
                output[m * TARGET_FRAMES + i] = log10(maxOf(energy, 1e-10f))
            }
        }

        // Per-utterance mean subtraction: makes the classifier robust to DC offset / mic gain
        var sum = 0.0f
        var count = 0
        for (t in 0 until framesToProcess) {
            for (m in 0 until N_MELS) { sum += output[m * TARGET_FRAMES + t]; count++ }
        }
        val mean = if (count > 0) sum / count else 0f
        for (i in output.indices) output[i] -= mean

        return output
    }

    /**
     * Kaldi-style log-mel filterbank features matching sherpa-onnx's kaldi-native-fbank pipeline:
     *   - Preemphasis α=0.97
     *   - Povey window (standard for Kaldi ASR/audio-tagging models)
     *   - 80 mel bins, low_freq=20 Hz, Slaney/HTK mel scale
     *   - Natural log (not log10) with energy floor 1e-10
     *   - Per-utterance mean normalisation (CMVN approximation)
     *
     * Returns flat [80 × nFrames] in [mel_bin, frame] layout (same as computeForClassifier).
     * SoundClassifier transposes to [nFrames, 80] before feeding the ONNX model.
     */
    fun computeKaldiFbank(samples: ShortArray): FloatArray {
        val raw = FloatArray(samples.size) { samples[it] / 32768.0f }

        // Preemphasis: x'[n] = x[n] - 0.97 * x[n-1]
        val audio = FloatArray(raw.size)
        audio[0] = raw[0]
        for (i in 1 until raw.size) audio[i] = raw[i] - 0.97f * raw[i - 1]

        val numFrames = (audio.size - N_FFT) / HOP_LENGTH + 1
        val framesToProcess = minOf(numFrames, TARGET_FRAMES)

        // Povey window: pow(0.5 - 0.5*cos(2π*n/(N-1)), 0.85)
        val window = FloatArray(N_FFT) { n ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * n / (N_FFT - 1))).pow(0.85).toFloat()
        }

        val melFilters = computeKaldiMelFilters()
        val output = FloatArray(N_MELS * TARGET_FRAMES)

        for (i in 0 until framesToProcess) {
            val start = i * HOP_LENGTH
            val paddedFrame = FloatArray(512)
            val end = minOf(start + N_FFT, audio.size)
            for (j in start until end) paddedFrame[j - start] = audio[j] * window[j - start]

            val magnitudes = computeMagnitudeSpectrum(paddedFrame)

            for (m in 0 until N_MELS) {
                var energy = 0.0f
                for (k in melFilters[m].indices) energy += melFilters[m][k] * magnitudes[k]
                // Natural log, matching kaldi-native-fbank
                output[m * TARGET_FRAMES + i] = ln(maxOf(energy, 1e-10f))
            }
        }

        // Per-utterance mean subtraction (CMVN approximation)
        var sum = 0.0f
        var n = 0
        for (t in 0 until framesToProcess) {
            for (m in 0 until N_MELS) { sum += output[m * TARGET_FRAMES + t]; n++ }
        }
        val mean = if (n > 0) sum / n else 0f
        for (i in output.indices) output[i] -= mean

        return output
    }

    // Slaney/Kaldi mel filterbank with low_freq=20 Hz (matches kaldi-native-fbank defaults)
    private fun computeKaldiMelFilters(): Array<FloatArray> {
        val lowFreq  = 20.0
        val highFreq = SAMPLE_RATE / 2.0
        val numFftBins = 512 / 2 + 1

        fun hzToMelSlaney(hz: Double): Double = when {
            hz < 1000.0 -> hz / 1000.0 * 15.0
            else        -> 15.0 + ln(hz / 1000.0) / ln(6.4) * 27.0
        }
        fun melToHzSlaney(mel: Double): Double = when {
            mel < 15.0  -> mel / 15.0 * 1000.0
            else        -> 1000.0 * (6.4.pow((mel - 15.0) / 27.0))
        }

        val melLow  = hzToMelSlaney(lowFreq)
        val melHigh = hzToMelSlaney(highFreq)
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            melLow + i * (melHigh - melLow) / (N_MELS + 1)
        }
        val hzPoints = DoubleArray(melPoints.size) { melToHzSlaney(melPoints[it]) }
        val binPoints = IntArray(hzPoints.size) { i ->
            Math.floor((512 + 1) * hzPoints[i] / SAMPLE_RATE).toInt().coerceIn(0, numFftBins - 1)
        }

        val filters = Array(N_MELS) { FloatArray(numFftBins) }
        for (i in 0 until N_MELS) {
            val left   = binPoints[i]
            val center = binPoints[i + 1]
            val right  = binPoints[i + 2]
            for (j in left until center) {
                if (center > left) filters[i][j] = (j - left).toFloat() / (center - left)
            }
            for (j in center until right) {
                if (right > center) filters[i][j] = (right - j).toFloat() / (right - center)
            }
        }
        return filters
    }

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

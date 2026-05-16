package com.example.gemmaapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Handles ONNX Runtime inference for Whisper Small (Encoder + Decoder-with-past).
 */
object WhisperEngine {

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private val vocab = mutableMapOf<Int, String>()

    // Whisper special tokens
    private const val SOT = 50258
    private const val EOT = 50257
    private const val TRANSCRIBE = 50359
    private const val NO_TIMESTAMPS = 50363
    // Assuming English for simplicity, but can be configured
    private const val EN_LANG = 50259

    var isLoaded = false
        private set

    suspend fun load(encoderPath: String, decoderPath: String, vocabPath: String): String? = withContext(Dispatchers.IO) {
        try {
            release()
            
            env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions()
            // Optimize for mobile CPU
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            sessionOptions.setIntraOpNumThreads(4)

            encoderSession = env?.createSession(encoderPath, sessionOptions)
            decoderSession = env?.createSession(decoderPath, sessionOptions)

            loadVocab(vocabPath)
            
            isLoaded = true
            null
        } catch (e: Exception) {
            e.printStackTrace()
            release()
            "Failed to load Whisper: ${e.message}"
        }
    }

    private fun loadVocab(vocabPath: String) {
        val file = File(vocabPath)
        if (!file.exists()) return

        val jsonString = file.readText()
        val json = JSONObject(jsonString)
        
        // Handle standard HuggingFace tokenizer.json
        val model = json.optJSONObject("model")
        val vocabJson = model?.optJSONObject("vocab") ?: return

        vocab.clear()
        vocabJson.keys().forEach { key ->
            val id = vocabJson.getInt(key)
            // Clean up the token string (e.g. replacing 'Ġ' with space)
            var cleanStr = key.replace("Ġ", " ").replace("Ċ", "\n")
            // Handle byte fallbacks and other special chars if necessary
            vocab[id] = cleanStr
        }
    }

    suspend fun transcribe(pcmSamples: ShortArray): String? = withContext(Dispatchers.IO) {
        if (!isLoaded || env == null || encoderSession == null || decoderSession == null) {
            return@withContext null
        }

        try {
            // 1. Compute Mel Spectrogram
            val mel = MelSpectrogram.compute(pcmSamples)
            val melBuffer = FloatBuffer.wrap(mel)
            val melTensor = OnnxTensor.createTensor(env, melBuffer, longArrayOf(1, 80, 3000))

            // 2. Run Encoder
            val encoderInputs = mapOf("input_features" to melTensor)
            val encoderResult = encoderSession?.run(encoderInputs)
                ?: throw Exception("Encoder session run failed")
                
            val hiddenStatesTensor = encoderResult.get(0) as? OnnxTensor 
                ?: throw Exception("Failed to get encoder output tensor")

            // Copy to a new buffer so we can safely close the encoder result
            val hsBuffer = hiddenStatesTensor.floatBuffer
            val floatArray = FloatArray(hsBuffer.remaining())
            hsBuffer.get(floatArray)
            val clonedHsBuffer = FloatBuffer.wrap(floatArray)
            
            val newHsTensor = OnnxTensor.createTensor(env, clonedHsBuffer, hiddenStatesTensor.info.shape)

            // 3. Decoder Loop
            var currentTokens = mutableListOf<Long>(SOT.toLong(), EN_LANG.toLong(), TRANSCRIBE.toLong(), NO_TIMESTAMPS.toLong())
            var transcript = ""

            for (step in 0 until 100) { // Max tokens to generate
                val inputIdsBuffer = LongBuffer.wrap(currentTokens.toLongArray())
                val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, longArrayOf(1, currentTokens.size.toLong()))

                val decoderInputs = mutableMapOf(
                    "input_ids" to inputIdsTensor,
                    "encoder_hidden_states" to newHsTensor
                )
                
                val decoderResult = decoderSession?.run(decoderInputs)
                    ?: throw Exception("Decoder session run failed")
                    
                val logitsTensor = decoderResult.get(0) as? OnnxTensor
                    ?: throw Exception("Failed to get decoder logits tensor")
                
                val logitsBuffer = logitsTensor.floatBuffer
                val shape = logitsTensor.info.shape // usually [batch, sequence, vocab]
                val vocabSize = shape[2].toInt()
                
                // We want the logits for the last token in the sequence
                // Offset = (sequence_length - 1) * vocab_size
                val lastTokenOffset = (shape[1].toInt() - 1) * vocabSize
                
                var maxTokenId = 0
                var maxVal = Float.NEGATIVE_INFINITY
                
                for (i in 0 until vocabSize) {
                    val logit = logitsBuffer.get(lastTokenOffset + i)
                    if (logit > maxVal) {
                        maxVal = logit
                        maxTokenId = i
                    }
                }

                inputIdsTensor.close()
                decoderResult.close()

                if (maxTokenId == EOT) break

                currentTokens.add(maxTokenId.toLong())
                
                // Decode token string
                val tokenStr = vocab[maxTokenId] ?: ""
                // Ignore special tokens in output
                if (!tokenStr.startsWith("<|")) {
                    transcript += tokenStr
                }
            }

            melTensor.close()
            encoderResult?.close()
            newHsTensor.close()

            transcript.trim()
        } catch (e: Exception) {
            e.printStackTrace()
            "Error transcribing: ${e.message}"
        }
    }

    fun release() {
        encoderSession?.close()
        decoderSession?.close()
        env?.close()
        encoderSession = null
        decoderSession = null
        env = null
        isLoaded = false
        vocab.clear()
    }
}

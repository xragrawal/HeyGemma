package com.example.gemmaapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin Kotlin wrapper around the llama.cpp JNI layer.
 * All heavy work is dispatched to IO threads; the callback fires on the
 * calling coroutine's dispatcher (use a Main-scoped collector to update UI).
 */
object LlamaEngine {

    private const val TAG = "LlamaEngine"

    // Number of transformer layers to offload to the Vulkan GPU.
    // -1 = offload everything (recommended for Gemma 4 2B on recent Adreno/Mali).
    private const val GPU_LAYERS = -1

    // Context window size in tokens.
    // Lower context sizes significantly improve initial prompt processing (time-to-first-token)
    private const val N_CTX = 2048

    init {
        System.loadLibrary("gemma_jni")
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    private external fun nativeLoad(modelPath: String, nCtx: Int, nGpuLayers: Int): String?
    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        tokenCallback: Function1<String, Unit>
    ): String?
    private external fun nativeStop()
    private external fun nativeRelease()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Loads a GGUF model from [modelPath].
     * @return null on success, error message on failure.
     */
    suspend fun load(modelPath: String): String? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Loading $modelPath with $GPU_LAYERS GPU layers")
        val err = nativeLoad(modelPath, N_CTX, GPU_LAYERS)
        isLoaded = (err == null)
        err
    }

    /**
     * Streams generated text for [prompt].
     * [onToken] is called on the IO thread for every new piece; collect in a
     * coroutine and post to the main thread as needed.
     *
     * @return null on success, error message on failure.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        onToken: (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        nativeGenerate(prompt, maxTokens, temperature, topP, onToken)
    }

    var isLoaded: Boolean = false
        private set

    /** Interrupt a running generation. Safe to call from any thread. */
    fun stop() = nativeStop()

    /** Free model memory. Call from onDestroy / when model is no longer needed. */
    suspend fun release() = withContext(Dispatchers.IO) {
        nativeRelease()
        isLoaded = false
    }

    // ── Prompt formatting ─────────────────────────────────────────────────────

    /**
     * Wraps [history] in Gemma chat template:
     *   <start_of_turn>user\n{msg}<end_of_turn>\n<start_of_turn>model\n
     */
    fun buildGemmaPrompt(history: List<ChatMessage>): String = buildString {
        for (msg in history) {
            if (msg.isAgentNote) continue          // routing notes are UI-only, never feed to model
            val role = if (msg.isUser) "user" else "model"
            append("<start_of_turn>$role\n${msg.text}<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }
}

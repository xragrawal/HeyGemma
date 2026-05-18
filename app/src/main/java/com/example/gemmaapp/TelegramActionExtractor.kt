package com.example.gemmaapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Extracts action items (add/cancel todos) from incoming Telegram messages and
 * executes them asynchronously without blocking TTS playback.
 *
 * Messages are queued in a bounded Channel (DROP_OLDEST on overflow) and
 * processed one at a time by a single background coroutine.
 * LlamaEngine.generationMutex serialises this with active chat responses.
 */
object TelegramActionExtractor {

    private const val TAG            = "TelegramActionExtractor"
    private const val MAX_MSG_LENGTH = 200
    private const val QUEUE_CAPACITY = 50

    private data class Job(val text: String, val appContext: Context)

    // Bounded: oldest messages silently dropped when full (spam protection)
    private val queue = Channel<Job>(QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        processorScope.launch {
            for (job in queue) {
                // Consumer never dies — any exception in runExtraction is caught here
                try {
                    runExtraction(job.text, job.appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Extraction failed unexpectedly: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Enqueue a message for async action extraction. Returns immediately.
     * Silently drops the message if it exceeds MAX_MSG_LENGTH.
     */
    fun enqueue(messageText: String, context: Context) {
        if (messageText.length > MAX_MSG_LENGTH) {
            Log.d(TAG, "Skipping long message (${messageText.length} chars)")
            return
        }
        val result = queue.trySend(Job(messageText, context.applicationContext))
        if (result.isFailure) {
            Log.w(TAG, "Queue full — oldest message dropped to make room")
        }
    }

    // ── Core extraction ───────────────────────────────────────────────────────

    private suspend fun runExtraction(messageText: String, context: Context) {
        if (!LlamaEngine.isLoaded) return

        val safe = sanitize(messageText)

        val raw = buildString {
            LlamaEngine.generate(
                prompt      = buildPrompt(safe),
                maxTokens   = 48,
                temperature = 0.1f,
                topP        = 0.9f
            ) { token -> append(token) }
        }.trim()

        // generate() returns null when model was released while waiting for the lock
        if (raw.isEmpty()) return

        Log.d(TAG, "Raw output: $raw")

        val json    = parseJson(raw) ?: return
        val action  = json.optString("action", "NONE").uppercase()
        val content = json.optString("content", "").trim()

        when (action) {
            "ADD" -> {
                if (content.isNotBlank()) {
                    TodoRepository.add(content)
                    showToast(context, "Added to todos: \"$content\"")
                    Log.i(TAG, "Added: $content")
                }
            }
            "CANCEL", "DONE", "DELETE" -> {
                if (content.isNotBlank()) {
                    val deleted = cancelMatchingTodo(content)
                    if (deleted != null) {
                        showToast(context, "Removed from todos: \"$deleted\"")
                        Log.i(TAG, "Cancelled: $deleted")
                    } else {
                        Log.d(TAG, "No unambiguous match for: $content")
                    }
                }
            }
            "NONE" -> Log.d(TAG, "No action")
        }
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private fun buildPrompt(message: String) = buildString {
        append("<start_of_turn>user\n")
        append("Extract any task action from this message. Output ONE JSON line only. No other text.\n\n")
        append("Actions:\n")
        append("  ADD    — message asks to create, add, schedule, set up or remember something\n")
        append("  CANCEL — message asks to cancel, remove, delete, drop or forget something\n")
        append("  NONE   — no actionable task, or message uses negation (don't, no need, not)\n\n")
        append("Format:\n")
        append("  {\"action\":\"ADD\",\"content\":\"<the task>\"}\n")
        append("  {\"action\":\"CANCEL\",\"content\":\"<what to cancel>\"}\n")
        append("  {\"action\":\"NONE\"}\n\n")
        append("Examples:\n")
        append("  \"Cancel the meeting at 6pm tomorrow\"   → {\"action\":\"CANCEL\",\"content\":\"meeting at 6pm tomorrow\"}\n")
        append("  \"Setup a meeting at 6pm tomorrow\"      → {\"action\":\"ADD\",\"content\":\"meeting at 6pm tomorrow\"}\n")
        append("  \"Can you send me the report?\"          → {\"action\":\"NONE\"}\n")
        append("  \"Don't forget to call the dentist\"     → {\"action\":\"ADD\",\"content\":\"call the dentist\"}\n")
        append("  \"Drop the 3pm standup\"                 → {\"action\":\"CANCEL\",\"content\":\"3pm standup\"}\n")
        append("  \"Don't add anything for now\"           → {\"action\":\"NONE\"}\n")
        append("  \"No need to schedule the standup\"      → {\"action\":\"NONE\"}\n\n")
        append("Message: \"$message\"\n")
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Strips Gemma chat-template control tokens and normalises quotes so the
     * message text cannot escape its position inside the prompt.
     */
    private fun sanitize(text: String): String = text
        .replace("<end_of_turn>", "", ignoreCase = true)
        .replace("<start_of_turn>", "", ignoreCase = true)
        .replace('"', '\'')
        .trim()

    /**
     * Finds the first balanced {...} pair in [raw] rather than spanning from
     * the first '{' to the last '}', which would merge multiple JSON objects.
     */
    private fun parseJson(raw: String): JSONObject? {
        var depth = 0
        var start = -1
        for (i in raw.indices) {
            when (raw[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start != -1) {
                        return try {
                            JSONObject(raw.substring(start, i + 1))
                        } catch (_: Exception) { null }
                    }
                }
            }
        }
        return null
    }

    /**
     * Finds the todo whose text best matches [target] by Jaccard word similarity.
     * Returns null and does NOT delete if:
     *   - no todos exist
     *   - best score is below the 0.25 threshold
     *   - two or more todos score within 0.05 of each other (ambiguous match)
     */
    private suspend fun cancelMatchingTodo(target: String): String? {
        val todos = TodoRepository.getAll().filter { !it.isDone }
        if (todos.isEmpty()) return null

        val targetWords = target.lowercase().split("\\s+".toRegex()).toSet()

        data class Scored(val todo: com.example.gemmaapp.TodoEntity, val score: Double)

        val scored = todos.map { todo ->
            val todoWords = todo.text.lowercase().split("\\s+".toRegex()).toSet()
            val intersection = todoWords.intersect(targetWords).size.toDouble()
            val union        = (todoWords + targetWords).size.toDouble()
            Scored(todo, if (union == 0.0) 0.0 else intersection / union)
        }

        val best = scored.maxByOrNull { it.score } ?: return null

        if (best.score < 0.25) {
            Log.d(TAG, "Best Jaccard ${best.score} below threshold — skipping delete")
            return null
        }

        // If another todo scores within 0.05 of the best, the match is ambiguous
        val rivals = scored.filter { it.todo.id != best.todo.id && best.score - it.score < 0.05 }
        if (rivals.isNotEmpty()) {
            Log.d(TAG, "Ambiguous cancel — ${rivals.size + 1} todos with similar scores, skipping")
            return null
        }

        TodoRepository.deleteById(best.todo.id)
        return best.todo.text
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

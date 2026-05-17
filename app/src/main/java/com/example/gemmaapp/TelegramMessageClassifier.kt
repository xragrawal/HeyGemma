package com.example.gemmaapp

import android.util.Log

object TelegramMessageClassifier {

    private const val TAG = "TelegramMsgClassifier"

    // ── Keyword fast-paths (no LLM needed for obvious cases) ─────────────────

    private val emergencyKeywords = Regex(
        """\b(emergency|urgent|asap|help|sos|fire|danger|critical|immediately|accident|911|police|ambulance|crisis|panic)\b""",
        RegexOption.IGNORE_CASE
    )

    suspend fun isEmergency(message: String): Boolean {
        if (message.isBlank() || message == "[media]") return false
        // Keyword fast-path — emergency signals are predictable
        if (emergencyKeywords.containsMatchIn(message)) return true
        return askGemma(buildEmergencyPrompt(message))
    }

    // Keyword safety-net for when the LLM isn't loaded — covers obvious health/wellness signals
    private val importantFallbackKeywords = Regex(
        """\b(sick|ill|pain|hurt|injury|fever|hospital|doctor|medicine|medication|not feeling|unwell|help|bleed|dizzy|chest|breathe|allerg|faint|vomit|nausea|anxiety|attack|stroke|died|death|passed away|accident|broke|broken|fell|wound|blood)\b""",
        RegexOption.IGNORE_CASE
    )

    suspend fun isImportant(message: String): Boolean {
        if (message.isBlank() || message == "[media]") return false

        // If model isn't loaded, fall back to keyword matching so messages still get read
        if (!LlamaEngine.isLoaded) {
            val hit = importantFallbackKeywords.containsMatchIn(message)
            Log.d(TAG, "isImportant (keyword fallback) '$message' → $hit")
            return hit
        }

        return askGemma(buildImportantPrompt(message))
    }

    private suspend fun askGemma(prompt: String): Boolean {
        var raw = ""
        LlamaEngine.generate(
            prompt      = prompt,
            maxTokens   = 8,
            temperature = 0.1f,
            topP        = 0.9f
        ) { token -> raw += token }

        // Lenient: check first 20 chars — Gemma may add punctuation or a leading space
        val answer = raw.trim().take(20).uppercase()
        Log.d(TAG, "Classify result → raw='$raw' parsed='$answer'")
        return answer.contains("YES") && !answer.startsWith("NO")
    }

    private fun buildEmergencyPrompt(message: String) = buildString {
        append("<start_of_turn>user\n")
        append("Answer YES or NO only.\n")
        append("Is this an emergency requiring immediate action?\n")
        append("Message: \"$message\"\n")
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    private fun buildImportantPrompt(message: String) = buildString {
        append("<start_of_turn>user\n")
        append("Answer YES or NO only.\n\n")
        append("Is this message important? It is important if it involves health, medical, well-being, work, deadlines, money, family, safety, or any request that needs attention.\n")
        append("It is NOT important if it is just a greeting, joke, or casual chat.\n\n")
        append("'I am not feeling well, help' → YES\n")
        append("'Take your medication now' → YES\n")
        append("'Meeting at 3pm tomorrow' → YES\n")
        append("'Bill payment overdue' → YES\n")
        append("'Hey, how are you?' → NO\n")
        append("'lol funny video' → NO\n\n")
        append("Message: \"$message\"\n")
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }
}

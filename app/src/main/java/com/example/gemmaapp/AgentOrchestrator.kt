package com.example.gemmaapp

import android.util.Log
import org.json.JSONObject

object AgentOrchestrator {

    private const val TAG = "AgentOrchestrator"

    // Max chat turns fed into the direct-agent prompt to keep prefill short
    private const val MAX_HISTORY_MESSAGES = 10

    data class ClassifierResult(
        val service: String,
        val task: String,
        val content: String,
        val recipient: String    = "",
        val contactType: String  = ""   // "GROUP" | "PERSON" | "" (unknown)
    )

    data class Result(
        val agentType: AgentType,
        val displayText: String,
        val error: String?
    )

    // ── Public entry point ────────────────────────────────────────────────────

    suspend fun process(
        userMessage: String,
        chatHistory: List<ChatMessage>,
        onClassified: (ClassifierResult, AgentType) -> Unit,
        onToken: (String) -> Unit
    ): Result {
        val classified = classify(userMessage)
        Log.i(TAG, "Classifier → ${classified.service}/${classified.task} '${classified.content}'")

        val agentType = when (classified.service) {
            "NOTES"    -> AgentType.TODO
            "TELEGRAM" -> AgentType.TELEGRAM
            else       -> AgentType.DIRECT
        }
        onClassified(classified, agentType)

        return when (agentType) {
            AgentType.TODO     -> runTodoAgent(classified, onToken)
            AgentType.TELEGRAM -> runTelegramAgent(classified, onToken)
            AgentType.DIRECT   -> runDirectAgent(chatHistory, onToken)
        }
    }

    // ── Layer 1: Classifier ───────────────────────────────────────────────────

    private suspend fun classify(userMessage: String): ClassifierResult {
        // Telegram SEND: skip regex, use Gemma to extract recipient/content/contactType
        if (isTelegramSendIntent(userMessage)) {
            return extractTelegramParams(userMessage)
        }

        // Fast keyword pass — skips LLM entirely for unambiguous messages
        keywordClassify(userMessage)?.let { return it }

        // LLM fallback for ambiguous cases
        var raw = ""
        LlamaEngine.generate(
            prompt      = buildClassifierPrompt(userMessage),
            maxTokens   = 48,
            temperature = 0.1f,
            topP        = 0.9f
        ) { token -> raw += token }
        return parseClassifierJson(raw.trim())
    }

    private fun isTelegramSendIntent(msg: String): Boolean {
        val lower = msg.lowercase()
        val hasTelegram = lower.contains("telegram") || Regex("""\bbot\b""").containsMatchIn(lower)
        val hasSendIntent = Regex("""\b(send|message|text|tell|write)\b""").containsMatchIn(lower)
        return hasTelegram && hasSendIntent
    }

    private suspend fun extractTelegramParams(userMessage: String): ClassifierResult {
        var raw = ""
        LlamaEngine.generate(
            prompt      = buildTelegramExtractPrompt(userMessage),
            maxTokens   = 64,
            temperature = 0.1f,
            topP        = 0.9f
        ) { token -> raw += token }
        return try {
            val trimmed = raw.trim()
            val start = trimmed.indexOf('{')
            val end   = trimmed.lastIndexOf('}')
            if (start == -1 || end == -1) throw IllegalArgumentException("no braces")
            val json = org.json.JSONObject(trimmed.substring(start, end + 1))
            ClassifierResult(
                service     = "TELEGRAM",
                task        = "SEND",
                content     = json.optString("content",     ""),
                recipient   = json.optString("recipient",   ""),
                contactType = json.optString("contactType", "").uppercase()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Telegram extract failed: '$raw' — fallback DIRECT")
            ClassifierResult("DIRECT", "ANSWER", "")
        }
    }

    private fun keywordClassify(msg: String): ClassifierResult? {
        val lower = msg.lowercase().trim()
        val todoWords = Regex("""\b(todo|todos|note|notes|task|tasks|reminder|reminders)\b""")

        // Clearly a direct question — starts with a question word and has no todo vocab
        val questionStart = Regex("""^(what|who|where|when|why|how|is|are|was|were|can|could|would|will|do|does|did|tell me|explain|describe)\b""")
        if (questionStart.containsMatchIn(lower) && !todoWords.containsMatchIn(lower)) {
            return ClassifierResult("DIRECT", "ANSWER", "")
        }

        // Clearly an ADD — explicit trigger word followed by content
        val addMatch = Regex("""^(add|create|new todo|remind me to|remember to|note down|note that|note:)\s+(.+)""")
            .find(lower)
        if (addMatch != null) {
            val content = addMatch.groupValues[2].trim()
            return ClassifierResult("NOTES", "ADD", content)
        }

        // Clearly a LIST
        val listPattern = Regex("""(list|show|display|view|see|get|what are)\b.*(todo|note|task|reminder|doodle)""")
        if (listPattern.containsMatchIn(lower)) {
            return ClassifierResult("NOTES", "LIST", "")
        }

        // Clearly a CLEAR
        if (Regex("""(clear|delete|remove)\s+all\b""").containsMatchIn(lower)) {
            return ClassifierResult("NOTES", "CLEAR", "")
        }

        // Telegram LIST — "list/show telegram contacts/chats"
        if (Regex("""(list|show|who are)\s+(my\s+)?(telegram|bot)\s+(contacts|chats|users|people)""",
                RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return ClassifierResult("TELEGRAM", "LIST_CONTACTS", "")
        }

        return null  // fall through to LLM classifier
    }

    private fun parseClassifierJson(raw: String): ClassifierResult {
        return try {
            val start = raw.indexOf('{')
            val end   = raw.lastIndexOf('}')
            if (start == -1 || end == -1) throw IllegalArgumentException("no braces")
            val json = JSONObject(raw.substring(start, end + 1))
            ClassifierResult(
                service     = json.optString("service",     "DIRECT").uppercase(),
                task        = json.optString("task",        "ANSWER").uppercase(),
                content     = json.optString("content",     ""),
                recipient   = json.optString("recipient",   ""),
                contactType = json.optString("contactType", "").uppercase()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Classifier parse failed: '$raw' — fallback to DIRECT")
            ClassifierResult("DIRECT", "ANSWER", "")
        }
    }

    // ── Layer 2a: Todo Agent ──────────────────────────────────────────────────
    // All todo operations are formatted locally — zero LLM calls needed.

    private suspend fun runTodoAgent(
        classified: ClassifierResult,
        onToken: (String) -> Unit
    ): Result {
        applyTodoAction(classified)
        val todos = TodoRepository.getAll()
        val text  = formatTodoResponse(classified, todos)
        onToken(text)
        return Result(AgentType.TODO, text, null)
    }

    private suspend fun applyTodoAction(classified: ClassifierResult) {
        when (classified.task) {
            "ADD"    -> classified.content.takeIf { it.isNotBlank() }?.let { TodoRepository.add(it) }
            "DONE"   -> classified.content.toIntOrNull()?.let { TodoRepository.markDone(it) }
            "UNDONE" -> classified.content.toIntOrNull()?.let { TodoRepository.markUndone(it) }
            "DELETE" -> classified.content.toIntOrNull()?.let { TodoRepository.delete(it) }
            "CLEAR"  -> TodoRepository.deleteAll()
        }
    }

    private fun formatTodoResponse(classified: ClassifierResult, todos: List<TodoEntity>): String =
        when (classified.task) {
            "ADD"    -> "Added: \"${classified.content}\"\n\n${formatTodoList(todos)}"
            "DONE"   -> "Marked item ${classified.content} as done.\n\n${formatTodoList(todos)}"
            "UNDONE" -> "Reopened item ${classified.content}.\n\n${formatTodoList(todos)}"
            "DELETE" -> "Deleted item ${classified.content}.\n\n${formatTodoList(todos)}"
            "CLEAR"  -> "All todos cleared."
            "LIST"   -> formatTodoList(todos)
            else     -> formatTodoList(todos)
        }

    private fun formatTodoList(todos: List<TodoEntity>): String = buildString {
        if (todos.isEmpty()) {
            append("Your todo list is empty.")
        } else {
            todos.forEachIndexed { i, item ->
                val check = if (item.isDone) "✓" else "○"
                append("${i + 1}. $check ${item.text}\n")
            }
        }
    }.trimEnd()

    // ── Layer 2b: Direct Agent ────────────────────────────────────────────────

    private suspend fun runDirectAgent(
        chatHistory: List<ChatMessage>,
        onToken: (String) -> Unit
    ): Result {
        val trimmedHistory = chatHistory.takeLast(MAX_HISTORY_MESSAGES)
        val prompt = LlamaEngine.buildGemmaPrompt(trimmedHistory)
        var accumulated = ""
        val error = LlamaEngine.generate(
            prompt      = prompt,
            maxTokens   = 512,
            temperature = 0.7f,
            topP        = 0.9f
        ) { token ->
            accumulated += token
            onToken(token)
        }
        // Strip any chat-template tokens that leaked through (safety net for the C++ stop-token fix)
        val clean = stripTemplateTokens(accumulated)
        return Result(AgentType.DIRECT, clean, error)
    }

    // ── Layer 2c: Telegram Agent ──────────────────────────────────────────────
    // No LLM needed — all operations resolve instantly from the API or local cache.

    private suspend fun runTelegramAgent(
        classified: ClassifierResult,
        onToken: (String) -> Unit
    ): Result {
        val text = when (classified.task) {
            "SEND" -> TelegramRepository.sendMessage(classified.recipient, classified.content, classified.contactType)
            "LIST_CONTACTS" -> TelegramRepository.listContacts()
            else -> "Unknown Telegram command."
        }
        onToken(text)
        return Result(AgentType.TELEGRAM, text, null)
    }

    private val templateTokenRegex = Regex(
        """(<end_of_turn>|<start_of_turn>(user|model)).*""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private fun stripTemplateTokens(text: String) =
        templateTokenRegex.replaceFirst(text, "").trimEnd()

    // ── Prompt builders ───────────────────────────────────────────────────────

    private fun buildTelegramExtractPrompt(userMessage: String) = buildString {
        append("<start_of_turn>user\n")
        append("Extract from the Telegram send request: recipient name (without the word 'group' or 'person'), message content, and whether it targets a GROUP or PERSON.\n")
        append("The words 'group' and 'person' are type labels, NOT part of the name. Strip them from the recipient field.\n")
        append("Output one JSON line only. No other text.\n\n")
        append("Format: {\"recipient\":\"name\",\"content\":\"message\",\"contactType\":\"GROUP\" or \"PERSON\"}\n\n")
        append("Examples:\n")
        append("  'send hi to Jayesh on telegram'                         → {\"recipient\":\"Jayesh\",\"content\":\"hi\",\"contactType\":\"PERSON\"}\n")
        append("  'send hello to group devs on telegram'                  → {\"recipient\":\"devs\",\"content\":\"hello\",\"contactType\":\"GROUP\"}\n")
        append("  'send a message on group testing saying live demo'      → {\"recipient\":\"testing\",\"content\":\"live demo\",\"contactType\":\"GROUP\"}\n")
        append("  'send a telegram message on group hackbot saying demo'  → {\"recipient\":\"hackbot\",\"content\":\"demo\",\"contactType\":\"GROUP\"}\n")
        append("  'message John on telegram saying are you free?'         → {\"recipient\":\"John\",\"content\":\"are you free?\",\"contactType\":\"PERSON\"}\n")
        append("  'send a message to group alpha saying hello'            → {\"recipient\":\"alpha\",\"content\":\"hello\",\"contactType\":\"GROUP\"}\n\n")
        append("Request: $userMessage\n")
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    private fun buildClassifierPrompt(userMessage: String) = buildString {
        append("<start_of_turn>user\n")
        append("Output one JSON line routing the message. No other text.\n\n")
        append("{\"service\":\"NOTES\",\"task\":\"ADD\",\"content\":\"<text>\"}\n")
        append("{\"service\":\"NOTES\",\"task\":\"DONE\",\"content\":\"<n>\"}\n")
        append("{\"service\":\"NOTES\",\"task\":\"UNDONE\",\"content\":\"<n>\"}\n")
        append("{\"service\":\"NOTES\",\"task\":\"DELETE\",\"content\":\"<n>\"}\n")
        append("{\"service\":\"NOTES\",\"task\":\"CLEAR\",\"content\":\"\"}\n")
        append("{\"service\":\"NOTES\",\"task\":\"LIST\",\"content\":\"\"}\n")
        append("{\"service\":\"TELEGRAM\",\"task\":\"SEND\",\"content\":\"<message>\",\"recipient\":\"<name>\",\"contactType\":\"GROUP\"}\n")
        append("{\"service\":\"TELEGRAM\",\"task\":\"SEND\",\"content\":\"<message>\",\"recipient\":\"<name>\",\"contactType\":\"PERSON\"}\n")
        append("{\"service\":\"TELEGRAM\",\"task\":\"LIST_CONTACTS\",\"content\":\"\",\"recipient\":\"\",\"contactType\":\"\"}\n")
        append("{\"service\":\"DIRECT\",\"task\":\"ANSWER\",\"content\":\"\"}\n\n")
        append("Rules:\n")
        append("- ADD only when the user explicitly asks to add/create/remember a NEW specific item.\n")
        append("- LIST when the user wants to see/show/list/display todos — even if misspelled.\n")
        append("- TELEGRAM/SEND when the user wants to send a message to someone via Telegram/the bot.\n")
        append("- TELEGRAM/LIST_CONTACTS when the user asks who they can message on Telegram.\n")
        append("- When intent is ambiguous between ADD and LIST, prefer LIST.\n")
        append("- DIRECT for general questions and conversation.\n\n")
        append("Examples:\n")
        append("  'add buy milk'            → ADD    content=buy milk\n")
        append("  'remind me to call mom'   → ADD    content=call mom\n")
        append("  'list my todos'           → LIST\n")
        append("  'lest out all my doodles' → LIST\n")
        append("  'show my notes'           → LIST\n")
        append("  'what are my tasks'       → LIST\n")
        append("  'what is 2+2'             → DIRECT\n\n")
        append("Message: $userMessage\n")
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }
}

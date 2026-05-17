package com.example.gemmaapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TelegramApi {

    private const val TAG  = "TelegramApi"
    private const val BASE = "https://api.telegram.org/bot"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UpdateResult(
        val contacts: List<TelegramContact>,
        val messages: List<TelegramMessage>,
        val maxUpdateId: Long = -1L   // -1 means no updates received
    )

    // ── Fetch updates, optionally from a given offset ─────────────────────────

    suspend fun getUpdates(token: String, offset: Long = 0): Result<UpdateResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = buildString {
                    append("$BASE$token/getUpdates?limit=100&allowed_updates=[\"message\"]")
                    if (offset > 0) append("&offset=$offset")
                }
                val request = Request.Builder().url(url).build()
                val body    = client.newCall(request).execute().use { it.body?.string() ?: "" }
                Log.d(TAG, "getUpdates (offset=$offset) response: $body")
                parseUpdates(body)
            }.also { if (it.isFailure) Log.e(TAG, "getUpdates error", it.exceptionOrNull()) }
        }

    // ── Verify token via getMe ────────────────────────────────────────────────

    suspend fun getMe(token: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url("$BASE$token/getMe").build()
                val body    = client.newCall(request).execute().use { it.body?.string() ?: "" }
                Log.d(TAG, "getMe response: $body")
                val root = JSONObject(body)
                if (!root.optBoolean("ok")) error("Invalid token or bot not found")
                val result = root.getJSONObject("result")
                val name   = result.optString("first_name", "Bot")
                val username = result.optString("username", "")
                if (username.isNotBlank()) "$name (@$username)" else name
            }.also { if (it.isFailure) Log.e(TAG, "getMe error", it.exceptionOrNull()) }
        }

    // ── Send a message ────────────────────────────────────────────────────────

    suspend fun sendMessage(token: String, chatId: Long, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                }.toString()

                val body    = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$BASE$token/sendMessage")
                    .post(body)
                    .build()

                val resp = client.newCall(request).execute()
                Log.d(TAG, "sendMessage status=${resp.code}")
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
            }.also { if (it.isFailure) Log.e(TAG, "sendMessage error", it.exceptionOrNull()) }
        }

    // ── Parser ────────────────────────────────────────────────────────────────

    private fun parseUpdates(json: String): UpdateResult {
        val root = JSONObject(json)
        if (!root.optBoolean("ok")) return UpdateResult(emptyList(), emptyList())

        val updates      = root.getJSONArray("result")
        val seenContacts = mutableSetOf<Long>()
        val contacts     = mutableListOf<TelegramContact>()
        val messages     = mutableListOf<TelegramMessage>()
        var maxUpdateId  = -1L

        for (i in 0 until updates.length()) {
            val update   = updates.getJSONObject(i)
            val updateId = update.optLong("update_id", -1L)
            if (updateId > maxUpdateId) maxUpdateId = updateId

            val message = update.optJSONObject("message")
                ?: update.optJSONObject("channel_post")
                ?: continue

            val chat     = message.optJSONObject("chat") ?: continue
            val chatId   = chat.getLong("id")
            val chatType = chat.optString("type", "private")
            val chatName = chatName(chat, chatId, chatType)

            if (seenContacts.add(chatId)) {
                contacts.add(TelegramContact(chatId, chatName, chatType))
            }

            val text = message.optString("text", "").ifBlank { "[media]" }
            val from = message.optJSONObject("from")
            val senderName = if (from != null) {
                listOf(from.optString("first_name"), from.optString("last_name"))
                    .filter { it.isNotBlank() }.joinToString(" ")
                    .ifEmpty { from.optString("username", "Unknown") }
            } else chatName

            val msgId     = message.optLong("message_id", System.currentTimeMillis())
            val timestamp = message.optLong("date", 0L) * 1000L

            messages.add(
                TelegramMessage(
                    id         = "in_$msgId",
                    chatId     = chatId,
                    chatName   = chatName,
                    senderName = senderName,
                    text       = text,
                    timestamp  = timestamp,
                    isOutgoing = false
                )
            )
        }
        return UpdateResult(contacts, messages, maxUpdateId)
    }

    private fun chatName(chat: org.json.JSONObject, chatId: Long, type: String): String =
        if (type == "private") {
            listOf(chat.optString("first_name"), chat.optString("last_name"))
                .filter { it.isNotBlank() }.joinToString(" ")
                .ifEmpty { chat.optString("username", chatId.toString()) }
        } else {
            chat.optString("title", chatId.toString())
        }
}

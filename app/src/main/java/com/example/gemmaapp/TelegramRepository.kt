package com.example.gemmaapp

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

object TelegramRepository {

    private lateinit var appContext: Context
    private lateinit var messageDao: TelegramMessageDao
    private lateinit var contactDao: TelegramContactDao

    private val _contacts = MutableStateFlow<List<TelegramContact>>(emptyList())
    val contacts: StateFlow<List<TelegramContact>> = _contacts.asStateFlow()

    suspend fun init(context: Context) {
        appContext  = context.applicationContext
        val db      = TelegramDatabase.getInstance(appContext)
        messageDao  = db.messageDao()
        contactDao  = db.contactDao()
        // Restore persisted contacts into memory on startup
        _contacts.value = contactDao.getAll().map { it.toContact() }
    }

    fun getToken(): String? = TelegramConfig.getToken(appContext)
    fun saveToken(token: String) = TelegramConfig.setToken(appContext, token)

    // ── Live DB flows for UI ──────────────────────────────────────────────────

    fun observeInbox(): Flow<List<TelegramMessage>>              = messageDao.observeInbox()
    fun observeThread(chatId: Long): Flow<List<TelegramMessage>> = messageDao.observeThread(chatId)
    fun observeContacts(): Flow<List<TelegramContactEntity>>     = contactDao.observeAll()

    // ── Used by TelegramActivity ──────────────────────────────────────────────

    suspend fun refreshContacts(): String {
        val token = getToken() ?: return "Bot token not set."
        return TelegramApi.getUpdates(token).fold(
            onSuccess = { result ->
                // Persist contacts so they survive restarts and reflect renames
                val entities = result.contacts.map {
                    TelegramContactEntity(it.chatId, it.name, it.type)
                }
                contactDao.upsertAll(entities)
                _contacts.value = result.contacts

                // Insert new messages; then sync chatName for every known contact
                messageDao.insertAll(result.messages)
                result.contacts.forEach { contact ->
                    messageDao.renameChatId(contact.chatId, contact.name)
                }

                if (result.contacts.isEmpty())
                    "No contacts yet. Have someone message the bot first."
                else
                    "Found ${result.contacts.size} contact(s), ${result.messages.size} message(s)."
            },
            onFailure = { "Error: ${it.message}" }
        )
    }

    // ── Used by AgentOrchestrator ─────────────────────────────────────────────

    suspend fun sendMessage(recipientName: String, message: String, contactType: String = ""): String {
        val token = getToken() ?: return "Telegram not configured. Set the bot token first."

        // Auto-refresh from API if no contacts in memory or DB
        if (_contacts.value.isEmpty()) {
            TelegramApi.getUpdates(token).onSuccess { result ->
                val entities = result.contacts.map {
                    TelegramContactEntity(it.chatId, it.name, it.type)
                }
                contactDao.upsertAll(entities)
                _contacts.value = result.contacts
                messageDao.insertAll(result.messages)
                result.contacts.forEach { contact ->
                    messageDao.renameChatId(contact.chatId, contact.name)
                }
            }
        }

        val isGroup = when (contactType.uppercase()) {
            "GROUP"  -> true
            "PERSON" -> false
            else     -> null
        }

        val contact = findBestMatch(recipientName, isGroup)
        if (contact == null) {
            val available = _contacts.value.joinToString(", ") { it.name }
            return buildString {
                append("Contact \"$recipientName\" not found.")
                if (available.isNotBlank()) append(" Available: $available")
                else append(" No contacts found. Make sure someone has messaged the bot first.")
            }
        }

        return TelegramApi.sendMessage(token, contact.chatId, message).fold(
            onSuccess = {
                messageDao.insert(
                    TelegramMessage(
                        id         = "out_${UUID.randomUUID()}",
                        chatId     = contact.chatId,
                        chatName   = contact.name,
                        senderName = "You",
                        text       = message,
                        timestamp  = System.currentTimeMillis(),
                        isOutgoing = true
                    )
                )
                "Sent to ${contact.name}."
            },
            onFailure = { "Failed to send: ${it.message}" }
        )
    }

    // ── Used by TelegramChatActivity (direct send from UI) ────────────────────

    suspend fun sendMessageToChat(chatId: Long, chatName: String, message: String): String {
        val token = getToken() ?: return "Telegram not configured."
        return TelegramApi.sendMessage(token, chatId, message).fold(
            onSuccess = {
                messageDao.insert(
                    TelegramMessage(
                        id         = "out_${UUID.randomUUID()}",
                        chatId     = chatId,
                        chatName   = chatName,
                        senderName = "You",
                        text       = message,
                        timestamp  = System.currentTimeMillis(),
                        isOutgoing = true
                    )
                )
                ""
            },
            onFailure = { "Failed: ${it.message}" }
        )
    }

    fun listContacts(): String {
        val list = _contacts.value
        if (list.isEmpty()) return "No contacts loaded. Open the Telegram page and tap Refresh."
        return list.joinToString("\n") { "• ${it.name}  [${it.displayType}]" }
    }

    fun findBestMatch(query: String, isGroup: Boolean? = null): TelegramContact? {
        val all = _contacts.value
        if (all.isEmpty()) return null

        val pool = when (isGroup) {
            true  -> all.filter { it.type != "private" }
            false -> all.filter { it.type == "private" }
            null  -> all
        }.ifEmpty { all }

        val q = query.lowercase().trim()

        pool.firstOrNull { it.name.equals(q, ignoreCase = true) }?.let { return it }
        pool.firstOrNull { it.name.lowercase().contains(q) }?.let { return it }

        val queryWords = q.split(Regex("\\W+")).filter { it.length >= 2 }
        if (queryWords.isEmpty()) return null

        return pool
            .map { contact ->
                val nameWords = contact.name.lowercase().split(Regex("\\W+"))
                val score = queryWords.sumOf { qw ->
                    nameWords.count { nw -> nw.contains(qw) || qw.contains(nw) }
                }
                contact to score
            }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    fun findContact(name: String): TelegramContact? = findBestMatch(name)
}

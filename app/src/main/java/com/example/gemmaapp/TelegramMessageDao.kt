package com.example.gemmaapp

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TelegramMessageDao {

    // Latest message per chat — drives the inbox list
    @Query("""
        SELECT * FROM telegram_messages
        WHERE timestamp = (
            SELECT MAX(t2.timestamp) FROM telegram_messages t2
            WHERE t2.chatId = telegram_messages.chatId
        )
        ORDER BY timestamp DESC
    """)
    fun observeInbox(): Flow<List<TelegramMessage>>

    // All messages in a single chat thread
    @Query("SELECT * FROM telegram_messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeThread(chatId: Long): Flow<List<TelegramMessage>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<TelegramMessage>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: TelegramMessage)

    @Query("UPDATE telegram_messages SET chatName = :name WHERE chatId = :chatId")
    suspend fun renameChatId(chatId: Long, name: String)
}

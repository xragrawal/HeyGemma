package com.example.gemmaapp

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "telegram_messages",
    indices = [Index("chatId"), Index("timestamp")]
)
data class TelegramMessage(
    @PrimaryKey val id: String,         // incoming: "in_<messageId>", outgoing: "out_<uuid>"
    val chatId: Long,
    val chatName: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,                // epoch millis
    val isOutgoing: Boolean = false
)

package com.example.gemmaapp

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telegram_contacts")
data class TelegramContactEntity(
    @PrimaryKey val chatId: Long,
    val name: String,
    val type: String
) {
    fun toContact() = TelegramContact(chatId, name, type)
}

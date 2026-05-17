package com.example.gemmaapp

data class TelegramContact(
    val chatId: Long,
    val name: String,
    val type: String   // "private" | "group" | "supergroup" | "channel"
) {
    val displayType: String get() = when (type) {
        "private"    -> "Person"
        "group",
        "supergroup" -> "Group"
        "channel"    -> "Channel"
        else         -> type.replaceFirstChar { it.uppercase() }
    }
}

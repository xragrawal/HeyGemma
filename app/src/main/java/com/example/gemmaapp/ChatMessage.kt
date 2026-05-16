package com.example.gemmaapp

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false
)

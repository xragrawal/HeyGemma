package com.example.gemmaapp

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val agentType: AgentType? = null,
    val isAgentNote: Boolean = false  // classifier routing note, shown between user msg and agent reply
)

package com.claude.remote.features.chat

import com.claude.remote.core.tmux.TmuxSession

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val currentSession: TmuxSession? = null,
    val error: String? = null
)

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
package com.claude.remote.features.chat

import com.claude.remote.core.tmux.TmuxSession
import com.claude.remote.core.ui.components.ConnectionState

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val currentSession: TmuxSession? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val error: String? = null,
    val isVoiceListening: Boolean = false,
    val voicePartialResult: String = "",
    val showPasswordPrompt: Boolean = false,
    val isConnecting: Boolean = false
)

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
package com.claude.remote.features.chat

import com.claude.remote.core.tmux.TmuxSession
import com.claude.remote.core.ui.components.ConnectionState

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isTerminalMode: Boolean = false,
    val sessionName: String = "",
    val isUploading: Boolean = false,
    val uploadFileName: String? = null,
    val error: String? = null,
    val isReconnecting: Boolean = false,
    val isVoiceListening: Boolean = false,
    val voicePartialResult: String = "",
    val wordWrap: Boolean = true,
    val outputChunkCount: Int = 0
)

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
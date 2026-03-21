package com.claude.remote.features.session

import com.claude.remote.core.tmux.TmuxSession
import com.claude.remote.core.ui.components.ConnectionState

data class SessionUiState(
    val sessions: List<TmuxSession> = emptyList(),
    val repos: List<String> = emptyList(),
    val repoHistory: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val isLoading: Boolean = false,
    val isConnecting: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val error: String? = null,
    val showPasswordPrompt: Boolean = false,
    val navigateToSession: String? = null
)
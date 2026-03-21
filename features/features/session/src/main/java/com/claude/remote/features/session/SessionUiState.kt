package com.claude.remote.features.session

import com.claude.remote.core.tmux.TmuxSession

data class SessionUiState(
    val sessions: List<TmuxSession> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showNewSessionDialog: Boolean = false,
    val newSessionName: String = "",
    val newSessionWorkDir: String = "~"
)
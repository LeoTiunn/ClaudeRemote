package com.claude.remote.features.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claude.remote.core.ssh.SshClient
import com.claude.remote.core.tmux.TmuxSession
import com.claude.remote.core.tmux.TmuxSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionSwitcherViewModel @Inject constructor(
    private val sshClient: SshClient,
    private val tmuxSessionManager: TmuxSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val sessions = tmuxSessionManager.listSessions(sshClient)
                _uiState.update { it.copy(sessions = sessions, isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun killSession(sessionName: String) {
        viewModelScope.launch {
            try {
                tmuxSessionManager.killSession(sessionName, sshClient)
                loadSessions()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun showNewSessionDialog() {
        _uiState.update { it.copy(showNewSessionDialog = true) }
    }

    fun dismissNewSessionDialog() {
        _uiState.update { it.copy(showNewSessionDialog = false, newSessionName = "", newSessionWorkDir = "~") }
    }

    fun onNewSessionNameChange(name: String) {
        _uiState.update { it.copy(newSessionName = name) }
    }

    fun onNewSessionWorkDirChange(dir: String) {
        _uiState.update { it.copy(newSessionWorkDir = dir) }
    }

    fun createSession(onCreated: (TmuxSession) -> Unit) {
        val name = _uiState.value.newSessionName.trim()
        val workDir = _uiState.value.newSessionWorkDir.trim()
        if (name.isEmpty()) return

        viewModelScope.launch {
            try {
                val session = tmuxSessionManager.createSession(name, workDir, sshClient)
                dismissNewSessionDialog()
                loadSessions()
                onCreated(session)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
package com.claude.remote.features.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claude.remote.core.ssh.SshClient
import com.claude.remote.core.tmux.TmuxSession
import com.claude.remote.core.tmux.TmuxSessionManager
import com.claude.remote.core.ui.components.ConnectionState
import com.claude.remote.features.settings.SettingsRepository
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
    private val tmuxSessionManager: TmuxSessionManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sshClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state, isConnecting = false) }
                if (state == ConnectionState.CONNECTED) {
                    loadSessionsAndRepos()
                }
            }
        }
        autoConnect()
    }

    private fun autoConnect() {
        if (sshClient.connectionState.value == ConnectionState.CONNECTED) {
            loadSessionsAndRepos()
            return
        }
        if (settingsRepository.hasPassword()) {
            connect()
        } else {
            _uiState.update { it.copy(showPasswordPrompt = true) }
        }
    }

    fun connect() {
        val host = settingsRepository.getSshHost()
        val port = settingsRepository.getSshPort().toIntOrNull() ?: 22
        val username = settingsRepository.getSshUsername()
        val password = settingsRepository.getSshPassword()

        if (password.isEmpty()) {
            _uiState.update { it.copy(showPasswordPrompt = true) }
            return
        }

        _uiState.update { it.copy(isConnecting = true, error = null) }
        viewModelScope.launch {
            try {
                sshClient.connect(host, port, username, password)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Connection failed: ${e.message}", isConnecting = false)
                }
            }
        }
    }

    fun connectWithPassword(password: String) {
        settingsRepository.setSshPassword(password)
        _uiState.update { it.copy(showPasswordPrompt = false) }
        connect()
    }

    fun dismissPasswordPrompt() {
        _uiState.update { it.copy(showPasswordPrompt = false) }
    }

    fun loadSessionsAndRepos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val sessions = tmuxSessionManager.listSessions(sshClient)
                val repos = tmuxSessionManager.listRemoteRepos(sshClient)
                _uiState.update {
                    it.copy(sessions = sessions, repos = repos, isLoading = false, error = null)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = "Load failed: ${e.javaClass.simpleName}: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun attachSession(sessionName: String) {
        viewModelScope.launch {
            try {
                tmuxSessionManager.attachToSession(sessionName, sshClient)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun createSessionFromRepo(repo: String): TmuxSession {
        val sessionName = repo.substringAfterLast("/")
        val workDir = "~/Developer/$repo"
        viewModelScope.launch {
            try {
                tmuxSessionManager.createSession(sessionName, workDir, sshClient)
                // Small delay then attach
                kotlinx.coroutines.delay(500)
                tmuxSessionManager.attachToSession(sessionName, sshClient)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
        return TmuxSession(name = sessionName, windowName = sessionName)
    }

    fun killSession(sessionName: String) {
        viewModelScope.launch {
            try {
                tmuxSessionManager.killSession(sessionName, sshClient)
                loadSessionsAndRepos()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
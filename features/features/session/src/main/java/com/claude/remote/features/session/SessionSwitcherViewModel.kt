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

    @Volatile private var loadingInProgress = false

    init {
        _uiState.update { it.copy(repoHistory = settingsRepository.getRepoHistory()) }

        viewModelScope.launch {
            sshClient.connectionState.collect { state ->
                val wasDisconnected = _uiState.value.connectionState != ConnectionState.CONNECTED
                _uiState.update { it.copy(connectionState = state, isConnecting = false) }
                if (state == ConnectionState.CONNECTED && wasDisconnected) {
                    loadSessionsAndRepos()
                }
            }
        }
        autoConnect()
    }

    fun onScreenVisible() {
        val currentState = sshClient.connectionState.value
        _uiState.update { it.copy(
            repoHistory = settingsRepository.getRepoHistory(),
            connectionState = currentState
        ) }
        if (currentState == ConnectionState.CONNECTED) {
            loadSessionsAndRepos()
        }
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

    /** Temporarily bypass isAttachedToTmux flag for command shell operations */
    private inline fun <T> withCommandShell(block: () -> T): T {
        val wasAttached = sshClient.isAttachedToTmux
        sshClient.isAttachedToTmux = false
        return try {
            block()
        } finally {
            sshClient.isAttachedToTmux = wasAttached
        }
    }

    fun loadSessionsAndRepos() {
        if (loadingInProgress) return
        loadingInProgress = true
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val sessions = withCommandShell {
                    tmuxSessionManager.listSessions(sshClient)
                }
                _uiState.update {
                    it.copy(sessions = sessions, isLoading = false, error = null)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = "Load failed: ${e.javaClass.simpleName}: ${e.message}",
                        isLoading = false
                    )
                }
            } finally {
                loadingInProgress = false
            }
        }
    }

    fun searchRepos(query: String) {
        if (query.length < 2) {
            _uiState.update { it.copy(repos = emptyList(), isSearching = false) }
            return
        }
        viewModelScope.launch {
            try {
                val output = withCommandShell {
                    sshClient.executeCommand(
                        "find ~/Developer -maxdepth 3 -type d -name .git 2>/dev/null | sed 's|/\\.git\$||' | sed 's|.*/Developer/||' | grep -i '${query.replace("'", "\\'")}' | sort | head -20"
                    )
                }
                val repos = output.lines().filter { it.isNotBlank() }
                _uiState.update { it.copy(repos = repos, isSearching = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Search failed: ${e.message}") }
            }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(repos = emptyList(), isSearching = false) }
    }

    fun attachSession(sessionName: String) {
        _uiState.update { it.copy(repos = emptyList(), isSearching = false) }
        sshClient.currentSessionName = sessionName
        _uiState.update { it.copy(navigateToSession = sessionName) }
    }

    fun onNavigated() {
        _uiState.update { it.copy(navigateToSession = null) }
    }

    fun createSessionFromRepo(repo: String) {
        val sessionName = repo.substringAfterLast("/")
        val workDir = "\$HOME/Developer/$repo"
        settingsRepository.addRepoToHistory(repo)
        _uiState.update { it.copy(repos = emptyList(), isSearching = false, repoHistory = settingsRepository.getRepoHistory()) }
        viewModelScope.launch {
            try {
                withCommandShell {
                    tmuxSessionManager.createSession(sessionName, workDir, sshClient)
                }
                kotlinx.coroutines.delay(500)
                sshClient.currentSessionName = sessionName
                _uiState.update { it.copy(navigateToSession = sessionName) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun killSession(sessionName: String) {
        viewModelScope.launch {
            try {
                withCommandShell {
                    tmuxSessionManager.killSession(sessionName, sshClient)
                }
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

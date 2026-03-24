package com.claude.remote.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.claude.remote.features.chat.TerminalWebViewHolder
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    val appUpdater: AppUpdater,
    private val webViewHolder: TerminalWebViewHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow(repository.loadAll())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun onSshHostChange(host: String) {
        _uiState.update { it.copy(sshHost = host) }
        repository.setSshHost(host)
    }

    fun onSshPortChange(port: String) {
        _uiState.update { it.copy(sshPort = port) }
        repository.setSshPort(port)
    }

    fun onSshUsernameChange(username: String) {
        _uiState.update { it.copy(sshUsername = username) }
        repository.setSshUsername(username)
    }

    fun onTmuxPathChange(path: String) {
        _uiState.update { it.copy(tmuxPath = path) }
        repository.setTmuxPath(path)
    }

    fun onClaudePathChange(path: String) {
        _uiState.update { it.copy(claudePath = path) }
        repository.setClaudePath(path)
    }

    fun onThemeChange(theme: AppTheme) {
        _uiState.update { it.copy(theme = theme) }
        repository.setTheme(theme)
    }

    fun onFontSizeChange(size: Float) {
        _uiState.update { it.copy(fontSize = size) }
        repository.setFontSize(size)
        webViewHolder.fontSize = size
        webViewHolder.webView?.post {
            webViewHolder.webView?.evaluateJavascript("if(window.setFontSize)setFontSize($size)", null)
        }
    }

    fun onPasswordSave(password: String) {
        repository.setSshPassword(password)
        _uiState.update { it.copy(showPasswordDialog = false) }
    }

    fun onPasswordClear() {
        repository.clearPassword()
    }

    fun showPasswordDialog() {
        _uiState.update { it.copy(showPasswordDialog = true) }
    }

    fun dismissPasswordDialog() {
        _uiState.update { it.copy(showPasswordDialog = false) }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            appUpdater.checkForUpdate()
        }
    }

    fun downloadAndInstallUpdate() {
        appUpdater.downloadAndInstall()
    }
}

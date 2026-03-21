package com.claude.remote.features.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun onSshHostChange(host: String) {
        _uiState.update { it.copy(sshHost = host) }
    }

    fun onSshPortChange(port: String) {
        _uiState.update { it.copy(sshPort = port) }
    }

    fun onSshUsernameChange(username: String) {
        _uiState.update { it.copy(sshUsername = username) }
    }

    fun onTmuxPathChange(path: String) {
        _uiState.update { it.copy(tmuxPath = path) }
    }

    fun onClaudePathChange(path: String) {
        _uiState.update { it.copy(claudePath = path) }
    }

    fun onThemeChange(theme: AppTheme) {
        _uiState.update { it.copy(theme = theme) }
    }

    fun onFontSizeChange(size: Float) {
        _uiState.update { it.copy(fontSize = size) }
    }
}
package com.claude.remote.features.settings

data class SettingsUiState(
    val sshHost: String = "asune.asuscomm.com",
    val sshPort: String = "22",
    val sshUsername: String = "leo.chang",
    val tmuxPath: String = "tmux",
    val claudePath: String = "claude",
    val theme: AppTheme = AppTheme.SYSTEM,
    val fontSize: Float = 16f,
    val showPasswordDialog: Boolean = false
)

enum class AppTheme {
    LIGHT, DARK, SYSTEM
}
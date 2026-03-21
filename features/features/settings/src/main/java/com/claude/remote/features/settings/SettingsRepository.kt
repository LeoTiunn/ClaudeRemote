package com.claude.remote.features.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getSshHost(): String = prefs.getString("ssh_host", "asune.asuscomm.com") ?: "asune.asuscomm.com"
    fun setSshHost(host: String) = prefs.edit().putString("ssh_host", host).apply()

    fun getSshPort(): String = prefs.getString("ssh_port", "22") ?: "22"
    fun setSshPort(port: String) = prefs.edit().putString("ssh_port", port).apply()

    fun getSshUsername(): String = prefs.getString("ssh_username", "leo.chang") ?: "leo.chang"
    fun setSshUsername(username: String) = prefs.edit().putString("ssh_username", username).apply()

    fun getSshPassword(): String = securePrefs.getString("ssh_password", "") ?: ""
    fun setSshPassword(password: String) =
        securePrefs.edit().putString("ssh_password", password).apply()

    fun hasPassword(): Boolean =
        securePrefs.contains("ssh_password") && getSshPassword().isNotEmpty()

    fun clearPassword() = securePrefs.edit().remove("ssh_password").apply()

    fun getTmuxPath(): String = prefs.getString("tmux_path", "tmux") ?: "tmux"
    fun setTmuxPath(path: String) = prefs.edit().putString("tmux_path", path).apply()

    fun getClaudePath(): String = prefs.getString("claude_path", "claude") ?: "claude"
    fun setClaudePath(path: String) = prefs.edit().putString("claude_path", path).apply()

    fun getTheme(): AppTheme =
        AppTheme.valueOf(prefs.getString("theme", "SYSTEM") ?: "SYSTEM")

    fun setTheme(theme: AppTheme) = prefs.edit().putString("theme", theme.name).apply()

    fun getFontSize(): Float = prefs.getFloat("font_size", 16f)
    fun setFontSize(size: Float) = prefs.edit().putFloat("font_size", size).apply()

    fun loadAll(): SettingsUiState = SettingsUiState(
        sshHost = getSshHost(),
        sshPort = getSshPort(),
        sshUsername = getSshUsername(),
        tmuxPath = getTmuxPath(),
        claudePath = getClaudePath(),
        theme = getTheme(),
        fontSize = getFontSize()
    )
}

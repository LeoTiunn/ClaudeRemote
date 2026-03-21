package com.claude.remote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.claude.remote.app.navigation.AppNavigation
import com.claude.remote.core.ui.theme.ClaudeRemoteTheme
import com.claude.remote.features.chat.TerminalWebViewHolder
import com.claude.remote.features.settings.AppTheme
import com.claude.remote.features.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var webViewHolder: TerminalWebViewHolder

    private val _themeState = MutableStateFlow(AppTheme.SYSTEM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by _themeState.collectAsState()
            val darkTheme = when (theme) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            ClaudeRemoteTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavigation(navController = navController)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settingsRepository.isInitialized) {
            _themeState.value = settingsRepository.getTheme()
            val fontSize = settingsRepository.getFontSize()
            webViewHolder.fontSize = fontSize
            // Apply to live WebView if it exists
            webViewHolder.webView?.post {
                webViewHolder.webView?.evaluateJavascript(
                    "if(window.setFontSize)setFontSize($fontSize)", null
                )
            }
        }
    }
}

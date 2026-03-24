package com.claude.remote.features.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placeholder — WebView is no longer used for terminal display.
 * Kept for DI compatibility. Can be removed in a future cleanup.
 */
@Singleton
class TerminalWebViewHolder @Inject constructor() {
    @Volatile var fontSize: Float = 13f
    @Volatile var isDarkTheme: Boolean = true
}

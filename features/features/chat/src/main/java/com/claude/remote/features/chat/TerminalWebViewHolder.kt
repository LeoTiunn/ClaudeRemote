package com.claude.remote.features.chat

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the terminal WebView alive across navigation events.
 * WebView is display-only — keyboard input goes through native TextField.
 */
@Singleton
class TerminalWebViewHolder @Inject constructor() {
    var webView: WebView? = null
        private set

    var isInitialized: Boolean = false
        private set

    @Volatile var fontSize: Float = 13f
    @Volatile var isDarkTheme: Boolean = true

    var onResize: ((cols: Int, rows: Int) -> Unit)? = null

    fun getOrCreate(context: Context): WebView {
        return webView ?: WebView(context).also {
            webView = it
            isInitialized = false
        }
    }

    fun markInitialized() {
        isInitialized = true
    }

    fun detachFromParent() {
        (webView?.parent as? ViewGroup)?.removeView(webView)
    }

    fun destroy() {
        webView?.destroy()
        webView = null
        isInitialized = false
    }
}

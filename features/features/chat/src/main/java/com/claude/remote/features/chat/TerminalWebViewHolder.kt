package com.claude.remote.features.chat

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the terminal WebView alive across navigation events.
 * The WebView is created once with application context and reused,
 * so terminal state is preserved when navigating to Settings and back.
 */
@Singleton
class TerminalWebViewHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    var webView: WebView? = null
        private set

    var isInitialized: Boolean = false
        private set

    @Volatile var fontSize: Float = 13f

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(): WebView {
        return webView ?: WebView(context).also {
            webView = it
            isInitialized = false
        }
    }

    fun markInitialized() {
        isInitialized = true
    }

    /** Detach from current parent so it can be added to a new one */
    fun detachFromParent() {
        (webView?.parent as? ViewGroup)?.removeView(webView)
    }

    fun destroy() {
        webView?.destroy()
        webView = null
        isInitialized = false
    }
}

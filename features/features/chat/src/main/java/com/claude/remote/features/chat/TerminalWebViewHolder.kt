package com.claude.remote.features.chat

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalWebViewHolder @Inject constructor() {
    var webView: WebView? = null
        private set

    var isInitialized: Boolean = false
        private set

    @Volatile var fontSize: Float = 13f
    @Volatile var isDarkTheme: Boolean = true

    var onInput: ((String) -> Unit)? = null
    var onResize: ((cols: Int, rows: Int) -> Unit)? = null

    fun getOrCreate(context: Context): WebView {
        return webView ?: WebView(context).also {
            webView = it
            isInitialized = false
        }
    }

    fun markInitialized() { isInitialized = true }

    fun detachFromParent() {
        (webView?.parent as? ViewGroup)?.removeView(webView)
    }

    fun destroy() {
        webView?.destroy()
        webView = null
        isInitialized = false
    }
}

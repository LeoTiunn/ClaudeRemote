package com.claude.remote.features.chat

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
        return webView ?: object : WebView(context) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val ic = super.onCreateInputConnection(outAttrs)
                // Tell keyboard: plain text, no suggestions/predictions
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                return ic
            }
        }.also {
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

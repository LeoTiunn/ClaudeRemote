package com.claude.remote.features.chat

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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

    // false = English (TYPE_NULL, immediate keys)
    // true = Chinese (TYPE_CLASS_TEXT, composing allowed)
    var chineseMode: Boolean = false

    fun getOrCreate(context: Context): WebView {
        return webView ?: object : WebView(context) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val ic = super.onCreateInputConnection(outAttrs)
                outAttrs.inputType = if (chineseMode) {
                    InputType.TYPE_CLASS_TEXT
                } else {
                    InputType.TYPE_NULL
                }
                return ic
            }
        }.also {
            webView = it
            isInitialized = false
        }
    }

    fun toggleChineseMode() {
        chineseMode = !chineseMode
        // Force IME to re-read inputType
        val wv = webView ?: return
        val imm = wv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(wv)
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

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

    fun getOrCreate(context: Context): WebView {
        return webView ?: object : WebView(context) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val ic = super.onCreateInputConnection(outAttrs)
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                @Suppress("DEPRECATION")
                val subtype = imm.currentInputMethodSubtype
                val lang = subtype?.languageTag ?: subtype?.locale ?: "en"

                outAttrs.inputType = if (lang.startsWith("zh") || lang.startsWith("ja") || lang.startsWith("ko")) {
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

package com.claude.remote.features.chat

import android.content.Context
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the terminal WebView alive across navigation events.
 * WebView must be created with Activity context (not Application) for keyboard to work.
 * Call getOrCreate(activityContext) the first time.
 */
@Singleton
class TerminalWebViewHolder @Inject constructor() {
    var webView: WebView? = null
        private set

    var isInitialized: Boolean = false
        private set

    @Volatile var fontSize: Float = 13f
    @Volatile var isDarkTheme: Boolean = true

    // Mutable callback refs — updated by TerminalView composable,
    // called by the JS interface on the singleton WebView
    var onInput: ((String) -> Unit)? = null
    var onResize: ((cols: Int, rows: Int) -> Unit)? = null

    fun getOrCreate(context: Context): WebView {
        return webView ?: object : WebView(context) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val ic = super.onCreateInputConnection(outAttrs) ?: return null
                return TerminalInputConnection(ic)
            }
        }.also {
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

/**
 * Wraps the WebView InputConnection to handle IME composing:
 * - ASCII single chars (regular typing): commit immediately, skip prediction bar
 * - Non-ASCII (Chinese pinyin candidates, etc.): let composing work normally
 * - Swipe typing: commitText is called directly by Gboard, works as-is
 */
private class TerminalInputConnection(
    target: InputConnection
) : InputConnectionWrapper(target, true) {

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null && text.isNotEmpty() && isPlainAscii(text)) {
            // English typing: commit immediately instead of composing
            return commitText(text, newCursorPosition)
        }
        // Chinese/other IME: let composing work normally
        return super.setComposingText(text, newCursorPosition)
    }

    private fun isPlainAscii(text: CharSequence): Boolean {
        for (i in text.indices) {
            if (text[i].code > 127) return false
        }
        return true
    }
}

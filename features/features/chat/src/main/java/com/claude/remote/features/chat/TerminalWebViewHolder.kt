package com.claude.remote.features.chat

import android.content.Context
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
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
                val ic = super.onCreateInputConnection(outAttrs) ?: return null
                return SmartInputConnection(ic) { text ->
                    onInput?.invoke(text)
                }
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

/**
 * Two modes:
 * - ASCII composing (English): send each new character immediately via delta tracking
 * - Non-ASCII composing (Chinese pinyin): let composing work normally, send on commitText
 */
private class SmartInputConnection(
    target: InputConnection,
    private val send: (String) -> Unit
) : InputConnectionWrapper(target, true) {

    private var asciiLen = 0 // length of ASCII composing text we've already sent

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null || text.isEmpty()) {
            asciiLen = 0
            return super.setComposingText(text, newCursorPosition)
        }

        val str = text.toString()
        if (str.all { it.code <= 127 }) {
            // English: send delta only
            val newLen = str.length
            if (newLen > asciiLen) {
                send(str.substring(asciiLen))
            } else if (newLen < asciiLen) {
                repeat(asciiLen - newLen) { send("\u007F") }
            }
            asciiLen = newLen
            return true // consume — don't pass to WebView composing
        }

        // Chinese: composing normally
        asciiLen = 0
        return super.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null || text.isEmpty()) {
            asciiLen = 0
            return true
        }

        val str = text.toString()
        if (asciiLen > 0) {
            // English composing was already sent char-by-char.
            // commitText may add trailing space/punctuation.
            if (str.length > asciiLen) {
                send(str.substring(asciiLen))
            }
            asciiLen = 0
            return true
        }

        // Direct commit: Chinese character, voice dictation, etc.
        send(str)
        asciiLen = 0
        return true
    }

    override fun finishComposingText(): Boolean {
        asciiLen = 0
        return super.finishComposingText()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) {
            repeat(beforeLength) { send("\u007F") }
        }
        asciiLen = 0
        return super.deleteSurroundingText(beforeLength, afterLength)
    }
}

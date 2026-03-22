package com.claude.remote.features.chat

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
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
        return webView ?: TerminalWebView(context).also {
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
 * Custom WebView that auto-commits composing text so characters go to
 * xterm.js immediately, while still allowing swipe typing (which uses
 * commitText directly when the swipe gesture completes).
 */
class TerminalWebView(context: Context) : WebView(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(ic)
    }
}

/**
 * Intercepts setComposingText and immediately commits it instead.
 * This prevents the prediction bar from holding characters.
 * Swipe typing bypasses composing and calls commitText directly.
 */
class TerminalInputConnection(
    target: InputConnection
) : InputConnectionWrapper(target, true) {

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // Don't compose — commit each character immediately
        if (text != null && text.isNotEmpty()) {
            return commitText(text, newCursorPosition)
        }
        return super.setComposingText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        return super.finishComposingText()
    }
}

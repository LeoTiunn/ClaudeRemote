package com.claude.remote.features.chat

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the terminal WebView alive across navigation events.
 * WebView is display-only — keyboard input is handled by TerminalInputProxy.
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
        val holder = this
        return webView ?: object : WebView(context) {
            // Termux-style BaseInputConnection: handles composing (swipe),
            // commit, and backspace properly
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
                outAttrs.inputType = InputType.TYPE_NULL
                outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                return object : BaseInputConnection(this, true) {
                    override fun finishComposingText(): Boolean {
                        super.finishComposingText()
                        val text = editable?.toString() ?: ""
                        if (text.isNotEmpty()) {
                            holder.onInput?.invoke(text)
                            editable?.clear()
                        }
                        return true
                    }

                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                        super.commitText(text, newCursorPosition)
                        val content = editable?.toString() ?: ""
                        if (content.isNotEmpty()) {
                            holder.onInput?.invoke(content)
                            editable?.clear()
                        }
                        return true
                    }

                    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                        repeat(beforeLength) { holder.onInput?.invoke("\u007f") }
                        return super.deleteSurroundingText(beforeLength, afterLength)
                    }

                    override fun sendKeyEvent(event: KeyEvent?): Boolean {
                        if (event?.action == KeyEvent.ACTION_DOWN) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_DEL -> {
                                    holder.onInput?.invoke("\u007f")
                                    return true
                                }
                                KeyEvent.KEYCODE_ENTER -> {
                                    holder.onInput?.invoke("\r")
                                    return true
                                }
                            }
                        }
                        return super.sendKeyEvent(event)
                    }
                }
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

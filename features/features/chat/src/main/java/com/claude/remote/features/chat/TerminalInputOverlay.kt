package com.claude.remote.features.chat

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import com.claude.remote.core.ssh.DebugLog

/**
 * Invisible EditText that captures all keyboard input (regular typing,
 * swipe, Chinese pinyin, voice dictation) and forwards committed text
 * to the terminal via [onTerminalInput].
 *
 * Sits on top of (or below) the WebView which is display-only.
 */
class TerminalInputOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : EditText(context, attrs) {

    var onTerminalInput: ((String) -> Unit)? = null

    init {
        // Invisible: no background, no cursor blinking visible, 1px tall
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.TRANSPARENT)
        isCursorVisible = false
        // Single line, send action
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        // Minimal height so it doesn't take space but still receives focus
        minHeight = 1
        setPadding(0, 0, 0, 0)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return TerminalIC(ic)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle special keys that don't go through InputConnection
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onTerminalInput?.invoke("\u007F") // DEL / backspace
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                onTerminalInput?.invoke("\r")
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                onTerminalInput?.invoke("\t")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * InputConnection wrapper that intercepts committed text and forwards
     * it to the terminal. Composing text is left alone (shown in the
     * EditText / prediction bar) until finalized.
     */
    private inner class TerminalIC(
        target: InputConnection
    ) : InputConnectionWrapper(target, true) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text != null && text.isNotEmpty()) {
                DebugLog.log("INPUT", "commitText: '${text.toString().take(50)}'")
                onTerminalInput?.invoke(text.toString())
            }
            // Commit to the EditText so it clears composing state,
            // then clear the EditText content
            val result = super.commitText(text, newCursorPosition)
            post { setText("") }
            return result
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Gboard sends this for backspace when EditText has content
            if (beforeLength > 0) {
                onTerminalInput?.invoke("\u007F")
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
    }
}

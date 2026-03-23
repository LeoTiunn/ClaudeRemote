package com.claude.remote.features.chat

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import com.claude.remote.core.ssh.DebugLog

/**
 * Native Android EditText that captures all keyboard input and forwards
 * it to the terminal via SSH. Bypasses WebView's flaky InputConnection.
 *
 * Supports both regular typing and swipe typing:
 * - Composing text (swipe preview) is handled natively by Android
 * - Only committed text gets sent to the terminal
 * - Backspace/delete handled via deleteSurroundingText and key events
 */
class TerminalInputProxy(context: Context) : EditText(context) {

    var onTerminalInput: ((String) -> Unit)? = null

    init {
        // Allow composing for swipe typing — no VISIBLE_PASSWORD restriction
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        // Make invisible but still focusable for keyboard
        alpha = 0f
        setBackgroundColor(0)
        isCursorVisible = false
        height = 1
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return TerminalInputConnection(base)
    }

    private inner class TerminalInputConnection(
        base: InputConnection
    ) : InputConnectionWrapper(base, true) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text != null && text.isNotEmpty()) {
                DebugLog.log("INPUT_PROXY", "commitText: '${text.toString().take(50)}' (${text.length} chars)")
                onTerminalInput?.invoke(text.toString())
            }
            // Clear the field so it doesn't accumulate
            super.setComposingText("", 1)
            super.commitText("", 1)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // Let composing happen natively (swipe preview on keyboard)
            // Don't send to terminal — only commitText sends
            return super.setComposingText(text, newCursorPosition)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Send backspace for each deleted character
            if (beforeLength > 0) {
                DebugLog.log("INPUT_PROXY", "deleteSurrounding: $beforeLength chars")
                repeat(beforeLength) {
                    onTerminalInput?.invoke("\u007f")
                }
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event?.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        DebugLog.log("INPUT_PROXY", "KeyEvent DEL")
                        onTerminalInput?.invoke("\u007f")
                        return true
                    }
                    KeyEvent.KEYCODE_ENTER -> {
                        DebugLog.log("INPUT_PROXY", "KeyEvent ENTER")
                        onTerminalInput?.invoke("\r")
                        return true
                    }
                }
            }
            return super.sendKeyEvent(event)
        }
    }
}

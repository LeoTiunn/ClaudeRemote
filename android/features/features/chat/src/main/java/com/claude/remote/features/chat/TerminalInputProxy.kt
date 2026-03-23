package com.claude.remote.features.chat

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText

/**
 * Invisible EditText overlay that captures keyboard input for the terminal.
 * Uses Termux's BaseInputConnection pattern for proper IME handling:
 * - commitText: regular typing → send to terminal
 * - finishComposingText: swipe typing completion → send to terminal
 * - deleteSurroundingText: backspace → send DEL
 * - sendKeyEvent: hardware key fallback
 */
class TerminalInputProxy(context: Context) : EditText(context) {

    var onTerminalInput: ((String) -> Unit)? = null

    init {
        alpha = 0f
        setBackgroundColor(0)
        isCursorVisible = false
        height = 1
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL = dumb terminal mode, most keyboards still work including swipe
        outAttrs.inputType = android.text.InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, true) {
            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                val text = editable?.toString() ?: ""
                if (text.isNotEmpty()) {
                    onTerminalInput?.invoke(text)
                    editable?.clear()
                }
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                val content = editable?.toString() ?: ""
                if (content.isNotEmpty()) {
                    onTerminalInput?.invoke(content)
                    editable?.clear()
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { onTerminalInput?.invoke("\u007f") }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            onTerminalInput?.invoke("\u007f")
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            onTerminalInput?.invoke("\r")
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}

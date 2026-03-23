package com.claude.remote.features.chat

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText

/**
 * Invisible EditText that captures keyboard input for the terminal.
 * xterm.js textarea is disabled, so this is the ONLY keyboard target.
 *
 * With TYPE_NULL, keyboards may send input via either:
 * - commitText/finishComposingText (swipe, some keyboards)
 * - onKeyDown with KeyEvents (GBoard regular typing)
 * Both paths are handled.
 */
class TerminalInputProxy(context: Context) : EditText(context) {

    var onTerminalInput: ((String) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        alpha = 0f
        setBackgroundColor(0)
        isCursorVisible = false
        height = 1
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, true) {

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                sendEditable()
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                sendEditable()
                return true
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                for (i in 0 until leftLength) sendKeyEvent(deleteKey)
                return super.deleteSurroundingText(leftLength, rightLength)
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
                        else -> {
                            // Handle regular character keys sent via InputConnection
                            val c = event.unicodeChar
                            if (c != 0) {
                                onTerminalInput?.invoke(String(Character.toChars(c)))
                                return true
                            }
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }

            private fun sendEditable() {
                val content: Editable? = editable
                val text = content?.toString() ?: ""
                if (text.isNotEmpty()) {
                    onTerminalInput?.invoke(text.replace('\n', '\r'))
                    content?.clear()
                }
            }
        }
    }

    // GBoard with TYPE_NULL sends regular characters here as KeyEvents
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onTerminalInput?.invoke("\u007f")
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                onTerminalInput?.invoke("\r")
                return true
            }
        }

        // ACTION_MULTIPLE with KEYCODE_UNKNOWN: batch character input
        if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = event.characters
            if (!chars.isNullOrEmpty()) {
                onTerminalInput?.invoke(chars)
                return true
            }
        }

        // Regular character: get unicode from the key event
        val c = event.unicodeChar
        if (c != 0) {
            onTerminalInput?.invoke(String(Character.toChars(c)))
            return true
        }

        return super.onKeyDown(keyCode, event)
    }
}

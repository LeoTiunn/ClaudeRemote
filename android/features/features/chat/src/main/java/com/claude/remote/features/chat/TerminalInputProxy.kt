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
 * Uses TYPE_CLASS_TEXT to enable all keyboard features:
 * - Regular typing (commitText per character or word)
 * - Swipe typing (setComposingText during swipe, commitText on finish)
 * - Voice input (commitText with recognized text)
 * - Pinyin/CJK (setComposingText for pinyin, commitText for selected char)
 *
 * Composing text is NOT sent to terminal — only committed text is.
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
        // TYPE_CLASS_TEXT enables all keyboard features (swipe, voice, pinyin)
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, true) {

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Let composing happen (swipe preview, pinyin input)
                // Don't send to terminal — wait for commit
                return super.setComposingText(text, newCursorPosition)
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                sendAndClear()
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                sendAndClear()
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

            private fun sendAndClear() {
                val content: Editable? = editable
                val text = content?.toString() ?: ""
                if (text.isNotEmpty()) {
                    onTerminalInput?.invoke(text.replace('\n', '\r'))
                    content?.clear()
                }
            }
        }
    }

    // Fallback for keyboards that send KeyEvents directly
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

        if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = event.characters
            if (!chars.isNullOrEmpty()) {
                onTerminalInput?.invoke(chars)
                return true
            }
        }

        val c = event.unicodeChar
        if (c != 0) {
            onTerminalInput?.invoke(String(Character.toChars(c)))
            return true
        }

        return super.onKeyDown(keyCode, event)
    }
}

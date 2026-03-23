package com.claude.remote.features.chat

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Invisible native View that captures keyboard input for the terminal.
 *
 * Modeled after Termux's TerminalView input handling:
 * - Extends View (not EditText) with onCheckIsTextEditor() = true
 * - BaseInputConnection handles commitText, finishComposingText, deleteSurroundingText
 * - onKeyDown handles hardware key events
 * - TYPE_NULL = standard terminal input mode
 *
 * The WebView's xterm.js textarea is disabled so this is the ONLY keyboard target.
 */
class TerminalInputProxy(context: Context) : View(context) {

    var onTerminalInput: ((String) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // Invisible but present in layout
        alpha = 0f
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL: standard terminal mode. Most keyboards work including swipe.
        // Samsung keyboards may need TYPE_TEXT_VARIATION_VISIBLE_PASSWORD instead.
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
                // Termux pattern: send synthetic DEL key events
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
                    }
                }
                return super.sendKeyEvent(event)
            }

            private fun sendEditable() {
                val content: Editable? = editable
                val text = content?.toString() ?: ""
                if (text.isNotEmpty()) {
                    // Convert \n to \r (terminal expects \r for enter)
                    val termText = text.replace('\n', '\r')
                    onTerminalInput?.invoke(termText)
                    content?.clear()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        return super.onKeyDown(keyCode, event)
    }
}

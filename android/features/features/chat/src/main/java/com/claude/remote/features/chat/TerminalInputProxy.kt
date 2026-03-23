package com.claude.remote.features.chat

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import com.claude.remote.core.ssh.DebugLog

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
        // Use a small but non-zero size so IME doesn't reject "not served".
        // This view is positioned at the bottom of the terminal Box,
        // NOT as a fullMaxSize overlay — so it doesn't block WebView scroll.
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_CLASS_TEXT enables all keyboard features (swipe, voice, pinyin)
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        DebugLog.log("INPUT_PROXY", "onCreateInputConnection: inputType=TYPE_CLASS_TEXT, imeOptions=${outAttrs.imeOptions}")

        return object : BaseInputConnection(this, true) {

            // Track what we've already sent to terminal during composing
            // so we don't double-send on commit
            private var sentComposing = ""

            private fun isAsciiAppend(oldText: String, newText: String): Boolean {
                // English typing: old composing + 1 ASCII char = new composing
                return newText.length == oldText.length + 1 &&
                    newText.startsWith(oldText) &&
                    newText.last().code < 128
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val newText = text?.toString() ?: ""
                DebugLog.log("INPUT_PROXY", "setComposingText: text='$newText' cursor=$newCursorPosition sentComposing='$sentComposing'")

                if (isAsciiAppend(sentComposing, newText)) {
                    // English typing — send the new character immediately
                    val newChar = newText.last().toString()
                    DebugLog.log("INPUT_PROXY", "setComposingText: ASCII append, sending '$newChar' immediately")
                    onTerminalInput?.invoke(newChar)
                    sentComposing = newText
                }
                // For IME (Chinese/swipe/non-ASCII): buffer, wait for commit

                return super.setComposingText(text, newCursorPosition)
            }

            override fun finishComposingText(): Boolean {
                val editableBefore = editable?.toString() ?: ""
                DebugLog.log("INPUT_PROXY", "finishComposingText: editable_before='$editableBefore' sentComposing='$sentComposing'")
                super.finishComposingText()
                val editableAfter = editable?.toString() ?: ""
                DebugLog.log("INPUT_PROXY", "finishComposingText: editable_after='$editableAfter'")
                sendRemainingAndClear()
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val editableBefore = editable?.toString() ?: ""
                DebugLog.log("INPUT_PROXY", "commitText: text='$text' cursor=$newCursorPosition editable_before='$editableBefore' sentComposing='$sentComposing'")
                val committed = text?.toString() ?: ""

                if (sentComposing.isNotEmpty() && committed != sentComposing) {
                    // Autocorrect changed the word — backspace what we sent, resend correct text
                    DebugLog.log("INPUT_PROXY", "commitText: autocorrect detected, backspacing ${sentComposing.length} chars")
                    for (i in sentComposing.indices) {
                        onTerminalInput?.invoke("\u007f")
                    }
                    sentComposing = ""
                }

                super.commitText(text, newCursorPosition)
                val editableAfter = editable?.toString() ?: ""
                DebugLog.log("INPUT_PROXY", "commitText: editable_after='$editableAfter'")
                sendRemainingAndClear()
                return true
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                DebugLog.log("INPUT_PROXY", "deleteSurroundingText: left=$leftLength right=$rightLength editable='${editable}'")
                val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                for (i in 0 until leftLength) sendKeyEvent(deleteKey)
                return super.deleteSurroundingText(leftLength, rightLength)
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                DebugLog.log("INPUT_PROXY", "sendKeyEvent: action=${event?.action} keyCode=${event?.keyCode} char=${event?.unicodeChar}")
                if (event?.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            DebugLog.log("INPUT_PROXY", "sendKeyEvent: DEL -> sending \\x7f")
                            onTerminalInput?.invoke("\u007f")
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            DebugLog.log("INPUT_PROXY", "sendKeyEvent: ENTER -> sending \\r")
                            onTerminalInput?.invoke("\r")
                            return true
                        }
                        else -> {
                            val c = event.unicodeChar
                            if (c != 0) {
                                val chars = String(Character.toChars(c))
                                DebugLog.log("INPUT_PROXY", "sendKeyEvent: unicode=$c -> sending '$chars'")
                                onTerminalInput?.invoke(chars)
                                return true
                            }
                        }
                    }
                }
                DebugLog.log("INPUT_PROXY", "sendKeyEvent: falling through to super")
                return super.sendKeyEvent(event)
            }

            private fun sendRemainingAndClear() {
                val content: Editable? = editable
                val text = content?.toString() ?: ""
                DebugLog.log("INPUT_PROXY", "sendRemainingAndClear: text='$text' sentComposing='$sentComposing'")

                // Only send what we haven't already sent during composing
                val remaining = if (text.startsWith(sentComposing)) {
                    text.substring(sentComposing.length)
                } else {
                    text // text changed (autocorrect) — send all
                }

                if (remaining.isNotEmpty()) {
                    val sending = remaining.replace('\n', '\r')
                    DebugLog.log("INPUT_PROXY", "sendRemainingAndClear: sending remaining '${sending}'")
                    onTerminalInput?.invoke(sending)
                }

                sentComposing = ""
                content?.clear()
            }
        }
    }

    // Fallback for keyboards that send KeyEvents directly
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        DebugLog.log("INPUT_PROXY", "onKeyDown: keyCode=$keyCode action=${event?.action} char=${event?.unicodeChar}")
        if (event == null) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                DebugLog.log("INPUT_PROXY", "onKeyDown: DEL -> sending \\x7f")
                onTerminalInput?.invoke("\u007f")
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                DebugLog.log("INPUT_PROXY", "onKeyDown: ENTER -> sending \\r")
                onTerminalInput?.invoke("\r")
                return true
            }
        }

        if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = event.characters
            DebugLog.log("INPUT_PROXY", "onKeyDown: ACTION_MULTIPLE chars='$chars'")
            if (!chars.isNullOrEmpty()) {
                onTerminalInput?.invoke(chars)
                return true
            }
        }

        val c = event.unicodeChar
        if (c != 0) {
            val chars = String(Character.toChars(c))
            DebugLog.log("INPUT_PROXY", "onKeyDown: unicodeChar=$c -> sending '$chars'")
            onTerminalInput?.invoke(chars)
            return true
        }

        DebugLog.log("INPUT_PROXY", "onKeyDown: unhandled, falling through to super")
        return super.onKeyDown(keyCode, event)
    }
}

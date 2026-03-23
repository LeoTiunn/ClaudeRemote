package com.claude.remote.features.chat

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.claude.remote.core.ssh.DebugLog

/**
 * Native Android EditText that captures all keyboard input and forwards
 * it to the terminal via SSH. Bypasses WebView's flaky InputConnection.
 *
 * VISIBLE_PASSWORD disables composing — kills swipe typing but guarantees
 * every keystroke goes straight to the terminal with zero duplication.
 */
class TerminalInputProxy(context: Context) : EditText(context) {

    var onTerminalInput: ((String) -> Unit)? = null
    private var ignoreNextChange = false

    init {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        // Make invisible but still focusable for keyboard
        alpha = 0f
        setBackgroundColor(0)
        isCursorVisible = false
        height = 1
        isFocusable = true
        isFocusableInTouchMode = true

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (ignoreNextChange || s == null) return
                if (count > 0) {
                    val newText = s.subSequence(start, start + count).toString()
                    DebugLog.log("INPUT_PROXY", "Sending: '${newText.take(50)}' (${newText.length} chars)")
                    onTerminalInput?.invoke(newText)
                }
            }
            override fun afterTextChanged(s: Editable?) {
                if (!ignoreNextChange && s != null && s.isNotEmpty()) {
                    ignoreNextChange = true
                    s.clear()
                    ignoreNextChange = false
                }
            }
        })

        setOnEditorActionListener { _, _, _ ->
            onTerminalInput?.invoke("\r")
            true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                DebugLog.log("INPUT_PROXY", "Backspace")
                onTerminalInput?.invoke("\u007f")
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                DebugLog.log("INPUT_PROXY", "Enter")
                onTerminalInput?.invoke("\r")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

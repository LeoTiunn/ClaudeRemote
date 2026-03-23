package com.claude.remote.features.chat

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText

class TerminalInputProxy(context: Context) : EditText(context) {

    var onTerminalInput: ((String) -> Unit)? = null
    private var ignoreNextChange = false

    init {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
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

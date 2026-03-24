package com.claude.remote.features.chat

import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bridge for terminal output: Kotlin enqueues data, JS polls via @JavascriptInterface.
 * This replaces evaluateJavascript which permanently breaks after WebView resize.
 */
class TerminalOutputBridge {
    private val queue = ConcurrentLinkedQueue<String>()

    fun enqueue(chunk: String) {
        queue.add(chunk)
    }

    @JavascriptInterface
    fun poll(): String {
        val sb = StringBuilder()
        while (true) {
            val chunk = queue.poll() ?: break
            sb.append(chunk)
        }
        if (sb.isEmpty()) return ""
        return android.util.Base64.encodeToString(
            sb.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
    }
}

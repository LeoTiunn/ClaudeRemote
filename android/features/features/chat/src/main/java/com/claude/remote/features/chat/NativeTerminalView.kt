package com.claude.remote.features.chat

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.claude.remote.core.ssh.DebugLog
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Compose wrapper around Termux's native TerminalView.
 * Handles keyboard input, scrolling, and rendering natively — no WebView.
 */
@Composable
fun NativeTerminalView(
    sshTerminalSession: SshTerminalSession,
    modifier: Modifier = Modifier
) {
    val handler = remember { Handler(Looper.getMainLooper()) }

    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float = 1.0f
            override fun onSingleTapUp(e: MotionEvent) {}
            override fun shouldBackButtonBeMappedToEscape() = false
            override fun shouldEnforceCharBasedInput() = false
            override fun shouldUseCtrlSpaceWorkaround() = false
            override fun isTerminalViewSelected() = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
            override fun onLongPress(event: MotionEvent) = false
            override fun readControlKey() = false
            override fun readAltKey() = false
            override fun readShiftKey() = false
            override fun readFnKey() = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
            override fun onEmulatorSet() {
                DebugLog.log("NATIVE_TERM", "onEmulatorSet")
            }
            override fun logError(tag: String, message: String) = DebugLog.log(tag, "E: $message")
            override fun logWarn(tag: String, message: String) = DebugLog.log(tag, "W: $message")
            override fun logInfo(tag: String, message: String) = DebugLog.log(tag, "I: $message")
            override fun logDebug(tag: String, message: String) = DebugLog.log(tag, "D: $message")
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                DebugLog.log(tag, "$message: ${e.message}")
            }
            override fun logStackTrace(tag: String, e: Exception) {
                DebugLog.log(tag, "Exception: ${e.message}")
            }
        }
    }

    var termViewRef: TerminalView? = remember { null }

    AndroidView(
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                termViewRef = this
                setTerminalViewClient(viewClient)
                // Do NOT call attachSession() here — it would trigger initializeEmulator()
                // which starts a local /bin/sh process. We need SSH emulator instead.
                isFocusable = true
                isFocusableInTouchMode = true
                val density = ctx.resources.displayMetrics.density
                setTextSize((14 * density).toInt())
                setBackgroundColor(0xFF1C1917.toInt())

                var resizeRunnable: Runnable? = null
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    resizeRunnable?.let { handler.removeCallbacks(it) }
                    val runnable = Runnable {
                        if (mRenderer == null || width <= 0 || height <= 0) return@Runnable
                        val cellW = mRenderer.getFontWidth()
                        val cellH = mRenderer.getFontLineSpacing().toFloat()
                        if (cellW <= 0 || cellH <= 0) return@Runnable
                        val cols = (width / cellW).toInt().coerceAtLeast(10)
                        val rows = (height / cellH).toInt().coerceAtLeast(3)
                        DebugLog.log("NATIVE_TERM", "Layout: ${width}x${height}px → ${cols}x${rows}")
                        if (!sshTerminalSession.isStarted) {
                            // First layout: create SSH emulator, then attach to view
                            sshTerminalSession.start(cols, rows, cellW.toInt(), cellH.toInt())
                            attachSession(sshTerminalSession.session)
                            sshTerminalSession.terminalView = this@apply
                        } else {
                            sshTerminalSession.resize(cols, rows, cellW.toInt(), cellH.toInt())
                        }
                    }
                    resizeRunnable = runnable
                    handler.postDelayed(runnable, 150)
                }

                requestFocus()
            }
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            sshTerminalSession.destroy()
        }
    }
}

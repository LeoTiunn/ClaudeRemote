package com.claude.remote.features.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.claude.remote.core.ssh.DebugLog
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.termux.terminal.TerminalSession
import android.view.KeyEvent

/**
 * Hybrid terminal: Termux TerminalView for rendering + scroll,
 * TerminalInputProxy (invisible EditText) for all keyboard/IME input.
 *
 * Termux TerminalView normally uses TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
 * which kills IME composing. By overlaying TerminalInputProxy, we get:
 * - Native Canvas rendering + smooth native scroll (Termux)
 * - Full IME support: swipe, Chinese, voice (TerminalInputProxy)
 */
@Composable
fun NativeTerminalView(
    sshTerminalSession: SshTerminalSession,
    onTerminalInput: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val handler = remember { Handler(Looper.getMainLooper()) }
    val viewHolder = remember { arrayOfNulls<TerminalView>(1) }
    val proxyHolder = remember { arrayOfNulls<TerminalInputProxy>(1) }

    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float = 1.0f
            override fun onSingleTapUp(e: MotionEvent) {
                // Focus the input proxy (not the TerminalView) to show keyboard
                proxyHolder[0]?.let { proxy ->
                    proxy.requestFocus()
                    val imm = proxy.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? InputMethodManager
                    imm?.showSoftInput(proxy, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            override fun shouldBackButtonBeMappedToEscape() = false
            override fun shouldEnforceCharBasedInput() = true
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

    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                // 1. Termux TerminalView — fills the container, handles rendering + scroll
                val termView = TerminalView(ctx, null).apply {
                    setTerminalViewClient(viewClient)
                    isFocusable = false  // Don't let it grab keyboard focus
                    isFocusableInTouchMode = false
                    val density = ctx.resources.displayMetrics.density
                    setTextSize((14 * density).toInt())
                    setBackgroundColor(0xFF1C1917.toInt())
                }
                viewHolder[0] = termView
                addView(termView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                // 2. TerminalInputProxy — invisible, captures all keyboard/IME input
                val proxy = TerminalInputProxy(ctx).apply {
                    this.onTerminalInput = { data ->
                        DebugLog.log("NATIVE_TERM", "proxy input: '${data.take(20)}'")
                        onTerminalInput?.invoke(data)
                    }
                }
                proxyHolder[0] = proxy
                addView(proxy, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 1
                ).apply {
                    gravity = android.view.Gravity.BOTTOM
                })

                // Resize logic
                var resizeRunnable: Runnable? = null
                termView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    resizeRunnable?.let { handler.removeCallbacks(it) }
                    val runnable = Runnable {
                        if (termView.mRenderer == null || termView.width <= 0 || termView.height <= 0) return@Runnable
                        val cellW = termView.mRenderer.getFontWidth()
                        val cellH = termView.mRenderer.getFontLineSpacing().toFloat()
                        if (cellW <= 0 || cellH <= 0) return@Runnable
                        val cols = (termView.width / cellW).toInt().coerceAtLeast(10)
                        val rows = (termView.height / cellH).toInt().coerceAtLeast(3)
                        DebugLog.log("NATIVE_TERM", "Layout: ${termView.width}x${termView.height}px -> ${cols}x${rows}")
                        if (!sshTerminalSession.isStarted) {
                            sshTerminalSession.start(cols, rows, cellW.toInt(), cellH.toInt())
                        }
                        if (termView.mTermSession == null) {
                            termView.attachSession(sshTerminalSession.session)
                            sshTerminalSession.terminalView = termView
                        } else {
                            sshTerminalSession.resize(cols, rows, cellW.toInt(), cellH.toInt())
                        }
                    }
                    resizeRunnable = runnable
                    handler.postDelayed(runnable, 150)
                }
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

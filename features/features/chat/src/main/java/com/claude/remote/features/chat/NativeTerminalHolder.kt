package com.claude.remote.features.chat

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the native Termux TerminalView across navigation events (singleton).
 * Replaces TerminalWebViewHolder — no WebView, no JS, no renderer process.
 */
@Singleton
class NativeTerminalHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    var terminalView: com.termux.view.TerminalView? = null
        private set

    var sshSession: SshTerminalSession? = null
        private set

    var fontSize: Float = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getFloat("font_size", 16f)

    var onResize: ((cols: Int, rows: Int) -> Unit)? = null

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {
            terminalView?.invalidate()
        }
        override fun onTerminalCursorStateChange(state: Boolean) {
            terminalView?.invalidate()
        }
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int = 0 // block cursor
        override fun logError(tag: String?, message: String?) {
            DebugLog.log(tag ?: "TERM", message ?: "")
        }
        override fun logWarn(tag: String?, message: String?) {
            DebugLog.log(tag ?: "TERM", message ?: "")
        }
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f
        override fun onSingleTapUp(e: MotionEvent?) {}
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = false // We handle input via TextField
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {
            val emulator = sshSession?.emulator ?: return
            onResize?.invoke(emulator.mColumns, emulator.mRows)
        }
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    fun getOrCreateView(context: Context): com.termux.view.TerminalView {
        return terminalView ?: com.termux.view.TerminalView(context, null).also { tv ->
            tv.setTerminalViewClient(viewClient)
            tv.setTextSize(fontSize.toInt())
            tv.setBackgroundColor(0xFF1C1917.toInt()) // Match dark theme
            // Don't capture keyboard — we use native TextField
            tv.isFocusable = false
            tv.isFocusableInTouchMode = false
            terminalView = tv
            // Attach existing session if one was created before the view
            sshSession?.let { session ->
                tv.attachSession(session)
            }
        }
    }

    fun createSession(sshClient: SshClient, scope: CoroutineScope): SshTerminalSession {
        val session = SshTerminalSession(sshClient, scope, sessionClient)
        sshSession = session
        terminalView?.attachSession(session)
        return session
    }

    fun attachExistingSession() {
        val session = sshSession ?: return
        val tv = terminalView ?: return
        session.updateTerminalSessionClient(sessionClient)
        tv.attachSession(session)
    }

    fun detachFromParent() {
        (terminalView?.parent as? ViewGroup)?.removeView(terminalView)
    }

    fun setTextSize(size: Float) {
        fontSize = size
        terminalView?.setTextSize(size.toInt())
    }
}

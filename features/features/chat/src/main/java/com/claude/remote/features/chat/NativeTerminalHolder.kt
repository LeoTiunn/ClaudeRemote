package com.claude.remote.features.chat

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.util.TypedValue
import com.claude.remote.core.ssh.DebugLog
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds native Termux TerminalView + TerminalSession (singleton).
 *
 * Uses real dbclient subprocess via PTY — identical to how Termux runs SSH.
 * No Java SSH bridge, no race conditions.
 */
@Singleton
class NativeTerminalHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    var terminalView: com.termux.view.TerminalView? = null
        private set

    var termSession: TerminalSession? = null
        private set

    var fontSize: Float = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getFloat("font_size", 16f)

    private fun spToPx(sp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics).toInt()

    /** Extract dbclient binary from assets to app files dir on first run. */
    fun getDbclientPath(): String {
        val file = File(context.filesDir, "dbclient")
        if (!file.exists() || file.length() == 0L) {
            DebugLog.log("TERM", "Extracting dbclient binary...")
            context.assets.open("dbclient").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file.setExecutable(true)
            DebugLog.log("TERM", "dbclient extracted to ${file.absolutePath}")
        }
        return file.absolutePath
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            DebugLog.log("TERM", "Session finished")
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {
            terminalView?.invalidate()
        }
        override fun onTerminalCursorStateChange(state: Boolean) {
            terminalView?.invalidate()
        }
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            DebugLog.log("TERM", "Shell PID: $pid")
        }
        override fun getTerminalCursorStyle(): Int = 0
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
        override fun isTerminalViewSelected(): Boolean = false
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {}
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
            tv.setTextSize(spToPx(fontSize))
            tv.setBackgroundColor(0xFF1C1917.toInt())
            tv.isFocusable = false
            tv.isFocusableInTouchMode = false
            terminalView = tv
            termSession?.let { tv.attachSession(it) }
        }
    }

    /**
     * Create a standard Termux TerminalSession running dbclient as subprocess.
     * This is the exact same flow Termux uses — PTY + fork.
     */
    fun createSession(host: String, port: Int, username: String): TerminalSession {
        val dbclientPath = getDbclientPath()
        val args = arrayOf(
            dbclientPath,
            "-y", "-y",           // Auto-accept host keys
            "-p", port.toString(),
            "-T",                 // Allocate PTY on remote
            "$username@$host"
        )
        val env = arrayOf(
            "TERM=xterm-256color",
            "HOME=${context.filesDir.absolutePath}",
            "LANG=en_US.UTF-8"
        )

        DebugLog.log("TERM", "Creating session: ${args.joinToString(" ")}")
        val session = TerminalSession(
            dbclientPath,
            context.filesDir.absolutePath,
            args,
            env,
            null,
            sessionClient
        )
        termSession = session
        terminalView?.attachSession(session)
        return session
    }

    fun attachExistingSession() {
        val session = termSession ?: return
        val tv = terminalView ?: return
        session.updateTerminalSessionClient(sessionClient)
        tv.attachSession(session)
    }

    /** Write text to the terminal session (goes to dbclient's stdin via PTY). */
    fun writeToSession(text: String) {
        termSession?.write(text)
    }

    /** Write raw bytes to the terminal session. */
    fun writeBytes(data: ByteArray) {
        termSession?.write(data, 0, data.size)
    }

    fun isSessionRunning(): Boolean = termSession?.isRunning == true

    fun detachFromParent() {
        (terminalView?.parent as? ViewGroup)?.removeView(terminalView)
    }

    fun setTextSize(size: Float) {
        fontSize = size
        terminalView?.setTextSize(spToPx(size))
    }

    fun destroySession() {
        termSession?.finishIfRunning()
        termSession = null
    }
}

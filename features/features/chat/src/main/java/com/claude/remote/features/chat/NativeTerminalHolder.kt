package com.claude.remote.features.chat

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.util.TypedValue
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import com.termux.terminal.SshTerminalSession
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds native Termux TerminalView + SshTerminalSession (singleton).
 *
 * Uses JSch for SSH, connected directly to Termux's TerminalEmulator.
 * No external binary needed — all SSH happens in Java.
 */
@Singleton
class NativeTerminalHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    var terminalView: com.termux.view.TerminalView? = null
        private set

    var termSession: TerminalSession? = null
        private set

    private var jschSession: com.jcraft.jsch.Session? = null
    private var shellChannel: ChannelShell? = null

    var fontSize: Float = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getFloat("font_size", 16f)

    private fun spToPx(sp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics).toInt()

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
            DebugLog.log("TERM", "Session initialized")
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
     * Connect via JSch and create an SshTerminalSession bridging
     * the SSH channel I/O to Termux's TerminalEmulator.
     */
    suspend fun createSshSession(host: String, port: Int, username: String, password: String): SshTerminalSession {
        DebugLog.log("TERM", "Connecting SSH to $host:$port as $username")

        // JSch network I/O on background thread
        val (session, channel, sshInput, sshOutput) = withContext(Dispatchers.IO) {
            // Resolve hostname
            val resolvedHost = try {
                java.net.InetAddress.getAllByName(host)
                    .firstOrNull { it is java.net.Inet4Address }
                    ?.hostAddress ?: host
            } catch (e: Exception) { host }

            // JSch SSH connection
            val jsch = JSch()
            val sess = jsch.getSession(username, resolvedHost, port)
            sess.setPassword(password)
            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            sess.setConfig(config)
            sess.timeout = 0
            sess.setServerAliveInterval(15_000)
            sess.setServerAliveCountMax(3)

            DebugLog.log("TERM", "SSH connecting...")
            sess.connect(15_000)
            DebugLog.log("TERM", "SSH connected")

            // Open shell channel with PTY
            val ch = sess.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", 80, 24, 0, 0)

            val input = ch.inputStream
            val output = ch.outputStream

            ch.connect(10_000)
            DebugLog.log("TERM", "Shell channel connected")

            SshConnectionResult(sess, ch, input, output)
        }

        jschSession = session
        shellChannel = channel

        // Create SshTerminalSession on MAIN thread (Handler needs Looper)
        return withContext(Dispatchers.Main) {
            val sshTermSession = SshTerminalSession(sessionClient)
            termSession = sshTermSession

            sshTermSession.initializeWithStreams(
                80, 24, 0, 0,
                sshInput, sshOutput
            ) { newCols, newRows ->
                try {
                    channel.setPtySize(newCols, newRows, newCols * 8, newRows * 16)
                } catch (e: Exception) {
                    DebugLog.log("TERM", "PTY resize failed: ${e.message}")
                }
            }
            terminalView?.attachSession(sshTermSession)

            DebugLog.log("TERM", "SshTerminalSession initialized")
            sshTermSession
        }
    }

    private data class SshConnectionResult(
        val session: com.jcraft.jsch.Session,
        val channel: ChannelShell,
        val sshInput: java.io.InputStream,
        val sshOutput: java.io.OutputStream
    )

    fun attachExistingSession() {
        val session = termSession ?: return
        val tv = terminalView ?: return
        session.updateTerminalSessionClient(sessionClient)
        tv.attachSession(session)
    }

    fun writeToSession(text: String) {
        termSession?.write(text)
    }

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
        try { shellChannel?.disconnect() } catch (_: Exception) {}
        try { jschSession?.disconnect() } catch (_: Exception) {}
        shellChannel = null
        jschSession = null
    }
}

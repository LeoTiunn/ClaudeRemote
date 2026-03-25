package com.claude.remote.features.chat

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.util.TypedValue
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.ShellChannelHandle
import com.claude.remote.core.ssh.SshClient
import com.termux.terminal.SshTerminalSession
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @ApplicationContext private val context: Context,
    private val sshClient: SshClient
) {
    var terminalView: com.termux.view.TerminalView? = null
        private set

    var termSession: TerminalSession? = null
        private set

    private var channelHandle: ShellChannelHandle? = null

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
     * Open a new shell channel on the existing SshClient session and bridge
     * it to Termux's TerminalEmulator via SshTerminalSession.
     *
     * Sequence matters to avoid JSch PipedInputStream timeout:
     * 1. Prepare channel + get streams (no connect yet)
     * 2. Create SshTerminalSession + start reader thread (reader blocks on read())
     * 3. Connect channel — data flows, reader is already waiting
     */
    suspend fun createSshSession(): SshTerminalSession {
        DebugLog.log("TERM", "Opening terminal channel on existing SSH session")

        // Step 1: Prepare channel (get streams, but DON'T connect)
        val handle = sshClient.openShellChannel(80, 24)
        channelHandle = handle

        // Step 2: Create session + start reader/writer threads on MAIN thread
        val sshTermSession = withContext(Dispatchers.Main) {
            val session = SshTerminalSession(sessionClient)
            termSession = session

            session.initializeWithStreams(
                80, 24, 0, 0,
                handle.inputStream, handle.outputStream
            ) { newCols, newRows ->
                handle.resizePty(newCols, newRows)
            }

            DebugLog.log("TERM", "Reader/writer threads started, now connecting channel...")
            session
        }

        // Step 3: Connect channel — reader thread is already waiting on read()
        handle.connectChannel()

        // Step 4: Attach view on Main
        withContext(Dispatchers.Main) {
            terminalView?.attachSession(sshTermSession)
            DebugLog.log("TERM", "SshTerminalSession initialized and attached")
        }

        return sshTermSession
    }

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
        channelHandle?.disconnect?.invoke()
        channelHandle = null
    }
}

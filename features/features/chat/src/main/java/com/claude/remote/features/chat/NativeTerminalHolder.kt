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
    companion object {
        private const val DEFAULT_COLS = 52
        private const val DEFAULT_ROWS = 40
        private const val MIN_TERMINAL_SIZE = 4
    }

    var terminalView: com.termux.view.TerminalView? = null
        private set

    var termSession: TerminalSession? = null
        private set

    var attachedSessionName: String = ""

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

    private var terminalTypeface: android.graphics.Typeface? = null

    private fun getTerminalTypeface(context: Context): android.graphics.Typeface {
        return terminalTypeface ?: buildTerminalTypeface(context).also { terminalTypeface = it }
    }

    private fun buildTerminalTypeface(context: Context): android.graphics.Typeface {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // API 29+: Use system monospace for Latin, Sarasa for CJK fallback
                val sarasaFont = android.graphics.fonts.Font.Builder(
                    context.assets, "fonts/SarasaMonoSC-Regular.ttf"
                ).build()
                val sarasaFamily = android.graphics.fonts.FontFamily.Builder(sarasaFont).build()
                return android.graphics.Typeface.CustomFallbackBuilder(sarasaFamily)
                    .setSystemFallback("monospace")
                    .build()
            } else {
                // API 28: Use Sarasa directly (includes both Latin and CJK)
                return android.graphics.Typeface.createFromAsset(
                    context.assets, "fonts/SarasaMonoSC-Regular.ttf"
                )
            }
        } catch (e: Exception) {
            DebugLog.log("TERM", "Failed to load CJK font: ${e.message}")
            return android.graphics.Typeface.MONOSPACE
        }
    }

    fun getOrCreateView(context: Context): com.termux.view.TerminalView {
        // Always create a fresh view with the current Activity context
        // Old view may hold stale Activity reference after rotation
        val existing = terminalView
        if (existing != null && existing.context === context) {
            return existing
        }

        return com.termux.view.TerminalView(context, null).also { tv ->
            tv.setTerminalViewClient(viewClient)
            tv.setTextSize(spToPx(fontSize))
            tv.setTypeface(getTerminalTypeface(context))
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
     * Uses channel.setOutputStream/setInputStream so JSch writes directly
     * to our ByteQueue — no PipedInputStream, no reader/writer threads.
     */
    suspend fun createSshSession(): SshTerminalSession {
        DebugLog.log("TERM", "Creating SSH terminal session")

        // Compute initial size from actual view dimensions if available
        val tv = terminalView
        val renderer = tv?.mRenderer
        val initialCols: Int
        val initialRows: Int
        if (tv != null && renderer != null && tv.width > 0 && tv.height > 0) {
            val fontW = renderer.fontWidth
            val fontH = renderer.fontLineSpacing
            initialCols = Math.max(MIN_TERMINAL_SIZE, (tv.width / fontW).toInt())
            initialRows = Math.max(MIN_TERMINAL_SIZE, (tv.height - fontH) / fontH)
            DebugLog.log("TERM", "Using actual view size: ${initialCols}x${initialRows}")
        } else {
            initialCols = DEFAULT_COLS
            initialRows = DEFAULT_ROWS
            DebugLog.log("TERM", "Using fallback size: ${initialCols}x${initialRows}")
        }

        // Step 1: Create SshTerminalSession + emulator on MAIN (Handler needs Looper)
        // Do NOT set termSession yet — Compose recomposition would call attachSession()
        // before resize callback is set, so PTY would never get resized.
        val sshTermSession = withContext(Dispatchers.Main) {
            val session = SshTerminalSession(sessionClient)
            session.initializeEmulator(initialCols, initialRows)
            session
        }

        // Step 2: Open channel + start reader IMMEDIATELY on IO thread
        // Reader must start right after connect — no thread switching gap
        val handle = sshClient.openShellChannel(initialCols, initialRows)
        channelHandle = handle

        // Start reader/writer RIGHT NOW on IO thread — same context as channel.connect()
        sshTermSession.startIo(handle.inputStream, handle.outputStream)

        // Step 3: Set resize callback, THEN expose session, THEN attach view on Main
        // Order matters: resize callback must be set before attachSession triggers updateSize
        withContext(Dispatchers.Main) {
            sshTermSession.setResizeCallback { newCols, newRows ->
                Thread { handle.resizePty(newCols, newRows) }.start()
            }
            termSession = sshTermSession  // Now safe — callback is set
            terminalView?.attachSession(sshTermSession)
            DebugLog.log("TERM", "SshTerminalSession attached")
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
        attachedSessionName = ""
        channelHandle?.disconnect?.invoke()
        channelHandle = null
        // Force a fresh view next time so old session content doesn't linger
        terminalView = null
    }
}

package com.claude.remote.features.chat

import android.os.Handler
import android.os.Looper
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.nio.charset.StandardCharsets

/**
 * Bridges SshClient I/O to Termux's TerminalSession/TerminalEmulator.
 *
 * Does NOT collect from outputStream itself — ChatViewModel feeds data
 * via [feedOutput] to keep a single collector (same as the WebView era).
 */
class SshTerminalSession(
    private val sshClient: SshClient,
    private val client: TerminalSessionClient
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var isStarted = false
        private set
    /** Set by NativeTerminalView after attachSession so we can call onScreenUpdated() */
    var terminalView: TerminalView? = null

    val session: TerminalSession = TerminalSession(
        "/bin/sh", "/", arrayOf(), arrayOf(),
        null, client
    )

    /** Call from main thread after view has measured and knows cols/rows. */
    fun start(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (isStarted) {
            resize(columns, rows, cellWidthPixels, cellHeightPixels)
            return
        }
        isStarted = true
        DebugLog.log("SSH_TERM", "start: ${columns}x${rows} cell=${cellWidthPixels}x${cellHeightPixels}")

        // Display-only: emulator renders but never writes responses back to SSH.
        val termOutput = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {}
            override fun titleChanged(oldTitle: String?, newTitle: String?) {
                handler.post { client.onTitleChanged(session) }
            }
            override fun onCopyTextToClipboard(text: String?) {
                handler.post { client.onCopyTextToClipboard(session, text ?: "") }
            }
            override fun onPasteTextFromClipboard() {
                handler.post { client.onPasteTextFromClipboard(session) }
            }
            override fun onBell() {
                handler.post { client.onBell(session) }
            }
            override fun onColorsChanged() {
                handler.post { client.onColorsChanged(session) }
            }
        }

        val emulator = TerminalEmulator(
            termOutput, columns, rows, cellWidthPixels, cellHeightPixels,
            10000, client
        )
        session.mEmulator = emulator
        session.mClient = client
        session.mExternalWriter = TerminalSession.WriteCallback { _, _, _ -> }

        sshClient.resizePty(columns, rows)
    }

    /**
     * Feed SSH output into the emulator. Called by ChatViewModel's single
     * outputStream collector — no second collector needed.
     */
    fun feedOutput(chunk: String) {
        val bytes = chunk.toByteArray(StandardCharsets.UTF_8)
        if (isStarted && session.mEmulator != null) {
            handler.post {
                try {
                    session.mEmulator?.append(bytes, bytes.size)
                    terminalView?.onScreenUpdated()
                } catch (e: Exception) {
                    DebugLog.log("SSH_TERM", "append failed: ${e.message}")
                }
            }
        }
    }

    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        session.mEmulator?.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        sshClient.resizePty(columns, rows)
    }

    fun destroy() {
        // Nothing to cancel — no collector
    }
}

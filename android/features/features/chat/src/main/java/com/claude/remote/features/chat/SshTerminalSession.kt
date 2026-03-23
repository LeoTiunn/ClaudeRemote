package com.claude.remote.features.chat

import android.os.Handler
import android.os.Looper
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.termux.view.TerminalView
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bridges SshClient I/O to Termux's TerminalSession/TerminalEmulator.
 *
 * Starts collecting SSH output immediately on creation, buffering until
 * the emulator is initialized (after the view lays out and provides cols/rows).
 */
class SshTerminalSession(
    private val sshClient: SshClient,
    private val scope: CoroutineScope,
    private val client: TerminalSessionClient
) {
    private val handler = Handler(Looper.getMainLooper())
    private var collectJob: Job? = null
    @Volatile var isStarted = false
        private set
    @Volatile private var emulatorReady = false
    /** Set by NativeTerminalView after attachSession so we can call onScreenUpdated() */
    var terminalView: TerminalView? = null
    private val preBuffer = ConcurrentLinkedQueue<ByteArray>()

    val session: TerminalSession = TerminalSession(
        "/bin/sh", "/", arrayOf(), arrayOf(),
        null, client
    )

    init {
        // Start collecting SSH output immediately — buffer until emulator is ready
        startCollecting()
    }

    /** Call from main thread after view has measured and knows cols/rows. */
    fun start(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (isStarted) {
            // Already started — just resize
            resize(columns, rows, cellWidthPixels, cellHeightPixels)
            return
        }
        isStarted = true
        DebugLog.log("SSH_TERM", "start: ${columns}x${rows} cell=${cellWidthPixels}x${cellHeightPixels}")

        val termOutput = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {
                val bytes = data.copyOfRange(offset, offset + count)
                scope.launch {
                    try {
                        sshClient.sendRawBytes(bytes)
                    } catch (e: Exception) {
                        DebugLog.log("SSH_TERM", "send failed: ${e.message}")
                    }
                }
            }

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

        // Route keyboard input to SSH instead of local PTY
        session.mExternalWriter = TerminalSession.WriteCallback { data, offset, count ->
            val bytes = data.copyOfRange(offset, offset + count)
            scope.launch {
                try {
                    sshClient.sendRawBytes(bytes)
                } catch (e: Exception) {
                    DebugLog.log("SSH_TERM", "mExternalWriter send failed: ${e.message}")
                }
            }
        }

        emulatorReady = true

        // Resize SSH PTY
        sshClient.resizePty(columns, rows)

        // Replay buffered output
        val buffered = mutableListOf<ByteArray>()
        while (true) {
            val chunk = preBuffer.poll() ?: break
            buffered.add(chunk)
        }
        if (buffered.isNotEmpty()) {
            DebugLog.log("SSH_TERM", "Replaying ${buffered.size} buffered chunks")
            for (bytes in buffered) {
                emulator.append(bytes, bytes.size)
            }
            client.onTextChanged(session)
        }

        // Send Ctrl+L to force tmux redraw with correct size
        scope.launch {
            kotlinx.coroutines.delay(200)
            try {
                sshClient.sendRawBytes(byteArrayOf(0x0C))
            } catch (_: Exception) {}
        }
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = scope.launch {
            sshClient.outputStream.collect { chunk ->
                val bytes = chunk.toByteArray(StandardCharsets.UTF_8)
                if (emulatorReady) {
                    handler.post {
                        try {
                            session.mEmulator?.append(bytes, bytes.size)
                            terminalView?.onScreenUpdated()
                        } catch (e: Exception) {
                            DebugLog.log("SSH_TERM", "append failed: ${e.message}")
                        }
                    }
                } else {
                    // Buffer until emulator is ready
                    preBuffer.add(bytes)
                }
            }
        }
    }

    fun writeInput(data: String) {
        scope.launch {
            try {
                sshClient.sendRawBytes(data.toByteArray(StandardCharsets.UTF_8))
            } catch (e: Exception) {
                DebugLog.log("SSH_TERM", "writeInput failed: ${e.message}")
            }
        }
    }

    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        session.mEmulator?.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        sshClient.resizePty(columns, rows)
    }

    fun destroy() {
        collectJob?.cancel()
    }
}

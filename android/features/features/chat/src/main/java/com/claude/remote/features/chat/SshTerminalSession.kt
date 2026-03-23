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
import java.nio.charset.StandardCharsets

/**
 * Bridges our SshClient I/O to Termux's TerminalSession/TerminalEmulator.
 *
 * - SSH output bytes → TerminalEmulator (for parsing/rendering)
 * - User keyboard input (from TerminalView) → SSH stdin via TerminalOutput.write()
 */
class SshTerminalSession(
    private val sshClient: SshClient,
    private val scope: CoroutineScope,
    private val client: TerminalSessionClient
) {
    private val handler = Handler(Looper.getMainLooper())
    private var collectJob: Job? = null

    // Dummy session — we only use it as a container for the emulator
    val session: TerminalSession = TerminalSession(
        "/bin/sh", "/", arrayOf(), arrayOf(),
        null, client
    )

    /** Call from main thread after view has measured. */
    fun start(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        DebugLog.log("SSH_TERM", "start: ${columns}x${rows} cell=${cellWidthPixels}x${cellHeightPixels}")

        // Create emulator that sends keyboard/output data to SSH
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
            10000, // transcriptRows (scrollback)
            client
        )

        // Set emulator on session (we made mEmulator public)
        session.mEmulator = emulator
        session.mClient = client

        // Resize SSH PTY
        sshClient.resizePty(columns, rows)

        // Start collecting SSH output → feed to emulator
        startCollecting()
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = scope.launch {
            sshClient.outputStream.collect { chunk ->
                val bytes = chunk.toByteArray(StandardCharsets.UTF_8)
                handler.post {
                    try {
                        session.mEmulator?.append(bytes, bytes.size)
                        session.mClient?.onTextChanged(session)
                    } catch (e: Exception) {
                        DebugLog.log("SSH_TERM", "append failed: ${e.message}")
                    }
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

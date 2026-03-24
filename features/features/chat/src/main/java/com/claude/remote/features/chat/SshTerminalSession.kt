package com.claude.remote.features.chat

import android.os.Handler
import android.os.Looper
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bridges SSH I/O to Termux's TerminalEmulator.
 *
 * Extends TerminalSession to be compatible with TerminalView.attachSession(),
 * but replaces local process I/O with SSH.
 */
class SshTerminalSession(
    private val sshClient: SshClient,
    private val scope: CoroutineScope,
    client: TerminalSessionClient
) : TerminalSession("/bin/sh", "/", arrayOf(), arrayOf(), null, client) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Override: create emulator without forking a local process.
     */
    override fun initializeEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        mEmulator = TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, null, mClient)
        DebugLog.log("SSH_TERM", "Emulator initialized: ${columns}x${rows}")
    }

    /**
     * Override: resize emulator + remote PTY (no local PTY).
     */
    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels)
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
        sshClient.resizePty(columns, rows)
    }

    /**
     * Override: send data to SSH instead of local PTY.
     */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        scope.launch {
            try {
                sshClient.sendRawBytes(data.copyOfRange(offset, offset + count))
            } catch (e: Exception) {
                DebugLog.log("SSH_TERM", "Write failed: ${e.message}")
            }
        }
    }

    /**
     * Feed raw bytes from SSH into the terminal emulator.
     * Must be called on main thread.
     */
    fun processBytes(data: ByteArray, count: Int = data.size) {
        mEmulator?.append(data, count)
        notifyScreenUpdate()
    }

    /**
     * Post SSH output to main thread for processing.
     */
    fun postProcessBytes(data: ByteArray) {
        val copy = data.copyOf()
        mainHandler.post {
            processBytes(copy)
        }
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        // Optional: could update UI
    }

    override fun onCopyTextToClipboard(text: String?) {}
    override fun onPasteTextFromClipboard() {}

    override fun onBell() {
        mClient?.onBell(this)
    }

    override fun onColorsChanged() {
        mClient?.onColorsChanged(this)
    }

    override fun isRunning(): Boolean = sshClient.isAttachedToTmux

    override fun finishIfRunning() {
        // No local process to kill
    }
}

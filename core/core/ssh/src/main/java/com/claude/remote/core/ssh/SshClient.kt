package com.claude.remote.core.ssh

import com.claude.remote.core.ui.components.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

data class ShellChannelHandle(
    val inputStream: InputStream,
    val outputStream: OutputStream,
    val resizePty: (cols: Int, rows: Int) -> Unit,
    val disconnect: () -> Unit
)

interface SshClient {
    val connectionState: StateFlow<ConnectionState>
    val outputStream: Flow<String>
    var isAttachedToTmux: Boolean
    var currentSessionName: String

    val host: String
    val port: Int
    val username: String
    val password: String

    suspend fun connect(host: String, port: Int, username: String, password: String)
    suspend fun reconnect()
    suspend fun disconnect()
    suspend fun executeCommand(command: String): String
    suspend fun sendInput(input: String)
    suspend fun sendRawBytes(data: ByteArray)
    fun resizePty(cols: Int, rows: Int)

    /**
     * Open a new shell channel on the existing SSH session.
     * Returns handle with streams and connect/resize/disconnect callbacks.
     * Channel is already connected when returned.
     */
    suspend fun openShellChannel(cols: Int, rows: Int): ShellChannelHandle
}

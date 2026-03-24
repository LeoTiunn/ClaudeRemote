package com.claude.remote.core.ssh

import com.claude.remote.core.ui.components.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SshClient {
    val connectionState: StateFlow<ConnectionState>
    val outputStream: Flow<String>
    var isAttachedToTmux: Boolean
    var currentSessionName: String

    suspend fun connect(host: String, port: Int, username: String, password: String)
    suspend fun reconnect()
    suspend fun disconnect()
    suspend fun executeCommand(command: String): String
    suspend fun sendInput(input: String)
    suspend fun sendRawBytes(data: ByteArray)
    fun resizePty(cols: Int, rows: Int)
}
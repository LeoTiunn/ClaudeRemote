package com.claude.remote.core.tmux

import com.claude.remote.core.ssh.SshClient
import kotlinx.coroutines.flow.Flow

interface TmuxSessionManager {
    suspend fun listSessions(client: SshClient): List<TmuxSession>
    suspend fun createSession(sessionName: String, workingDirectory: String, client: SshClient): TmuxSession
    suspend fun attachToSession(sessionName: String, client: SshClient)
    suspend fun sendCommand(sessionName: String, command: String, client: SshClient)
    suspend fun killSession(sessionName: String, client: SshClient)
    fun streamSessionOutput(client: SshClient): Flow<String>
}
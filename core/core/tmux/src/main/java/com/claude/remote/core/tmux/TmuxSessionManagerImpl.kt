package com.claude.remote.core.tmux

import com.claude.remote.core.ssh.SshClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmuxSessionManagerImpl @Inject constructor() : TmuxSessionManager {

    override suspend fun listSessions(client: SshClient): List<TmuxSession> {
        val output = client.executeCommand("tmux list-sessions -F '#{session_name}|#{window_name}' 2>/dev/null || true")
        if (output.isBlank()) return emptyList()

        return output.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size >= 2) {
                TmuxSession(
                    name = parts[0].trim(),
                    windowName = parts[1].trim()
                )
            } else null
        }
    }

    override suspend fun createSession(sessionName: String, workingDirectory: String, client: SshClient): TmuxSession {
        client.executeCommand("tmux new-session -d -s '$sessionName' -c '$workingDirectory' 2>/dev/null || true")
        return TmuxSession(
            name = sessionName,
            windowName = sessionName
        )
    }

    override suspend fun attachToSession(sessionName: String, client: SshClient) {
        client.sendInput("tmux attach -t $sessionName")
    }

    override suspend fun sendCommand(sessionName: String, command: String, client: SshClient) {
        client.sendInput(command)
    }

    override suspend fun killSession(sessionName: String, client: SshClient) {
        client.executeCommand("tmux kill-session -t '$sessionName' 2>/dev/null || true")
    }

    override fun streamSessionOutput(client: SshClient): Flow<String> = flow {
        client.outputStream.collect { output ->
            emit(output)
        }
    }
}
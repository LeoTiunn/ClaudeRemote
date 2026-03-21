package com.claude.remote.core.tmux

import com.claude.remote.core.ssh.SshClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmuxSessionManagerImpl @Inject constructor() : TmuxSessionManager {

    override suspend fun listSessions(client: SshClient): List<TmuxSession> {
        val output = client.executeCommand("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux list-sessions -F '#{session_name}|#{pane_current_path}' 2>/dev/null || true")
        if (output.isBlank()) return emptyList()

        return output.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                TmuxSession(
                    name = parts[0].trim(),
                    windowName = parts.getOrElse(1) { parts[0] }.trim()
                )
            } else null
        }
    }

    override suspend fun listRemoteRepos(client: SshClient): List<String> {
        val output = client.executeCommand("find ~/Developer -maxdepth 3 -type d -name .git 2>/dev/null | sed 's|/\\.git\$||' | sed 's|.*/Developer/||' | sort")
        if (output.isBlank()) return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }

    override suspend fun createSession(sessionName: String, workingDirectory: String, client: SshClient): TmuxSession {
        client.executeCommand("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux new-session -d -s '$sessionName' -c '$workingDirectory' 'claude --continue --dangerously-skip-permissions'")
        return TmuxSession(
            name = sessionName,
            windowName = sessionName
        )
    }

    override suspend fun attachToSession(sessionName: String, client: SshClient) {
        client.sendInput("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux attach -t '$sessionName'")
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
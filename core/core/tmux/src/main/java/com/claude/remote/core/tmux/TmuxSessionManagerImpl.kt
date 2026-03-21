package com.claude.remote.core.tmux

import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmuxSessionManagerImpl @Inject constructor() : TmuxSessionManager {

    override suspend fun listSessions(client: SshClient): List<TmuxSession> {
        DebugLog.log("TMUX", "listSessions: calling executeCommand")
        val output = client.executeCommand("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux list-sessions -F '#{session_name}|#{pane_current_path}' 2>/dev/null || true")
        DebugLog.log("TMUX", "listSessions result(${output.length}): ${output.take(200)}")
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
        DebugLog.log("TMUX", "listRemoteRepos: calling executeCommand")
        val output = client.executeCommand("find ~/Developer -maxdepth 3 -type d -name .git 2>/dev/null | sed 's|/\\.git\$||' | sed 's|.*/Developer/||' | sort")
        DebugLog.log("TMUX", "listRemoteRepos result(${output.length}): ${output.take(200)}")
        if (output.isBlank()) return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }

    override suspend fun createSession(sessionName: String, workingDirectory: String, client: SshClient): TmuxSession {
        // Check if session already exists — if so, just reuse it
        val existing = client.executeCommand("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux has-session -t '$sessionName' 2>/dev/null && echo EXISTS || echo NONE")
        if (existing.trim() == "EXISTS") {
            DebugLog.log("TMUX", "Session '$sessionName' already exists, reusing")
            return TmuxSession(name = sessionName, windowName = sessionName)
        }
        // Use bash as the shell so the session survives if claude exits
        // Use double quotes around -c so $HOME expands
        client.executeCommand("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux new-session -d -s '$sessionName' -c \"$workingDirectory\"")
        // Start claude inside the session (session stays alive as bash even if claude exits)
        client.executeCommand("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux send-keys -t '$sessionName' 'claude --continue --dangerously-skip-permissions' Enter")
        return TmuxSession(
            name = sessionName,
            windowName = sessionName
        )
    }

    override suspend fun attachToSession(sessionName: String, client: SshClient) {
        client.isAttachedToTmux = true
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
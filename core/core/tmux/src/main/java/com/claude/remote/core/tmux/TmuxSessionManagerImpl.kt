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
        // Resolve the Claude Code conversation name for each tmux session, but WITHOUT a
        // long payload. Android's executeCommand runs inside the shared interactive PTY
        // shell; a 2.5KB one-liner (the old base64 python helper) exceeded the PTY line
        // buffer and jammed it → the END marker never printed → 30s timeout → endless
        // "loading". This is a compact pure-shell one-liner (~560 chars, verified to
        // complete under a real PTY): for each session, walk the pane_pid's descendants
        // to find the claude process, then read ~/.claude/sessions/<pid>.json for name +
        // status. Emits `name|cwd|cc_name|status`. Sessions with no live claude get empty
        // cc_name/status → displayName falls back to the tmux name.
        val cmd = "export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; " +
            "kids(){ for c in \$(pgrep -P \"\$1\" 2>/dev/null); do echo \"\$c\"; kids \"\$c\"; done; }; " +
            "tmux list-sessions -F '#{session_name}|#{pane_current_path}|#{pane_pid}' 2>/dev/null | " +
            "while IFS='|' read n d pp; do cc=\"\"; st=\"\"; " +
            "for k in \$pp \$(kids \"\$pp\"); do jf=\"\$HOME/.claude/sessions/\$k.json\"; " +
            "if [ -f \"\$jf\" ]; then " +
            "cc=\$(sed -n 's/.*\"name\":\"\\([^\"]*\\)\".*/\\1/p' \"\$jf\" | head -1); " +
            "st=\$(sed -n 's/.*\"status\":\"\\([^\"]*\\)\".*/\\1/p' \"\$jf\" | head -1); break; fi; done; " +
            "echo \"\$n|\$d|\$cc|\$st\"; done"
        val output = client.executeCommand(cmd)
        DebugLog.log("TMUX", "listSessions result(${output.length}): ${output.take(200)}")
        if (output.isBlank()) return emptyList()

        return output.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 4)
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                TmuxSession(
                    name = parts[0].trim(),
                    windowName = parts.getOrElse(1) { parts[0] }.trim(),
                    claudeName = parts.getOrElse(2) { "" }.trim(),
                    status = parts.getOrElse(3) { "" }.trim()
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
        client.executeCommand("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux new-session -d -s '$sessionName' -c \"$workingDirectory\" \\; set-option -t '$sessionName' history-limit 10000")
        // Start claude inside the session (session stays alive as bash even if claude exits)
        client.executeCommand("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux send-keys -t '$sessionName' 'claude --continue --dangerously-skip-permissions' Enter")
        return TmuxSession(
            name = sessionName,
            windowName = sessionName
        )
    }

    override suspend fun attachToSession(sessionName: String, client: SshClient) {
        client.isAttachedToTmux = true
        client.currentSessionName = sessionName
        client.sendInput("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux attach -t '$sessionName'")
    }

    override suspend fun capturePane(sessionName: String, client: SshClient): String {
        return client.executeCommand("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; tmux capture-pane -t '$sessionName' -p -S - 2>/dev/null || true")
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
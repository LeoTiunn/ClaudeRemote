package com.claude.remote.core.tmux

import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmuxSessionManagerImpl @Inject constructor() : TmuxSessionManager {

    companion object {
        // base64 of the session-resolver python (source: ios/scripts/webmux_sessions_helper.py).
        // Decoded + piped to python3 on the server: resolves each tmux session's live
        // claude process → ~/.claude/sessions/<pid>.json → conversation name + busy/idle.
        // Emits `tmux_name|cwd|cc_name|status` per line. Same helper the iOS app uses.
        private const val SESSION_HELPER_B64 = "aW1wb3J0IGpzb24sIG9zLCBnbG9iLCBzdWJwcm9jZXNzLCBzeXMKU0VTUyA9IG9zLnBhdGguZXhwYW5kdXNlcigifi8uY2xhdWRlL3Nlc3Npb25zIikKCiMgQ2xhdWRlJ3Mgb3duIHNlc3Npb24gcmVnaXN0cnksIGtleWVkIGJ5IHBpZDogbmFtZSArIHN0YXR1cy4KYnlfcGlkID0ge30KZm9yIGYgaW4gZ2xvYi5nbG9iKFNFU1MgKyAiLyouanNvbiIpOgogICAgdHJ5OgogICAgICAgIG8gPSBqc29uLmxvYWQob3BlbihmKSkKICAgIGV4Y2VwdCBFeGNlcHRpb246CiAgICAgICAgY29udGludWUKICAgIHAgPSBvLmdldCgicGlkIikKICAgIGlmIHA6CiAgICAgICAgYnlfcGlkW2ludChwKV0gPSB7Im5hbWUiOiBvLmdldCgibmFtZSIpIG9yICIiLCAic3RhdHVzIjogby5nZXQoInN0YXR1cyIpIG9yICIifQoKIyBwcm9jZXNzIHRhYmxlOiBwaWQgLT4gKHBwaWQsIGNvbW0pCnRyeToKICAgIHBzID0gc3VicHJvY2Vzcy5ydW4oWyJwcyIsICItYXhvIiwgInBpZD0scHBpZD0sY29tbT0iXSwgY2FwdHVyZV9vdXRwdXQ9VHJ1ZSwgdGV4dD1UcnVlKS5zdGRvdXQKZXhjZXB0IEV4Y2VwdGlvbjoKICAgIHBzID0gIiIKa2lkcyA9IHt9CmNvbW0gPSB7fQpmb3IgbGluZSBpbiBwcy5zcGxpdGxpbmVzKCk6CiAgICBwYXJ0cyA9IGxpbmUuc3BsaXQoTm9uZSwgMikKICAgIGlmIGxlbihwYXJ0cykgPCAzOgogICAgICAgIGNvbnRpbnVlCiAgICB0cnk6CiAgICAgICAgcGlkLCBwcGlkID0gaW50KHBhcnRzWzBdKSwgaW50KHBhcnRzWzFdKQogICAgZXhjZXB0IFZhbHVlRXJyb3I6CiAgICAgICAgY29udGludWUKICAgIGMgPSBwYXJ0c1syXQogICAga2lkcy5zZXRkZWZhdWx0KHBwaWQsIFtdKS5hcHBlbmQocGlkKQogICAgY29tbVtwaWRdID0gYwoKZGVmIGZpbmRfY2xhdWRlKHJvb3QpOgogICAgc3RhY2sgPSBsaXN0KGtpZHMuZ2V0KHJvb3QsIFtdKSkKICAgIHNlZW4gPSBzZXQoKQogICAgd2hpbGUgc3RhY2s6CiAgICAgICAgcCA9IHN0YWNrLnBvcCgpCiAgICAgICAgaWYgcCBpbiBzZWVuOgogICAgICAgICAgICBjb250aW51ZQogICAgICAgIHNlZW4uYWRkKHApCiAgICAgICAgY20gPSBjb21tLmdldChwLCAiIikubG93ZXIoKQogICAgICAgIGlmICgiY2xhdWRlIiBpbiBjbSBvciBjbS5lbmRzd2l0aCgibm9kZSIpKSBhbmQgcCBpbiBieV9waWQ6CiAgICAgICAgICAgIHJldHVybiBwCiAgICAgICAgc3RhY2suZXh0ZW5kKGtpZHMuZ2V0KHAsIFtdKSkKICAgIHJldHVybiBOb25lCgp0cnk6CiAgICBvdXQgPSBzdWJwcm9jZXNzLnJ1bigKICAgICAgICBbInRtdXgiLCAibGlzdC1zZXNzaW9ucyIsICItRiIsICIje3Nlc3Npb25fbmFtZX1cdCN7cGFuZV9jdXJyZW50X3BhdGh9XHQje3BhbmVfcGlkfSJdLAogICAgICAgIGNhcHR1cmVfb3V0cHV0PVRydWUsIHRleHQ9VHJ1ZSkuc3Rkb3V0CmV4Y2VwdCBFeGNlcHRpb246CiAgICBvdXQgPSAiIgoKZm9yIGxpbmUgaW4gb3V0LnNwbGl0bGluZXMoKToKICAgIGNvbHMgPSBsaW5lLnNwbGl0KCJcdCIpCiAgICBpZiBsZW4oY29scykgPCAzOgogICAgICAgIGNvbnRpbnVlCiAgICBuYW1lLCBjd2QsIHBwID0gY29sc1swXSwgY29sc1sxXSwgY29sc1syXQogICAgY3AgPSBmaW5kX2NsYXVkZShpbnQocHApKSBpZiBwcC5pc2RpZ2l0KCkgZWxzZSBOb25lCiAgICBpbmZvID0gYnlfcGlkLmdldChjcCkgaWYgY3AgZWxzZSBOb25lCiAgICBjY19uYW1lID0gaW5mb1sibmFtZSJdIGlmIGluZm8gZWxzZSAiIgogICAgc3RhdHVzID0gaW5mb1sic3RhdHVzIl0gaWYgaW5mbyBlbHNlICIiCiAgICAjIHRtdXhfbmFtZSB8IGN3ZCB8IGNjX25hbWUgfCBzdGF0dXMgICAocGlwZS1kZWxpbWl0ZWQ7IG5hbWVzIG5ldmVyIGNvbnRhaW4gJ3wnKQogICAgc3lzLnN0ZG91dC53cml0ZSgiJXN8JXN8JXN8JXNcbiIgJSAobmFtZSwgY3dkLCBjY19uYW1lLCBzdGF0dXMpKQo="
    }

    override suspend fun listSessions(client: SshClient): List<TmuxSession> {
        DebugLog.log("TMUX", "listSessions: calling executeCommand")
        // Claude Code is the display source of truth for names. The helper resolves
        // each tmux session's live claude → sessions json (cc name + busy/idle),
        // one SSH round-trip. Falls back to the plain tmux listing (empty cc/status)
        // when python3 isn't on PATH → displayName = tmux name.
        val output = client.executeCommand(
            "export PATH=\$HOME/.local/bin:/opt/homebrew/bin:\$PATH; " +
            "printf '%s' '$SESSION_HELPER_B64' | base64 --decode | python3 - 2>/dev/null " +
            "|| tmux list-sessions -F '#{session_name}|#{pane_current_path}||' 2>/dev/null || true"
        )
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
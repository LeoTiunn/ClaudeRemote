import Foundation

/// Mirrors Android `TmuxSession`.
struct TmuxSession: Identifiable, Equatable {
    /// tmux session name — the STABLE id used to attach/switch/kill. Never shown
    /// directly when a Claude Code conversation name is available.
    let name: String
    let windowName: String   // pane_current_path (the session's cwd)
    /// Claude Code's own conversation name (`/rename` sets it) — the source of
    /// truth for what the user sees. Empty when the session has no live claude.
    var claudeName: String = ""
    /// Claude Code busy/idle flag from ~/.claude/sessions/<pid>.json.
    var status: String = ""
    var id: String { name }

    /// The session's working directory — same field webmux groups by.
    var cwd: String { windowName }

    /// What the UI shows: Claude Code conversation name (source of truth),
    /// falling back to the tmux session name for non-Claude sessions.
    var displayName: String { claudeName.isEmpty ? name : claudeName }
}

/// A project groups the live tmux sessions sharing one cwd, exactly like the
/// webmux sidebar (project → sessions). Project label = last path segment of cwd.
struct TmuxProject: Identifiable, Equatable {
    let cwd: String
    let sessions: [TmuxSession]
    var id: String { cwd }
    /// e.g. /Users/x/Developer/leo-chang/webmux → "webmux".
    var name: String {
        let trimmed = cwd.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return trimmed.split(separator: "/").last.map(String.init) ?? cwd
    }
}

extension Array where Element == TmuxSession {
    /// Group sessions into projects by cwd, preserving first-seen order (matches
    /// webmux's `buildProjects`). Sessions within a project keep their list order.
    func groupedByProject() -> [TmuxProject] {
        var order: [String] = []
        var byCwd: [String: [TmuxSession]] = [:]
        for s in self {
            if byCwd[s.cwd] == nil { order.append(s.cwd) }
            byCwd[s.cwd, default: []].append(s)
        }
        return order.map { TmuxProject(cwd: $0, sessions: byCwd[$0] ?? []) }
    }
}

/// tmux operations over the SSH command channel. Commands are byte-for-byte the
/// same as Android `TmuxSessionManagerImpl.kt` so behavior matches exactly.
enum TmuxSessionManager {
    private static let pathPrefix = "export PATH=$HOME/.local/bin:/opt/homebrew/bin:$PATH; "

    /// base64 of the session-resolver python (see repo: scripts/webmux_sessions_helper.py).
    /// Decoded + piped to python3 on the server. Contains no quotes so it's safe
    /// inside the single-quoted printf.
    private static let sessionHelperB64 = "aW1wb3J0IGpzb24sIG9zLCBnbG9iLCBzdWJwcm9jZXNzLCBzeXMKU0VTUyA9IG9zLnBhdGguZXhwYW5kdXNlcigifi8uY2xhdWRlL3Nlc3Npb25zIikKCiMgQ2xhdWRlJ3Mgb3duIHNlc3Npb24gcmVnaXN0cnksIGtleWVkIGJ5IHBpZDogbmFtZSArIHN0YXR1cy4KYnlfcGlkID0ge30KZm9yIGYgaW4gZ2xvYi5nbG9iKFNFU1MgKyAiLyouanNvbiIpOgogICAgdHJ5OgogICAgICAgIG8gPSBqc29uLmxvYWQob3BlbihmKSkKICAgIGV4Y2VwdCBFeGNlcHRpb246CiAgICAgICAgY29udGludWUKICAgIHAgPSBvLmdldCgicGlkIikKICAgIGlmIHA6CiAgICAgICAgYnlfcGlkW2ludChwKV0gPSB7Im5hbWUiOiBvLmdldCgibmFtZSIpIG9yICIiLCAic3RhdHVzIjogby5nZXQoInN0YXR1cyIpIG9yICIifQoKIyBwcm9jZXNzIHRhYmxlOiBwaWQgLT4gKHBwaWQsIGNvbW0pCnRyeToKICAgIHBzID0gc3VicHJvY2Vzcy5ydW4oWyJwcyIsICItYXhvIiwgInBpZD0scHBpZD0sY29tbT0iXSwgY2FwdHVyZV9vdXRwdXQ9VHJ1ZSwgdGV4dD1UcnVlKS5zdGRvdXQKZXhjZXB0IEV4Y2VwdGlvbjoKICAgIHBzID0gIiIKa2lkcyA9IHt9CmNvbW0gPSB7fQpmb3IgbGluZSBpbiBwcy5zcGxpdGxpbmVzKCk6CiAgICBwYXJ0cyA9IGxpbmUuc3BsaXQoTm9uZSwgMikKICAgIGlmIGxlbihwYXJ0cykgPCAzOgogICAgICAgIGNvbnRpbnVlCiAgICB0cnk6CiAgICAgICAgcGlkLCBwcGlkID0gaW50KHBhcnRzWzBdKSwgaW50KHBhcnRzWzFdKQogICAgZXhjZXB0IFZhbHVlRXJyb3I6CiAgICAgICAgY29udGludWUKICAgIGMgPSBwYXJ0c1syXQogICAga2lkcy5zZXRkZWZhdWx0KHBwaWQsIFtdKS5hcHBlbmQocGlkKQogICAgY29tbVtwaWRdID0gYwoKZGVmIGZpbmRfY2xhdWRlKHJvb3QpOgogICAgc3RhY2sgPSBsaXN0KGtpZHMuZ2V0KHJvb3QsIFtdKSkKICAgIHNlZW4gPSBzZXQoKQogICAgd2hpbGUgc3RhY2s6CiAgICAgICAgcCA9IHN0YWNrLnBvcCgpCiAgICAgICAgaWYgcCBpbiBzZWVuOgogICAgICAgICAgICBjb250aW51ZQogICAgICAgIHNlZW4uYWRkKHApCiAgICAgICAgY20gPSBjb21tLmdldChwLCAiIikubG93ZXIoKQogICAgICAgIGlmICgiY2xhdWRlIiBpbiBjbSBvciBjbS5lbmRzd2l0aCgibm9kZSIpKSBhbmQgcCBpbiBieV9waWQ6CiAgICAgICAgICAgIHJldHVybiBwCiAgICAgICAgc3RhY2suZXh0ZW5kKGtpZHMuZ2V0KHAsIFtdKSkKICAgIHJldHVybiBOb25lCgp0cnk6CiAgICBvdXQgPSBzdWJwcm9jZXNzLnJ1bigKICAgICAgICBbInRtdXgiLCAibGlzdC1zZXNzaW9ucyIsICItRiIsICIje3Nlc3Npb25fbmFtZX1cdCN7cGFuZV9jdXJyZW50X3BhdGh9XHQje3BhbmVfcGlkfSJdLAogICAgICAgIGNhcHR1cmVfb3V0cHV0PVRydWUsIHRleHQ9VHJ1ZSkuc3Rkb3V0CmV4Y2VwdCBFeGNlcHRpb246CiAgICBvdXQgPSAiIgoKZm9yIGxpbmUgaW4gb3V0LnNwbGl0bGluZXMoKToKICAgIGNvbHMgPSBsaW5lLnNwbGl0KCJcdCIpCiAgICBpZiBsZW4oY29scykgPCAzOgogICAgICAgIGNvbnRpbnVlCiAgICBuYW1lLCBjd2QsIHBwID0gY29sc1swXSwgY29sc1sxXSwgY29sc1syXQogICAgY3AgPSBmaW5kX2NsYXVkZShpbnQocHApKSBpZiBwcC5pc2RpZ2l0KCkgZWxzZSBOb25lCiAgICBpbmZvID0gYnlfcGlkLmdldChjcCkgaWYgY3AgZWxzZSBOb25lCiAgICBjY19uYW1lID0gaW5mb1sibmFtZSJdIGlmIGluZm8gZWxzZSAiIgogICAgc3RhdHVzID0gaW5mb1sic3RhdHVzIl0gaWYgaW5mbyBlbHNlICIiCiAgICAjIHRtdXhfbmFtZSB8IGN3ZCB8IGNjX25hbWUgfCBzdGF0dXMgICAocGlwZS1kZWxpbWl0ZWQ7IG5hbWVzIG5ldmVyIGNvbnRhaW4gJ3wnKQogICAgc3lzLnN0ZG91dC53cml0ZSgiJXN8JXN8JXN8JXNcbiIgJSAobmFtZSwgY3dkLCBjY19uYW1lLCBzdGF0dXMpKQo="

    static func listSessions(_ ssh: SshClient) async throws -> [TmuxSession] {
        // Claude Code is the source of truth for names. This helper resolves each
        // tmux session's live claude process → ~/.claude/sessions/<pid>.json → the
        // `/rename`-set conversation name + busy/idle status, exactly like webmux.
        // Emits `tmux_name|cwd|cc_name|status` per line. Falls back to the plain
        // tmux listing if python3 isn't on PATH (then displayName = tmux name).
        let out = try await ssh.executeCommand(
            pathPrefix + "printf '%s' '\(sessionHelperB64)' | base64 --decode | python3 - 2>/dev/null"
            + " || tmux list-sessions -F '#{session_name}|#{pane_current_path}||' 2>/dev/null || true")
        if out.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return [] }
        return out.split(separator: "\n").compactMap { line in
            let parts = line.split(separator: "|", maxSplits: 3, omittingEmptySubsequences: false)
            guard let name = parts.first.map(String.init)?.trimmingCharacters(in: .whitespaces),
                  !name.isEmpty else { return nil }
            let window = parts.count > 1 ? String(parts[1]).trimmingCharacters(in: .whitespaces) : name
            let ccName = parts.count > 2 ? String(parts[2]).trimmingCharacters(in: .whitespaces) : ""
            let status = parts.count > 3 ? String(parts[3]).trimmingCharacters(in: .whitespaces) : ""
            return TmuxSession(name: name, windowName: window, claudeName: ccName, status: status)
        }
    }

    static func createSession(name: String, workDir: String, _ ssh: SshClient) async throws {
        let escaped = name.replacingOccurrences(of: "'", with: "'\\''")
        let existing = try await ssh.executeCommand(
            pathPrefix + "tmux has-session -t '\(escaped)' 2>/dev/null && echo EXISTS || echo NONE")
        if existing.trimmingCharacters(in: .whitespacesAndNewlines) == "EXISTS" { return }
        _ = try await ssh.executeCommand(
            pathPrefix + "tmux new-session -d -s '\(escaped)' -c \"\(workDir)\" \\; set-option -t '\(escaped)' history-limit 10000")
        _ = try await ssh.executeCommand(
            pathPrefix + "tmux send-keys -t '\(escaped)' 'claude --continue --dangerously-skip-permissions' Enter")
    }

    static func killSession(_ name: String, _ ssh: SshClient) async throws {
        let escaped = name.replacingOccurrences(of: "'", with: "'\\''")
        _ = try await ssh.executeCommand("tmux kill-session -t '\(escaped)' 2>/dev/null || true")
    }

    static func searchRepos(query: String, _ ssh: SshClient) async throws -> [String] {
        let q = query.replacingOccurrences(of: "'", with: "\\'")
        let out = try await ssh.executeCommand(
            "find ~/Developer -maxdepth 3 -type d -name .git 2>/dev/null | sed 's|/\\.git$||' | sed 's|.*/Developer/||' | grep -i '\(q)' | sort | head -20")
        return out.split(separator: "\n").map(String.init).filter { !$0.isEmpty }
    }
}

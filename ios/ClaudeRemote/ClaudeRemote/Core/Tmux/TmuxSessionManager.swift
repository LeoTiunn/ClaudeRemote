import Foundation

/// Mirrors Android `TmuxSession`.
struct TmuxSession: Identifiable, Equatable {
    let name: String
    let windowName: String
    var id: String { name }
}

/// tmux operations over the SSH command channel. Commands are byte-for-byte the
/// same as Android `TmuxSessionManagerImpl.kt` so behavior matches exactly.
enum TmuxSessionManager {
    private static let pathPrefix = "export PATH=$HOME/.local/bin:/opt/homebrew/bin:$PATH; "

    static func listSessions(_ ssh: SshClient) async throws -> [TmuxSession] {
        let out = try await ssh.executeCommand(
            pathPrefix + "tmux list-sessions -F '#{session_name}|#{pane_current_path}' 2>/dev/null || true")
        if out.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return [] }
        return out.split(separator: "\n").compactMap { line in
            let parts = line.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
            guard let name = parts.first.map(String.init)?.trimmingCharacters(in: .whitespaces),
                  !name.isEmpty else { return nil }
            let window = parts.count > 1 ? String(parts[1]).trimmingCharacters(in: .whitespaces) : name
            return TmuxSession(name: name, windowName: window)
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

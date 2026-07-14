import SwiftUI
import Combine

/// Ports Android `ChatViewModel.kt` behavior 1:1.
@MainActor
final class ChatViewModel: ObservableObject {
    private let ssh = SshClient.shared
    let holder = TerminalHolder.shared

    @Published var connectionState: ConnectionState = .disconnected
    @Published var sessionName = ""
    @Published var isReconnecting = false
    @Published var statusMessage: String?
    @Published var isUploading = false
    @Published var availableSessions: [TmuxSession] = []
    @Published var isLoadingSessions = false
    @Published var error: String?

    private var cancellables = Set<AnyCancellable>()

    init() {
        ssh.$connectionState
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.connectionState = $0 }
            .store(in: &cancellables)

        // Swipe on the terminal → tmux copy-mode scroll (no local scrollback in alt buffer).
        holder.onScrollLines = { [weak self] lines in
            self?.scrollHistory(lines: lines)
        }
    }

    /// Scroll exactly like the Android app: with tmux mouse mode on, a finger swipe sends
    /// MOUSE WHEEL events (not arrow keys — those get eaten by the CLI's input as command
    /// history). tmux interprets wheel-up/down as scrollback movement. SGR (1006) format:
    ///   ESC [ < button ; col ; row M   (button 64 = wheel up, 65 = wheel down)
    /// Positive lines = scroll up (into history), negative = down.
    func scrollHistory(lines: Int) {
        guard lines != 0 else { return }
        let up = lines > 0
        let count = min(abs(lines), 8)
        let button = up ? 64 : 65
        // Report the wheel at a fixed cell near the top-middle of the screen.
        let seq = "\u{1b}[<\(button);40;5M"
        let bytes = Array(seq.utf8)
        for _ in 0..<count { holder.writeBytes(bytes) }
    }

    /// Begin: connect SSH (if needed), open terminal channel, attach tmux session.
    func start(sessionName: String) {
        self.sessionName = sessionName
        if holder.isSessionRunning && holder.attachedSessionName == sessionName {
            return // same session already attached
        }
        connectAndAttach(sessionName)
    }

    func connectAndAttach(_ sessionName: String) {
        holder.destroySession()
        self.sessionName = sessionName
        isReconnecting = true
        statusMessage = "Connecting..."

        // Pull connection params from settings (host/port/user may be empty if we
        // navigated here without first connecting on the session screen).
        let settings = SettingsStore.shared
        let host = ssh.host.isEmpty ? settings.sshHost : ssh.host
        let port = ssh.port == 0 ? (Int(settings.sshPort) ?? 22) : ssh.port
        let username = ssh.username.isEmpty ? settings.sshUsername : ssh.username
        let password = ssh.password.isEmpty ? settings.getSshPassword() : ssh.password

        Task {
            do {
                if ssh.connectionState != .connected {
                    statusMessage = "Connecting to SSH..."
                    try await ssh.connect(host: host, port: port,
                                          username: username, password: password)
                }
                statusMessage = "Opening terminal..."
                do {
                    try await holder.createSession(ssh)
                } catch {
                    statusMessage = "Reconnecting SSH..."
                    try await ssh.connect(host: host, port: port,
                                          username: username, password: password)
                    try await holder.createSession(ssh)
                }
                statusMessage = "Attaching to \(sessionName)..."
                try? await Task.sleep(for: .milliseconds(300))
                let esc = sessionName.replacingOccurrences(of: "'", with: "'\\''")
                holder.writeToSession("tmux attach -t '\(esc)' || tmux new-session -s '\(esc)'\r")
                holder.attachedSessionName = sessionName
                ssh.currentSessionName = sessionName
                // Our swipe sends mouse-wheel events; tmux needs mouse mode ON to capture
                // them, and alternate-scroll ON so the wheel scrolls history while a
                // full-screen app (Claude CLI) owns the alt screen. Same as Android.
                try? await Task.sleep(for: .milliseconds(400))
                _ = try? await ssh.executeCommand("tmux set -g mouse on \\; set -g alternate-scroll on")
                ssh.isAttachedToTmux = true
                isReconnecting = false
                statusMessage = nil
            } catch {
                self.error = "SSH failed: \(error.localizedDescription)"
                isReconnecting = false
                statusMessage = nil
            }
        }
    }

    func reconnect() {
        if !sessionName.isEmpty { connectAndAttach(sessionName) }
    }

    /// On resume (app came back to foreground): the terminal PTY channel is usually dead
    /// after the screen was off for a while, even if the command channel still answers —
    /// they're separate channels, so probing with `echo ok` gives a false "alive". Probe
    /// the PTY itself: send Ctrl+L and check we get a redraw; if nothing comes back
    /// quickly, re-attach a fresh PTY (the reliable path).
    func checkConnectionAndReconnect() {
        guard !sessionName.isEmpty else { return }
        Task {
            // Probe the actual PTY: did we receive any bytes shortly after a redraw nudge?
            let gotOutput = await holder.probeRedraw(timeout: 2.0)
            if !gotOutput {
                // PTY is stale → re-attach fresh (same path as session switch).
                connectAndAttach(sessionName)
            }
        }
    }

    func loadAvailableSessions() {
        isLoadingSessions = true
        Task {
            let wasAttached = ssh.isAttachedToTmux
            ssh.isAttachedToTmux = false
            defer { ssh.isAttachedToTmux = wasAttached }
            do {
                availableSessions = try await TmuxSessionManager.listSessions(ssh)
            } catch { /* keep prior list */ }
            isLoadingSessions = false
        }
    }

    func switchSession(_ name: String) {
        guard name != sessionName else { return }
        // Re-open a fresh PTY attached to the new session. switch-client over a separate
        // command channel doesn't reliably retarget our attached client and left input
        // frozen — re-attaching is a touch slower but always works and keeps input live.
        connectAndAttach(name)
    }

    func restartCli(allSessions: Bool) {
        Task {
            if !allSessions {
                holder.writeToSession("/exit\r")
                try? await Task.sleep(for: .seconds(2))
                holder.writeToSession("ccx\r")
                return
            }
            let wasAttached = ssh.isAttachedToTmux
            ssh.isAttachedToTmux = false
            defer { ssh.isAttachedToTmux = wasAttached }
            do {
                let names = try await TmuxSessionManager.listSessions(ssh).map(\.name)
                for n in names {
                    let e = n.replacingOccurrences(of: "'", with: "'\\''")
                    _ = try await ssh.executeCommand("tmux send-keys -t '\(e)' '/exit' Enter")
                }
                try? await Task.sleep(for: .seconds(2))
                for n in names {
                    let e = n.replacingOccurrences(of: "'", with: "'\\''")
                    _ = try await ssh.executeCommand("tmux send-keys -t '\(e)' 'ccx' Enter")
                }
            } catch { self.error = "Restart CLI failed: \(error.localizedDescription)" }
        }
    }

    func refreshToken(allSessions: Bool) {
        Task {
            if !allSessions {
                holder.writeToSession("/exit\r")
                try? await Task.sleep(for: .seconds(2))
                holder.writeToSession("ca refresh && ccx\r")
                return
            }
            let wasAttached = ssh.isAttachedToTmux
            ssh.isAttachedToTmux = false
            do {
                let names = try await TmuxSessionManager.listSessions(ssh).map(\.name)
                for n in names {
                    let e = n.replacingOccurrences(of: "'", with: "'\\''")
                    _ = try await ssh.executeCommand("tmux send-keys -t '\(e)' '/exit' Enter")
                }
                try? await Task.sleep(for: .seconds(2))
                let chain = names.map { n -> String in
                    let e = n.replacingOccurrences(of: "'", with: "'\\''")
                    return "tmux send-keys -t '\(e)' 'ccx' Enter"
                }.joined(separator: " && ")
                ssh.isAttachedToTmux = wasAttached
                holder.writeToSession("ca refresh && \(chain)\r")
            } catch {
                ssh.isAttachedToTmux = wasAttached
                self.error = "Refresh token failed: \(error.localizedDescription)"
            }
        }
    }

    func killSession(_ name: String) {
        Task {
            let wasAttached = ssh.isAttachedToTmux
            ssh.isAttachedToTmux = false
            try? await TmuxSessionManager.killSession(name, ssh)
            ssh.isAttachedToTmux = wasAttached
        }
    }

    func sendRawEscape(_ seq: String) {
        holder.writeBytes(Array(seq.utf8))
    }

    /// Send terminal input, but first snap tmux back to the live bottom. When the
    /// user has scrolled up, tmux is in copy-mode and would EAT the keystrokes as
    /// copy-mode commands (so the typed text vanishes and never reaches Claude).
    /// `send-keys -X cancel` exits copy-mode over the command channel (no-op when
    /// not in copy-mode, so it's safe either way and injects nothing literal).
    func sendInput(_ text: String) {
        let name = sessionName
        Task { @MainActor in
            if !name.isEmpty {
                let esc = name.replacingOccurrences(of: "'", with: "'\\''")
                let wasAttached = ssh.isAttachedToTmux
                ssh.isAttachedToTmux = false
                _ = try? await ssh.executeCommand("tmux send-keys -t '\(esc)' -X cancel 2>/dev/null || true")
                ssh.isAttachedToTmux = wasAttached
            }
            holder.writeBytes(Array(text.utf8))
        }
    }

    func uploadAndAttachFile(_ url: URL) {
        // Upload over the command channel (independent of the tmux PTY — no detach needed).
        // The command channel is gated by isAttachedToTmux, so bypass it during the upload,
        // then paste the resulting remote path into the terminal for the CLI to read.
        Task {
            isUploading = true
            statusMessage = "Uploading file..."
            let wasAttached = ssh.isAttachedToTmux
            ssh.isAttachedToTmux = false
            do {
                let remotePath = try await FileUploadManager.upload(url: url, ssh: ssh) { [weak self] p in
                    self?.statusMessage = "Uploading file… \(Int(p * 100))%"
                }
                ssh.isAttachedToTmux = wasAttached
                isUploading = false
                statusMessage = nil
                // Type the path into the terminal (a trailing space, no newline, so the
                // user can keep typing their prompt around the attached file path).
                holder.writeBytes(Array((remotePath + " ").utf8))
            } catch {
                ssh.isAttachedToTmux = wasAttached
                isUploading = false
                statusMessage = nil
                self.error = "Upload failed: \(error.localizedDescription)"
            }
        }
    }
}

/// Runs `op` with a timeout; returns true if it finished without throwing in time.
private func withTimeoutBool(seconds: Double, _ op: @escaping () async throws -> Void) async -> Bool {
    await withTaskGroup(of: Bool.self) { group in
        group.addTask { (try? await op()) != nil }
        group.addTask {
            try? await Task.sleep(for: .seconds(seconds))
            return false
        }
        let result = await group.next() ?? false
        group.cancelAll()
        return result
    }
}

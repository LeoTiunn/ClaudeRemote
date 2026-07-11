import SwiftUI
import Combine

/// Ports Android `SessionSwitcherViewModel.kt`.
@MainActor
final class SessionSwitcherViewModel: ObservableObject {
    private let ssh = SshClient.shared
    private let settings = SettingsStore.shared

    @Published var connectionState: ConnectionState = .disconnected
    @Published var isConnecting = false
    @Published var sessions: [TmuxSession] = []
    /// Live sessions grouped by project (cwd), mirroring the webmux sidebar.
    var projects: [TmuxProject] { sessions.groupedByProject() }
    @Published var repos: [String] = []
    @Published var repoHistory: [String] = []
    @Published var isSearching = false
    @Published var isLoading = false
    @Published var error: String?
    @Published var showPasswordPrompt = false
    @Published var navigateToSession: String?

    private var cancellables = Set<AnyCancellable>()
    private var loading = false

    init() {
        repoHistory = settings.getRepoHistory()
        ssh.$connectionState
            .receive(on: RunLoop.main)
            .sink { [weak self] state in
                guard let self else { return }
                let wasDisconnected = self.connectionState != .connected
                self.connectionState = state
                self.isConnecting = false
                if state == .connected && wasDisconnected { self.loadSessions() }
            }
            .store(in: &cancellables)
        autoConnect()
    }

    func onScreenVisible() {
        repoHistory = settings.getRepoHistory()
        connectionState = ssh.connectionState
        if ssh.connectionState == .connected { loadSessions() }
    }

    private func autoConnect() {
        if ssh.connectionState == .connected { loadSessions(); return }
        if settings.hasPassword() { connect() } else { showPasswordPrompt = true }
    }

    func connect() {
        let pw = settings.getSshPassword()
        if pw.isEmpty { showPasswordPrompt = true; return }
        isConnecting = true; error = nil
        Task {
            do {
                try await ssh.connect(host: settings.sshHost,
                                      port: Int(settings.sshPort) ?? 22,
                                      username: settings.sshUsername,
                                      password: pw)
            } catch {
                self.error = "Connection failed: \(error.localizedDescription)"
                isConnecting = false
            }
        }
    }

    func connectWithPassword(_ pw: String) {
        settings.setSshPassword(pw)
        showPasswordPrompt = false
        connect()
    }

    func dismissPasswordPrompt() { showPasswordPrompt = false }

    func loadSessions() {
        if loading { return }
        loading = true
        Task {
            isLoading = true; error = nil
            let wasAttached = ssh.isAttachedToTmux
            ssh.isAttachedToTmux = false
            do {
                sessions = try await TmuxSessionManager.listSessions(ssh)
                isLoading = false
            } catch {
                self.error = "Load failed: \(error.localizedDescription)"
                isLoading = false
            }
            ssh.isAttachedToTmux = wasAttached
            loading = false
        }
    }

    func searchRepos(_ query: String) {
        if query.count < 2 { repos = []; isSearching = false; return }
        Task {
            let wasAttached = ssh.isAttachedToTmux
            ssh.isAttachedToTmux = false
            do {
                repos = try await TmuxSessionManager.searchRepos(query: query, ssh)
                isSearching = true
            } catch { self.error = "Search failed: \(error.localizedDescription)" }
            ssh.isAttachedToTmux = wasAttached
        }
    }

    func attachSession(_ name: String) {
        repos = []; isSearching = false
        ssh.currentSessionName = name
        navigateToSession = name
    }

    func onNavigated() { navigateToSession = nil }

    func createSessionFromRepo(_ repo: String) {
        let name = String(repo.split(separator: "/").last ?? Substring(repo))
        let workDir = "$HOME/Developer/\(repo)"
        settings.addRepoToHistory(repo)
        repos = []; isSearching = false; repoHistory = settings.getRepoHistory()
        Task {
            let wasAttached = ssh.isAttachedToTmux
            ssh.isAttachedToTmux = false
            do {
                try await TmuxSessionManager.createSession(name: name, workDir: workDir, ssh)
                try? await Task.sleep(for: .milliseconds(500))
                ssh.isAttachedToTmux = wasAttached
                ssh.currentSessionName = name
                navigateToSession = name
            } catch {
                ssh.isAttachedToTmux = wasAttached
                self.error = error.localizedDescription
            }
        }
    }

    func killSession(_ name: String) {
        Task {
            let wasAttached = ssh.isAttachedToTmux
            ssh.isAttachedToTmux = false
            do {
                try await TmuxSessionManager.killSession(name, ssh)
                ssh.isAttachedToTmux = wasAttached
                loadSessions()
            } catch {
                ssh.isAttachedToTmux = wasAttached
                self.error = error.localizedDescription
            }
        }
    }

    func clearError() { error = nil }
}

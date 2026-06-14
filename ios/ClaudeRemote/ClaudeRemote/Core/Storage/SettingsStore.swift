import Foundation
import Combine

/// Non-secret settings + repo history, mirroring Android `SettingsRepository.kt`.
/// Password lives in `KeychainStore`; everything else in UserDefaults.
final class SettingsStore: ObservableObject {
    static let shared = SettingsStore()
    private let d = UserDefaults.standard

    // Defaults match Android SettingsRepository exactly.
    @Published var sshHost: String { didSet { d.set(sshHost, forKey: "ssh_host") } }
    @Published var sshPort: String { didSet { d.set(sshPort, forKey: "ssh_port") } }
    @Published var sshUsername: String { didSet { d.set(sshUsername, forKey: "ssh_username") } }
    @Published var tmuxPath: String { didSet { d.set(tmuxPath, forKey: "tmux_path") } }
    @Published var claudePath: String { didSet { d.set(claudePath, forKey: "claude_path") } }
    @Published var theme: AppTheme { didSet { d.set(theme.rawValue, forKey: "theme") } }
    @Published var fontSize: Double { didSet { d.set(fontSize, forKey: "font_size") } }

    private init() {
        sshHost = d.string(forKey: "ssh_host") ?? "asune.asuscomm.com"
        // External access goes through the router's 59487 -> 192.168.50.20:22 forward.
        sshPort = d.string(forKey: "ssh_port") ?? "59487"
        sshUsername = d.string(forKey: "ssh_username") ?? "leo.chang"
        tmuxPath = d.string(forKey: "tmux_path") ?? "tmux"
        claudePath = d.string(forKey: "claude_path") ?? "claude"
        theme = AppTheme(rawValue: d.string(forKey: "theme") ?? "SYSTEM") ?? .SYSTEM
        let fs = d.double(forKey: "font_size")
        fontSize = fs == 0 ? 16 : fs
    }

    // Password (Keychain)
    func getSshPassword() -> String { KeychainStore.getPassword() }
    func setSshPassword(_ p: String) { KeychainStore.setPassword(p) }
    func clearPassword() { KeychainStore.clearPassword() }
    func hasPassword() -> Bool { KeychainStore.hasPassword() }

    // Repo history — newline-separated, keep last 20 (matches Android).
    func getRepoHistory() -> [String] {
        (d.string(forKey: "repo_history") ?? "")
            .split(separator: "\n").map(String.init).filter { !$0.isEmpty }
    }

    func addRepoToHistory(_ repo: String) {
        var hist = getRepoHistory().filter { $0 != repo }
        hist.insert(repo, at: 0)
        hist = Array(hist.prefix(20))
        d.set(hist.joined(separator: "\n"), forKey: "repo_history")
    }
}

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

    // Password — stored in UserDefaults (app sandbox) rather than the Keychain.
    // Rationale: this is a personal sideload app on a free dev cert; every re-sign
    // / cert-expiry reinstall changes the signing identity, which orphans Keychain
    // items (SecItemCopyMatching → itemNotFound) so the password "disappears" and
    // the app re-prompts. UserDefaults survives normal re-sign/overwrite installs.
    // iOS sandboxing still keeps it unreadable by other apps. On first run after
    // this change, migrate any existing Keychain password over so the user isn't
    // prompted again.
    private let passwordKey = "ssh_password"

    func getSshPassword() -> String {
        if let p = d.string(forKey: passwordKey), !p.isEmpty { return p }
        // One-time migration from the old Keychain storage.
        let legacy = KeychainStore.getPassword()
        if !legacy.isEmpty { d.set(legacy, forKey: passwordKey) }
        return legacy
    }

    func setSshPassword(_ p: String) { d.set(p, forKey: passwordKey) }

    func clearPassword() {
        d.removeObject(forKey: passwordKey)
        KeychainStore.clearPassword()
    }

    func hasPassword() -> Bool { !getSshPassword().isEmpty }

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

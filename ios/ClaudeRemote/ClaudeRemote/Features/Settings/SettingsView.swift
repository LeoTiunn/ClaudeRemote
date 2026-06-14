import SwiftUI

/// Ports Android `SettingsScreen.kt`: SSH connection, paths, appearance (theme +
/// font size), and a Developer section. The Android in-app APK self-update flow
/// does not apply on iOS (sideload) — replaced with a reinstall note.
struct SettingsView: View {
    @Environment(\.appColors) private var colors
    @ObservedObject private var settings = SettingsStore.shared
    let onBack: () -> Void

    @State private var showPasswordDialog = false
    @State private var passwordInput = ""

    var body: some View {
        Form {
            Section("SSH Connection") {
                labeledField("SSH Host", text: $settings.sshHost)
                labeledField("SSH Port", text: $settings.sshPort, keyboard: .numberPad)
                labeledField("Username", text: $settings.sshUsername)
                HStack {
                    Button("Update Password") { showPasswordDialog = true }
                        .buttonStyle(.borderedProminent)
                    Button("Clear Password") { settings.clearPassword() }
                        .foregroundStyle(colors.error)
                }
            }

            Section("Paths") {
                labeledField("tmux Path", text: $settings.tmuxPath)
                labeledField("Claude CLI Path", text: $settings.claudePath)
            }

            Section("Appearance") {
                Picker("Theme", selection: $settings.theme) {
                    ForEach(AppTheme.allCases) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.segmented)

                VStack(alignment: .leading) {
                    Text("Font Size: \(Int(settings.fontSize))sp")
                    Slider(value: $settings.fontSize, in: 12...24, step: 1)
                        .onChange(of: settings.fontSize) { _, v in
                            TerminalHolder.shared.setFontSize(CGFloat(v))
                        }
                }
            }

            Section("App Update") {
                Text("To update, rebuild and reinstall from Xcode.")
                    .font(.footnote).foregroundStyle(colors.onSurfaceVariant)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { onBack() } label: { Image(systemName: "chevron.backward") }
            }
        }
        .alert("SSH Password", isPresented: $showPasswordDialog) {
            SecureField("Password", text: $passwordInput)
            Button("Save") { settings.setSshPassword(passwordInput); passwordInput = "" }
            Button("Cancel", role: .cancel) { passwordInput = "" }
        }
    }

    private func labeledField(_ label: String, text: Binding<String>,
                              keyboard: UIKeyboardType = .default) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(colors.onSurfaceVariant)
            TextField(label, text: text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(keyboard)
        }
    }
}

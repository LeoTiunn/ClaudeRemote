import SwiftUI
import UniformTypeIdentifiers

/// Ports Android `ChatScreen.kt` (terminal mode) 1:1: top bar with status dot +
/// session-name dropdown + reconnect/attach/settings actions, status banner,
/// terminal surface, key bar, and the keyboard-tracking input row + dialogs.
struct ChatView: View {
    @Environment(\.appColors) private var colors
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = ChatViewModel()

    let sessionName: String
    let onNavigateToSessions: () -> Void
    let onNavigateToSettings: () -> Void

    @State private var showSessionMenu = false
    @State private var expandedProjects: Set<String> = []
    @State private var killConfirm: String?
    @State private var showRestartDialog = false
    @State private var showRefreshDialog = false
    @State private var termInput = ""
    @State private var showFileImporter = false
    @State private var didStart = false
    @FocusState private var inputFocused: Bool

    /// Title for the current session: prefer the Claude Code conversation name
    /// (source of truth) for the attached tmux session, fall back to tmux name.
    private var currentDisplayName: String {
        if vm.sessionName.isEmpty { return "Claude Remote" }
        if let s = vm.availableSessions.first(where: { $0.name == vm.sessionName }) {
            return s.displayName
        }
        return vm.sessionName
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            statusBanner
            TerminalSurface()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // Tap the terminal to dismiss the keyboard (alongside SwiftTerm's own taps).
                .simultaneousGesture(TapGesture().onEnded { inputFocused = false })
            Divider().overlay(colors.surfaceVariant.opacity(0.5))
            TerminalKeysBar { vm.sendRawEscape($0) }
                .background(colors.background)
            inputRow
        }
        .background(colors.background)
        // Pull the whole stack to the true bottom edge so the input row sits right at
        // the bottom (no grey strip below it). Keyboard insets are handled separately.
        .ignoresSafeArea(.container, edges: .bottom)
        .onAppear {
            if !didStart { didStart = true; vm.start(sessionName: sessionName) }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active && didStart { vm.checkConnectionAndReconnect() }
        }
        .fileImporter(isPresented: $showFileImporter,
                      allowedContentTypes: [.item], allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first {
                vm.uploadAndAttachFile(url)
            }
        }
        .confirmationDialog("Restart CLI", isPresented: $showRestartDialog, titleVisibility: .visible) {
            Button("All Sessions") { vm.restartCli(allSessions: true) }
            Button("This Session") { vm.restartCli(allSessions: false) }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Exit Claude CLI and restart with --continue.") }
        .confirmationDialog("Refresh Token", isPresented: $showRefreshDialog, titleVisibility: .visible) {
            Button("All Sessions") { vm.refreshToken(allSessions: true) }
            Button("This Session") { vm.refreshToken(allSessions: false) }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Refresh API token, then restart Claude CLI.") }
        .alert("Kill Session", isPresented: Binding(
            get: { killConfirm != nil }, set: { if !$0 { killConfirm = nil } })) {
            Button("Kill", role: .destructive) {
                if let n = killConfirm { vm.killSession(n) }
                killConfirm = nil
            }
            Button("Cancel", role: .cancel) { killConfirm = nil }
        } message: { Text("Kill tmux session \"\(killConfirm ?? "")\"?") }
    }

    // MARK: top bar

    private var topBar: some View {
        HStack(spacing: 0) {
            Button {
                vm.loadAvailableSessions()
                showSessionMenu = true
            } label: {
                HStack(spacing: 8) {
                    ConnectionStatusDot(state: vm.connectionState)
                    Text(currentDisplayName)
                        .font(.headline)
                        .foregroundStyle(colors.onBackground)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 13))
                        .foregroundStyle(colors.onSurfaceVariant)
                }
            }
            .buttonStyle(.plain)
            .popover(isPresented: $showSessionMenu) { sessionMenu }

            Spacer(minLength: 12)

            // Action icons with consistent hit targets + spacing.
            HStack(spacing: 4) {
                if vm.connectionState != .connected && !vm.sessionName.isEmpty {
                    Button { vm.reconnect() } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 17))
                            .foregroundStyle(colors.error)
                            .frame(width: 36, height: 36)
                    }.disabled(vm.isReconnecting)
                }
                if vm.connectionState == .connected {
                    Button { showFileImporter = true } label: {
                        Image(systemName: "paperclip")
                            .font(.system(size: 17))
                            .foregroundStyle(colors.onSurfaceVariant)
                            .frame(width: 36, height: 36)
                    }.disabled(vm.isUploading)
                }
                Button { onNavigateToSettings() } label: {
                    Image(systemName: "gearshape")
                        .font(.system(size: 17))
                        .foregroundStyle(colors.onSurfaceVariant)
                        .frame(width: 36, height: 36)
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 4)
        .background(colors.background)
    }

    private var sessionMenu: some View {
        Group {
            if vm.isLoadingSessions && vm.availableSessions.isEmpty {
                ProgressView().padding()
            } else {
                // Everything in one scroll view — sessions then actions. Scroll down to
                // reach New Session / Reconnect / Restart CLI / Refresh Token.
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("SESSIONS")
                            .font(.caption2).fontWeight(.semibold)
                            .foregroundStyle(colors.onSurfaceVariant.opacity(0.6))
                            .padding(.horizontal, 18).padding(.top, 14).padding(.bottom, 6)
                        ForEach(vm.availableSessions.groupedByProject()) { project in
                            let isCollapsed = !expandedProjects.contains(project.cwd)
                            Button {
                                if isCollapsed { expandedProjects.insert(project.cwd) }
                                else { expandedProjects.remove(project.cwd) }
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: isCollapsed ? "chevron.right" : "chevron.down")
                                        .font(.system(size: 10, weight: .semibold))
                                        .foregroundStyle(colors.onSurfaceVariant).frame(width: 11)
                                    Image(systemName: "folder.fill").font(.system(size: 12))
                                        .foregroundStyle(colors.primary.opacity(0.8))
                                    Text(project.name).font(.footnote).fontWeight(.semibold)
                                        .foregroundStyle(colors.onSurface).lineLimit(1)
                                    Spacer(minLength: 8)
                                    Text("\(project.sessions.count)").font(.caption2)
                                        .foregroundStyle(colors.onSurfaceVariant)
                                }.padding(.horizontal, 16).padding(.vertical, 9)
                            }.buttonStyle(.plain)
                            if !isCollapsed {
                                ForEach(project.sessions) { s in
                                    let isCurrent = s.name == vm.sessionName
                                    Button {
                                        showSessionMenu = false
                                        vm.switchSession(s.name)
                                    } label: {
                                        HStack(spacing: 12) {
                                            Circle()
                                                .fill(s.status == "busy" ? colors.primary
                                                      : (isCurrent ? colors.primary : colors.outline.opacity(0.5)))
                                                .frame(width: 7, height: 7)
                                            Text(s.displayName)
                                                .foregroundStyle(isCurrent ? colors.primary : colors.onSurface)
                                                .lineLimit(1)
                                            Spacer(minLength: 8)
                                            if isCurrent { Image(systemName: "checkmark").font(.system(size: 13)).foregroundStyle(colors.primary) }
                                        }.padding(.leading, 34).padding(.trailing, 18).padding(.vertical, 12)
                                    }
                                    .buttonStyle(.plain)
                                    .simultaneousGesture(LongPressGesture().onEnded { _ in
                                        if !isCurrent { showSessionMenu = false; killConfirm = s.name }
                                    })
                                }
                            }
                        }
                        Divider().padding(.vertical, 6)
                        menuItem("+ New Session", color: colors.primary) {
                            showSessionMenu = false; onNavigateToSessions()
                        }
                        menuItem("Reconnect", color: colors.onSurfaceVariant) {
                            showSessionMenu = false; vm.reconnect()
                        }
                        menuItem("Restart CLI", color: colors.onSurfaceVariant) {
                            showSessionMenu = false; showRestartDialog = true
                        }
                        menuItem("Refresh Token", color: colors.onSurfaceVariant) {
                            showSessionMenu = false; showRefreshDialog = true
                        }
                        Spacer().frame(height: 8)
                    }
                }
            }
        }
        .frame(minWidth: 280)
        .presentationCompactAdaptation(.popover)
    }

    private func menuItem(_ title: String, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).foregroundStyle(color)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 18).padding(.vertical, 13)
        }.buttonStyle(.plain)
    }

    // MARK: status banner

    @ViewBuilder private var statusBanner: some View {
        if let status = vm.statusMessage {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(status).font(.footnote).foregroundStyle(colors.onSecondaryContainer)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 6)
            .background(colors.secondaryContainer)
        } else if vm.isUploading {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Uploading file...").font(.footnote).foregroundStyle(colors.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 4)
            .background(colors.surfaceVariant)
        }
    }

    // MARK: input row

    private var inputRow: some View {
        HStack(spacing: 4) {
            TextField("Type here...", text: $termInput)
                .font(.custom(AppFont.mono, size: 15))
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(colors.background)
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(colors.outline.opacity(0.5)))
                .focused($inputFocused)
                .submitLabel(.send)
                .onSubmit(send)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if inputFocused {
                Button { inputFocused = false } label: {
                    Image(systemName: "keyboard.chevron.compact.down")
                        .font(.system(size: 18))
                        .foregroundStyle(colors.onSurfaceVariant)
                        .frame(width: 40, height: 44)
                }
            }
            Button(action: send) {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(colors.primary)
                    .frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 6)
        // Clear the home-indicator gesture area so the field doesn't need a double-tap.
        .padding(.bottom, inputFocused ? 6 : 24)
        .background(colors.surfaceVariant)
    }

    private func send() {
        // sendInput first cancels tmux copy-mode (snaps to the live bottom) so the
        // text isn't swallowed when the user has scrolled up.
        if !termInput.isEmpty {
            vm.sendInput(termInput + "\r")
            termInput = ""
        } else {
            vm.sendInput("\r")
        }
        inputFocused = false // dismiss keyboard after sending
    }
}

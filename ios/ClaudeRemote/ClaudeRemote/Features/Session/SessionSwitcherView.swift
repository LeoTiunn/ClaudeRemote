import SwiftUI

/// Ports Android `SessionSwitcherScreen.kt`: status dot + title, connection banner,
/// repo search, active-sessions list, recent/search results, password prompt.
struct SessionSwitcherView: View {
    @Environment(\.appColors) private var colors
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = SessionSwitcherViewModel()

    let onSessionSelected: (String) -> Void
    let onNavigateToSettings: () -> Void

    @State private var searchQuery = ""
    @State private var passwordInput = ""

    var body: some View {
        VStack(spacing: 0) {
            topBar
            banner
            if vm.connectionState == .connected || !vm.sessions.isEmpty || !vm.repos.isEmpty {
                searchField
                listContent
            }
            Spacer(minLength: 0)
        }
        .background(colors.background)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { vm.onScreenVisible() }
        }
        .onChange(of: vm.navigateToSession) { _, name in
            if let name { vm.onNavigated(); onSessionSelected(name) }
        }
        .alert("SSH Password", isPresented: $vm.showPasswordPrompt) {
            SecureField("Password", text: $passwordInput)
            Button("Connect") { vm.connectWithPassword(passwordInput); passwordInput = "" }
            Button("Cancel", role: .cancel) { vm.dismissPasswordPrompt() }
        } message: { Text("Enter password to connect") }
    }

    // Matches Android TopAppBar: [status dot] Claude Remote ... [gear]. No system
    // toolbar (iOS 26 would wrap items in circular glass backgrounds) — a plain row.
    private var topBar: some View {
        HStack(spacing: 8) {
            ConnectionStatusDot(state: vm.connectionState)
            Text("Claude Remote").font(.title3).fontWeight(.semibold)
                .foregroundStyle(colors.onBackground)
            Spacer()
            Button { onNavigateToSettings() } label: {
                Image(systemName: "gearshape").foregroundStyle(colors.onSurfaceVariant)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(colors.background)
    }

    @ViewBuilder private var banner: some View {
        if vm.connectionState == .disconnected && !vm.isConnecting {
            HStack {
                Text(vm.error ?? "Not connected")
                    .font(.subheadline).foregroundStyle(colors.onErrorContainer)
                Spacer()
                Button("Connect") { vm.connect() }.buttonStyle(.borderedProminent)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(colors.errorContainer)
        } else if vm.isConnecting {
            HStack(spacing: 12) {
                ProgressView().controlSize(.small)
                Text("Connecting...").font(.subheadline)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(colors.secondaryContainer)
        } else if let err = vm.error, vm.connectionState == .connected {
            HStack {
                Text(err).font(.footnote).foregroundStyle(colors.onErrorContainer)
                Spacer()
                Button("Dismiss") { vm.clearError() }
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .background(colors.errorContainer)
        }
    }

    private var searchField: some View {
        HStack {
            TextField("Search repos to create session...", text: $searchQuery)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit { vm.searchRepos(searchQuery) }
            Button { vm.searchRepos(searchQuery) } label: { Image(systemName: "magnifyingglass") }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(colors.outline.opacity(0.5)))
        .padding(.horizontal, 16).padding(.vertical, 8)
    }

    private var listContent: some View {
        List {
            if !vm.isSearching && !vm.sessions.isEmpty {
                Section {
                    ForEach(vm.sessions) { session in
                        sessionCard(session)
                            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                            .listRowBackground(Color.clear)
                    }
                } header: {
                    Text("Active Sessions").foregroundStyle(colors.primary)
                }
            }
            if vm.isSearching && !vm.repos.isEmpty {
                Section {
                    ForEach(vm.repos, id: \.self) { repo in
                        repoCard(repo) { searchQuery = ""; vm.createSessionFromRepo(repo) }
                            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                            .listRowBackground(Color.clear)
                    }
                } header: { Text("Search Results").foregroundStyle(colors.primary) }
            } else if !vm.repoHistory.isEmpty {
                Section {
                    ForEach(vm.repoHistory, id: \.self) { repo in
                        repoCard(repo) { vm.createSessionFromRepo(repo) }
                            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                            .listRowBackground(Color.clear)
                    }
                } header: { Text("Recent").foregroundStyle(colors.primary) }
            }
            if vm.isLoading {
                HStack { Spacer(); ProgressView().controlSize(.small); Spacer() }
                    .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func sessionCard(_ session: TmuxSession) -> some View {
        Button { vm.attachSession(session.name) } label: {
            HStack {
                Image(systemName: "terminal").foregroundStyle(colors.onPrimaryContainer)
                Spacer().frame(width: 12)
                VStack(alignment: .leading) {
                    Text(session.name).font(.headline).foregroundStyle(colors.onPrimaryContainer)
                    Text(session.windowName).font(.caption)
                        .foregroundStyle(colors.onPrimaryContainer.opacity(0.7))
                }
                Spacer()
                Button { vm.killSession(session.name) } label: {
                    Image(systemName: "trash").foregroundStyle(colors.error)
                }.buttonStyle(.plain)
            }
            .padding(16)
            .background(colors.primaryContainer)
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain)
    }

    private func repoCard(_ repo: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Image(systemName: "folder").foregroundStyle(colors.onSurfaceVariant)
                Spacer().frame(width: 12)
                VStack(alignment: .leading) {
                    Text(String(repo.split(separator: "/").last ?? Substring(repo))).font(.headline)
                    Text("~/Developer/\(repo)").font(.caption).foregroundStyle(colors.onSurfaceVariant)
                }
                Spacer()
            }
            .padding(16)
            .background(colors.surfaceVariant.opacity(0.4))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain)
    }
}

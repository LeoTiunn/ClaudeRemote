import SwiftUI

@main
struct ClaudeRemoteApp: App {
    @ObservedObject private var settings = SettingsStore.shared

    init() {
        _ = AppFont.monoUI(13) // register the bundled Sarasa font once at launch
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(settings.theme.colorScheme)
        }
    }
}

/// Which top-level screen is showing. We drive Chat at the root level (not via
/// NavigationStack `navigationDestination`) because SwiftTerm's UIViewRepresentable
/// fails to render when pushed as a navigation destination — but renders correctly as
/// a root view. Settings is presented as a sheet over whichever screen is active.
private enum Screen: Equatable {
    case sessions
    case chat(String)
}

struct RootView: View {
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.horizontalSizeClass) private var sizeClass
    @ObservedObject private var settings = SettingsStore.shared
    @State private var screen: Screen = .sessions
    @State private var showSettings = false

    // iPad sidebar state.
    @State private var sidebarVisible = true
    /// Session the terminal pane is showing. nil until the first tap.
    @State private var openSession: String?
    /// Fed to ChatView so it switches in place instead of being rebuilt.
    @State private var requestedSession: String?

    private var colors: AppColors {
        let scheme = settings.theme.colorScheme ?? systemScheme
        return AppColors.resolve(scheme)
    }

    var body: some View {
        Group {
            if RootLayout.forSizeClass(sizeClass) == .split {
                splitBody
            } else {
                stackedBody
            }
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                SettingsView(onBack: { showSettings = false })
            }
            .environment(\.appColors, colors)
            .tint(colors.primary)
            .preferredColorScheme(settings.theme.colorScheme)
        }
        .environment(\.appColors, colors)
        .tint(colors.primary)
    }

    /// iPhone: one screen at a time, exactly as before.
    @ViewBuilder private var stackedBody: some View {
        switch screen {
        case .sessions:
            SessionSwitcherView(
                onSessionSelected: { screen = .chat($0) },
                onNavigateToSettings: { showSettings = true }
            )
        case .chat(let name):
            ChatView(
                sessionName: name,
                onNavigateToSessions: { screen = .sessions },
                onNavigateToSettings: { showSettings = true }
            )
        }
    }

    /// iPad: webmux-style sidebar of sessions beside the terminal. Built from an
    /// HStack rather than NavigationSplitView because SwiftTerm does not render
    /// inside a navigation container (see the note on `Screen`).
    private var splitBody: some View {
        HStack(spacing: 0) {
            if sidebarVisible {
                SessionSwitcherView(
                    onSessionSelected: { tapped in
                        switch SidebarAction.forTap(on: tapped, current: openSession) {
                        case .open(let n): openSession = n
                        case .switchTo(let n): requestedSession = n; openSession = n
                        case .none: break
                        }
                    },
                    onNavigateToSettings: { showSettings = true },
                    selectedSession: openSession
                )
                .frame(width: 280)
                .transition(.move(edge: .leading))
                Divider().overlay(colors.surfaceVariant.opacity(0.5))
            }
            Group {
                if let current = openSession {
                    ChatView(
                        sessionName: current,
                        onNavigateToSessions: { withAnimation { sidebarVisible = true } },
                        onNavigateToSettings: { showSettings = true },
                        requestedSession: requestedSession,
                        onToggleSidebar: {
                            withAnimation(.easeInOut(duration: 0.2)) { sidebarVisible.toggle() }
                        },
                        onSessionChanged: { openSession = $0 }
                    )
                } else {
                    VStack(spacing: 10) {
                        Image(systemName: "sidebar.left").font(.system(size: 34))
                        Text("Pick a session from the sidebar")
                    }
                    .foregroundStyle(colors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(colors.background)
                }
            }
            .frame(maxWidth: .infinity)
        }
        .background(colors.background)
    }
}

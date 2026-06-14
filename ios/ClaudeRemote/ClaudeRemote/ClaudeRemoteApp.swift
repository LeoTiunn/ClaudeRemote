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
    @ObservedObject private var settings = SettingsStore.shared
    @State private var screen: Screen = .sessions
    @State private var showSettings = false

    private var colors: AppColors {
        let scheme = settings.theme.colorScheme ?? systemScheme
        return AppColors.resolve(scheme)
    }

    var body: some View {
        Group {
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
}

import SwiftUI

/// Top-level arrangement. iPad (regular width) gets the webmux-style sidebar of
/// sessions beside the terminal; iPhone keeps the one-screen-at-a-time flow.
enum RootLayout: Equatable {
    case stacked
    case split

    static func forSizeClass(_ sizeClass: UserInterfaceSizeClass?) -> RootLayout {
        sizeClass == .regular ? .split : .stacked
    }
}

/// What a tap on a sidebar session should do to the terminal pane.
enum SidebarAction: Equatable {
    /// Nothing attached yet — bring up the terminal on this session.
    case open(String)
    /// A terminal is showing another session — retarget it in place.
    case switchTo(String)
    /// Already showing it. Re-attaching would rebuild the PTY for no gain.
    case none

    static func forTap(on tapped: String, current: String?) -> SidebarAction {
        guard let current else { return .open(tapped) }
        return tapped == current ? .none : .switchTo(tapped)
    }
}

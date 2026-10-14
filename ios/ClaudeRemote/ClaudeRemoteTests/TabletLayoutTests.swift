import SwiftUI
import Testing
@testable import ClaudeRemote

struct RootLayoutTests {

    @Test func regularWidthSplitsIntoSidebarAndTerminal() {
        #expect(RootLayout.forSizeClass(.regular) == .split)
    }

    @Test func compactWidthKeepsThePhoneLayout() {
        #expect(RootLayout.forSizeClass(.compact) == .stacked)
    }

    // Size class is nil for a moment during scene setup. Falling back to the phone
    // layout is the safe default: it is what every device rendered before this change.
    @Test func unknownWidthKeepsThePhoneLayout() {
        #expect(RootLayout.forSizeClass(nil) == .stacked)
    }
}

struct SidebarActionTests {

    @Test func firstTapOpensTheTerminal() {
        #expect(SidebarAction.forTap(on: "webmux", current: nil) == .open("webmux"))
    }

    @Test func tappingAnotherSessionSwitchesInPlace() {
        #expect(SidebarAction.forTap(on: "webmux", current: "ClaudeRemote") == .switchTo("webmux"))
    }

    // Re-attaching the session already on screen tears down and reopens the PTY,
    // which flashes the terminal and drops anything typed in between.
    @Test func tappingTheCurrentSessionDoesNothing() {
        #expect(SidebarAction.forTap(on: "webmux", current: "webmux") == .none)
    }
}

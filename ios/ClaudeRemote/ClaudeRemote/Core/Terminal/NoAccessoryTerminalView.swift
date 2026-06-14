import UIKit
import SwiftTerm

/// SwiftTerm's TerminalView shows its own keyboard + accessory bar (esc/ctrl/~/F1…)
/// whenever it becomes first responder. We drive all input through our own TextField +
/// TerminalKeysBar, so the terminal never needs to be first responder. Blocking that at
/// the root prevents SwiftTerm's keyboard/accessory from ever appearing.
final class NoAccessoryTerminalView: TerminalView {
    override var canBecomeFirstResponder: Bool { false }
    override func becomeFirstResponder() -> Bool { false }
}

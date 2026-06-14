import UIKit
import SwiftTerm

/// Terminal view tailored for our app:
/// 1. Suppresses SwiftTerm's built-in keyboard + accessory bar (we use our own
///    TextField + TerminalKeysBar) by refusing first-responder.
/// 2. Stays inert to swipes: `allowMouseReporting=false` + a no-op `mouseModeChanged`
///    so tmux's DECSET 1000/1002/1003 never installs SwiftTerm's mouse pan gesture
///    (that gesture was sending motion/cursor bytes to the remote — the scroll garbage).
/// 3. Adds our own vertical pan that drives tmux copy-mode for scrollback history —
///    SwiftTerm has no local scrollback in tmux's alternate buffer.
class NoAccessoryTerminalView: TerminalView, UIGestureRecognizerDelegate {
    /// Positive = scroll up (into history), negative = scroll down, in line units.
    var onScrollLines: ((Int) -> Void)?

    override var canBecomeFirstResponder: Bool { false }
    override func becomeFirstResponder() -> Bool { false }

    open override func mouseModeChanged(source: Terminal) { /* never honor remote mouse mode */ }

    private var accumulatedY: CGFloat = 0

    func installScrollGesture() {
        allowMouseReporting = false
        isScrollEnabled = false // stop the UIScrollView's own pan from eating our gesture
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handleScrollPan(_:)))
        pan.maximumNumberOfTouches = 1
        pan.delegate = self
        addGestureRecognizer(pan)
    }

    // Recognize alongside any SwiftTerm gestures so we always get the swipe.
    func gestureRecognizer(_ g: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    @objc private func handleScrollPan(_ g: UIPanGestureRecognizer) {
        let lineHeight: CGFloat = 22
        switch g.state {
        case .began:
            accumulatedY = 0
        case .changed:
            accumulatedY += g.translation(in: self).y
            g.setTranslation(.zero, in: self)
            let lines = Int(accumulatedY / lineHeight)
            if lines != 0 {
                accumulatedY -= CGFloat(lines) * lineHeight
                onScrollLines?(lines)
            }
        default:
            break
        }
    }
}

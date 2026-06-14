import SwiftUI
import UIKit
import Combine
import SwiftTerm

/// Owns the SwiftTerm TerminalView + the live PTY handle (singleton), mirroring
/// Android `NativeTerminalHolder`. SwiftTerm itself parses VT100 and computes
/// cols/rows from the view size; we feed SSH bytes in and forward keystrokes/resize out.
@MainActor
final class TerminalHolder: ObservableObject {
    static let shared = TerminalHolder()

    let coordinator = Coordinator()
    private var _terminalView: TerminalView?
    private var handle: ShellChannelHandle?
    private var pumpTask: Task<Void, Never>?

    /// Bumped on every session (re)create so SwiftUI can recompose.
    @Published private(set) var generation = 0

    var attachedSessionName = ""
    var fontSize: CGFloat = CGFloat(SettingsStore.shared.fontSize) {
        didSet { applyFont() }
    }

    private init() {}

    /// Lazily create the SwiftTerm view on first use (kept off app launch).
    var terminalView: TerminalView {
        if let v = _terminalView { return v }
        // NoAccessoryTerminalView permanently suppresses SwiftTerm's built-in keyboard
        // accessory bar (we have our own TerminalKeysBar).
        let tv = NoAccessoryTerminalView(frame: CGRect(x: 0, y: 0, width: 390, height: 700))
        tv.terminalDelegate = coordinator
        tv.nativeBackgroundColor = UIColor(red: 0x1C/255, green: 0x19/255, blue: 0x17/255, alpha: 1)
        tv.nativeForegroundColor = UIColor(red: 0xEA/255, green: 0xE1/255, blue: 0xD9/255, alpha: 1)
        tv.font = AppFont.monoUI(fontSize)
        _terminalView = tv
        return tv
    }

    private func applyFont() {
        _terminalView?.font = AppFont.monoUI(fontSize)
    }

    func setFontSize(_ size: CGFloat) { fontSize = size }

    /// Current terminal grid size as SwiftTerm sees it.
    private var currentSize: (cols: Int, rows: Int) {
        let t = terminalView.getTerminal()
        return (max(4, t.cols), max(4, t.rows))
    }

    /// Open a fresh PTY at the terminal's real grid size and bridge it in.
    func createSession(_ ssh: SshClient) async throws {
        destroySession()
        let tv = terminalView
        let size = currentSize
        let h = try await ssh.openShell(cols: size.cols, rows: size.rows)
        handle = h
        coordinator.handle = h

        // Pump SSH output into SwiftTerm on the main actor.
        pumpTask = Task { @MainActor in
            for await chunk in h.output {
                tv.feed(byteArray: chunk[...])
            }
        }
        generation += 1
    }

    func writeToSession(_ text: String) {
        guard let h = handle else { return }
        h.write(ArraySlice(Array(text.utf8)))
    }

    func writeBytes(_ data: [UInt8]) {
        handle?.write(data[...])
    }

    func clearScreen() {
        // ESC c — full reset; SwiftTerm redraws clean for session switches.
        writeBytes([0x1b, 0x63])
    }

    var isSessionRunning: Bool { handle != nil }

    func destroySession() {
        pumpTask?.cancel(); pumpTask = nil
        handle?.close()
        handle = nil
        coordinator.handle = nil
        attachedSessionName = ""
        generation += 1
    }

    /// SwiftTerm delegate — forwards user input and resize to the live PTY.
    final class Coordinator: NSObject, TerminalViewDelegate {
        var handle: ShellChannelHandle?

        func send(source: TerminalView, data: ArraySlice<UInt8>) {
            handle?.write(data)
        }
        func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
            handle?.resize(newCols, newRows)
        }
        func scrolled(source: TerminalView, position: Double) {}
        func setTerminalTitle(source: TerminalView, title: String) {}
        func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
        func clipboardCopy(source: TerminalView, content: Data) {
            if let s = String(data: content, encoding: .utf8) { UIPasteboard.general.string = s }
        }
        func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
        func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {}
        func bell(source: TerminalView) {}
        func iTermContent(source: TerminalView, content: ArraySlice<UInt8>) {}
    }
}

/// SwiftUI wrapper that drops the shared TerminalView into the layout.
struct TerminalSurface: UIViewRepresentable {
    @ObservedObject var holder: TerminalHolder = .shared

    func makeUIView(context: Context) -> TerminalView {
        holder.terminalView
    }
    func updateUIView(_ uiView: TerminalView, context: Context) {}
}

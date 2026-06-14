import SwiftUI
import SwiftTerm

/// Wraps SwiftTerm's UIKit TerminalView for SwiftUI and bridges it to a ShellChannelHandle.
/// - incoming bytes: handle.output -> terminalView.feed(byteArray:) on the main actor
/// - user input: TerminalViewDelegate.send -> handle.write
/// - resize: TerminalViewDelegate.sizeChanged -> handle.resize
struct TerminalContainerView: UIViewRepresentable {
    let handle: ShellChannelHandle

    func makeCoordinator() -> Coordinator { Coordinator(handle: handle) }

    func makeUIView(context: Context) -> TerminalView {
        let tv = TerminalView(frame: .zero)
        tv.terminalDelegate = context.coordinator
        if let font = UIFont(name: "SarasaMonoSC-Regular", size: 13) {
            tv.font = font
        }
        context.coordinator.attach(terminalView: tv)
        return tv
    }

    func updateUIView(_ uiView: TerminalView, context: Context) {}

    static func dismantleUIView(_ uiView: TerminalView, coordinator: Coordinator) {
        coordinator.detach()
    }

    final class Coordinator: NSObject, TerminalViewDelegate {
        private let handle: ShellChannelHandle
        private weak var terminalView: TerminalView?
        private var pumpTask: Task<Void, Never>?

        init(handle: ShellChannelHandle) { self.handle = handle }

        func attach(terminalView: TerminalView) {
            self.terminalView = terminalView
            // Pump SSH output into the terminal on the main actor.
            pumpTask = Task { @MainActor [weak self] in
                guard let self else { return }
                for await chunk in self.handle.output {
                    self.terminalView?.feed(byteArray: chunk[...])
                }
            }
        }

        func detach() {
            pumpTask?.cancel()
            pumpTask = nil
            handle.close()
        }

        // MARK: TerminalViewDelegate

        func send(source: TerminalView, data: ArraySlice<UInt8>) {
            handle.write(data)
        }

        func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
            handle.resize(newCols, newRows)
        }

        func scrolled(source: TerminalView, position: Double) {}
        func setTerminalTitle(source: TerminalView, title: String) {}
        func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
        func clipboardCopy(source: TerminalView, content: Data) {}
        func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
        func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {}
        func bell(source: TerminalView) {}
        func iTermContent(source: TerminalView, content: ArraySlice<UInt8>) {}
    }
}

import Foundation
import Combine
import Citadel
import NIOCore
import NIOSSH

/// SSH client backed by patched Citadel. Ports Android `SshClientImpl.kt`:
/// - one connection, multiplexed channels
/// - a command channel (`executeCommand`) used while a separate PTY drives the terminal
/// - `isAttachedToTmux` gate: when true, executeCommand is skipped (matches Android)
/// - connection-state publisher + auto-reconnect with backoff (5 attempts, attempt*3s)
/// - generation counter to invalidate stale reconnects
@MainActor
final class SshClient: ObservableObject {
    static let shared = SshClient()

    @Published private(set) var connectionState: ConnectionState = .disconnected

    private(set) var host = ""
    private(set) var port = 22
    private(set) var username = ""
    private(set) var password = ""

    /// When attached to tmux, the terminal owns the PTY; executeCommand is suppressed (Android parity).
    var isAttachedToTmux = false
    var currentSessionName = ""

    private var client: SSHClient?
    private var connectGeneration = 0
    private var connecting = false

    private init() {}

    // MARK: connect / reconnect / disconnect

    func connect(host: String, port: Int, username: String, password: String) async throws {
        if connectionState == .connected, client != nil {
            return // already connected
        }
        if connecting { return }
        connecting = true
        defer { connecting = false }

        self.host = host; self.port = port
        self.username = username; self.password = password

        connectGeneration += 1
        // tear down any prior connection
        if let old = client { try? await old.close() }
        client = nil
        isAttachedToTmux = false

        connectionState = .connecting
        do {
            // Server advertises `password`; offer it via a custom delegate that doesn't
            // guard on availableMethods (robust if advertisement timing varies). See PasswordAuthDelegate.
            let c = try await SSHClient.connect(
                host: host,
                port: port,
                authenticationMethod: .custom(PasswordAuthDelegate(username: username, password: password)),
                hostKeyValidator: .acceptAnything(),
                reconnect: .never
            )
            client = c
            connectionState = .connected
        } catch {
            connectionState = .disconnected
            throw error
        }
    }

    func reconnect() async throws {
        guard !host.isEmpty, !password.isEmpty else {
            throw SshError.noCredentials
        }
        try await connect(host: host, port: port, username: username, password: password)
    }

    func disconnect() async {
        connectionState = .disconnected
        if let c = client { try? await c.close() }
        client = nil
    }

    /// Auto-reconnect with backoff, guarded by generation (Android attemptReconnect).
    private func attemptReconnect() {
        guard !host.isEmpty else { return }
        let gen = connectGeneration
        Task {
            for attempt in 1...5 {
                if gen != connectGeneration { return } // superseded
                try? await Task.sleep(for: .seconds(Double(attempt) * 3))
                do {
                    try await connect(host: host, port: port, username: username, password: password)
                    return
                } catch { /* keep trying */ }
            }
        }
    }

    // MARK: command channel

    /// Run a command on its own exec channel. Skipped while attached to tmux (Android parity).
    func executeCommand(_ command: String) async throws -> String {
        if isAttachedToTmux { return "" }
        guard let c = client else { throw SshError.notConnected }
        let buf = try await c.executeCommand(command, mergeStreams: true)
        var b = buf
        let bytes = b.readBytes(length: b.readableBytes) ?? []
        return String(decoding: bytes, as: UTF8.self)
    }

    // MARK: terminal channel

    /// Open an interactive PTY and bridge it to a ShellChannelHandle.
    /// `withPTY` is closure-scoped: we hold it open in a long-lived Task and pump
    /// inbound bytes into an AsyncStream while capturing the writer for input/resize.
    func openShell(cols: Int, rows: Int) async throws -> ShellChannelHandle {
        guard let c = client else { throw SshError.notConnected }

        let request = SSHChannelRequestEvent.PseudoTerminalRequest(
            wantReply: true,
            term: "xterm-256color",
            terminalCharacterWidth: cols,
            terminalRowHeight: rows,
            terminalPixelWidth: 0,
            terminalPixelHeight: 0,
            terminalModes: SSHTerminalModes([.ECHO: 0, .ICRNL: 1])
        )

        let (stream, continuation) = AsyncStream<[UInt8]>.makeStream()
        let writerBox = WriterBox()
        // Marks an intentional teardown (session switch / reconnect) so the PTY task's
        // completion does NOT flip us to .disconnected and kick attemptReconnect — that
        // race was freezing input after a session switch.
        let intentionalClose = IntentionalClose()

        let task = Task.detached {
            do {
                try await c.withPTY(request) { inbound, outbound in
                    await writerBox.set(outbound)
                    for try await chunk in inbound {
                        switch chunk {
                        case .stdout(let bb), .stderr(let bb):
                            var b = bb
                            if let bytes = b.readBytes(length: b.readableBytes) {
                                continuation.yield(bytes)
                            }
                        }
                    }
                }
            } catch {
                // EOF/closed — terminal stream ends; resume handling lives in the VM.
            }
            continuation.finish()
            if await intentionalClose.value { return } // closed on purpose — don't reconnect
            await MainActor.run { [weak self] in
                guard let self else { return }
                if self.connectionState == .connected {
                    self.connectionState = .disconnected
                    self.attemptReconnect()
                }
            }
        }

        // Wait briefly for the writer to be captured so write/resize aren't no-ops.
        for _ in 0..<100 {
            if await writerBox.isReady { break }
            try? await Task.sleep(for: .milliseconds(10))
        }

        return ShellChannelHandle(
            output: stream,
            write: { slice in
                let buf = ByteBuffer(bytes: slice)
                Task { await writerBox.write(buf) }
            },
            resize: { c2, r2 in
                Task { await writerBox.resize(cols: c2, rows: r2) }
            },
            close: {
                Task { await intentionalClose.markClosed() }
                task.cancel()
                Task { await writerBox.clear() }
            }
        )
    }

    enum SshError: Error { case notConnected, noCredentials }
}

/// Flags an intentional PTY teardown so the task's completion skips auto-reconnect.
private actor IntentionalClose {
    private(set) var value = false
    func markClosed() { value = true }
}

/// Serializes access to the closure-scoped TTYStdinWriter across actors.
private actor WriterBox {
    private var writer: TTYStdinWriter?
    var isReady: Bool { writer != nil }
    func set(_ w: TTYStdinWriter) { writer = w }
    func clear() { writer = nil }
    func write(_ buf: ByteBuffer) async {
        try? await writer?.write(buf)
    }
    func resize(cols: Int, rows: Int) async {
        try? await writer?.changeSize(cols: cols, rows: rows, pixelWidth: 0, pixelHeight: 0)
    }
}

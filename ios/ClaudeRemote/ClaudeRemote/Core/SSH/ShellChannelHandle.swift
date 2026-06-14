import Foundation

/// A live PTY shell channel: bytes flow out via `output`, user input goes in via `write`,
/// and the remote PTY is resized via `resize`. Mirrors the Android ShellChannelHandle.
struct ShellChannelHandle {
    /// SSH stdout/stderr -> terminal. Consume on the main actor and feed SwiftTerm.
    let output: AsyncStream<[UInt8]>
    /// Terminal input (keystrokes) -> SSH stdin.
    let write: @Sendable (ArraySlice<UInt8>) -> Void
    /// SwiftTerm size change -> remote PTY WindowChangeRequest.
    let resize: @Sendable (_ cols: Int, _ rows: Int) -> Void
    /// Tear down the channel and stop the I/O task.
    let close: @Sendable () -> Void
}

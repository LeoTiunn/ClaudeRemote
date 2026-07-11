import Foundation

/// Uploads a local file to the remote `~/Downloads/attachments/` via base64 over the SSH
/// command (exec) channel — which is independent of the terminal's tmux PTY, so there's
/// NO need to detach/reattach tmux. Returns the absolute remote path to paste to the CLI.
enum FileUploadManager {
    enum UploadError: Error, LocalizedError {
        case readFailed
        case verifyFailed(expected: Int, got: String)
        var errorDescription: String? {
            switch self {
            case .readFailed: return "Could not read the selected file"
            case .verifyFailed(let e, let g): return "Upload verify failed (expected \(e) bytes, got \(g))"
            }
        }
    }

    static func upload(url: URL, ssh: SshClient, progress: (@MainActor (Double) -> Void)? = nil) async throws -> String {
        let needsStop = url.startAccessingSecurityScopedResource()
        defer { if needsStop { url.stopAccessingSecurityScopedResource() } }

        guard let data = try? Data(contentsOf: url) else { throw UploadError.readFailed }
        let safeName = url.lastPathComponent
            .replacingOccurrences(of: "'", with: "_")
            .replacingOccurrences(of: "/", with: "_")
        let remoteDir = "$HOME/Downloads/attachments"
        let remotePath = "\(remoteDir)/\(safeName)"

        // Prepare dir + empty target (command channel; independent of the tmux PTY).
        _ = try await ssh.executeCommand("mkdir -p \(remoteDir) && : > '\(remotePath)'")

        // Stream base64 in chunks; each chunk decoded and appended server-side. printf '%s'
        // keeps it 8-bit clean; base64 is pure ASCII so it's safe inside single quotes.
        let b64 = data.base64EncodedString()
        let chunkSize = 16384
        var idx = b64.startIndex
        var done = 0
        let total = b64.count
        while idx < b64.endIndex {
            let end = b64.index(idx, offsetBy: chunkSize, limitedBy: b64.endIndex) ?? b64.endIndex
            let chunk = String(b64[idx..<end])
            _ = try await ssh.executeCommand("printf '%s' '\(chunk)' | base64 --decode >> '\(remotePath)'")
            idx = end
            done += chunk.count
            if let progress { let p = Double(done) / Double(max(total, 1)); await MainActor.run { progress(p) } }
        }

        // Verify the byte count matches what we sent.
        let sizeOut = try await ssh.executeCommand("wc -c < '\(remotePath)' | tr -d ' \\n'")
        let reported = sizeOut.trimmingCharacters(in: .whitespacesAndNewlines)
        if Int(reported) != data.count {
            throw UploadError.verifyFailed(expected: data.count, got: reported.isEmpty ? "?" : reported)
        }

        // Resolve $HOME to an absolute path for pasting into the CLI.
        let resolved = try await ssh.executeCommand("printf '%s' \"\(remotePath)\"")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return resolved.isEmpty ? remotePath : resolved
    }
}

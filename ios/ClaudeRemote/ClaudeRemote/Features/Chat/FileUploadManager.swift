import Foundation

/// Uploads a local file to the remote `~/Downloads/attachments/` via base64 chunks
/// over the SSH command channel, mirroring Android `FileUploadManager`.
/// Returns the remote path to paste into the terminal.
enum FileUploadManager {
    static func upload(url: URL, ssh: SshClient) async throws -> String {
        let needsStop = url.startAccessingSecurityScopedResource()
        defer { if needsStop { url.stopAccessingSecurityScopedResource() } }

        let data = try Data(contentsOf: url)
        let name = url.lastPathComponent
        let safeName = name.replacingOccurrences(of: "'", with: "_")
        let remoteDir = "~/Downloads/attachments"
        let remotePath = "\(remoteDir)/\(safeName)"

        _ = try await ssh.executeCommand("mkdir -p \(remoteDir)")
        // truncate target
        _ = try await ssh.executeCommand(": > '\(remotePath)'")

        // base64 the whole file, append in chunks decoded server-side
        let b64 = data.base64EncodedString()
        let chunkSize = 4096
        var idx = b64.startIndex
        while idx < b64.endIndex {
            let end = b64.index(idx, offsetBy: chunkSize, limitedBy: b64.endIndex) ?? b64.endIndex
            let chunk = String(b64[idx..<end])
            _ = try await ssh.executeCommand("printf '%s' '\(chunk)' | base64 -d >> '\(remotePath)'")
            idx = end
        }
        return remotePath
    }
}

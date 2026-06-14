import NIOCore
import NIOSSH

/// Custom auth delegate that offers a password regardless of which methods the server
/// advertises. Synology's sshd advertises only `keyboard-interactive` (not `password`),
/// so Citadel's built-in `.passwordBased` — which guards on `availableMethods.contains(.password)`
/// — fails with `allAuthenticationOptionsFailed`. NIOSSH sends a password offer for both
/// `password` and `keyboard-interactive`, so offering it unconditionally authenticates
/// against Synology (matching how OpenSSH falls back to keyboard-interactive).
final class PasswordAuthDelegate: NIOSSHClientUserAuthenticationDelegate {
    private let username: String
    private let password: String
    private var offered = false

    init(username: String, password: String) {
        self.username = username
        self.password = password
    }

    func nextAuthenticationType(
        availableMethods: NIOSSHAvailableUserAuthenticationMethods,
        nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>
    ) {
        // Offer the password once. If the server rejects it, stop (nil) so auth fails cleanly.
        guard !offered else {
            nextChallengePromise.succeed(nil)
            return
        }
        offered = true

        // Offer the password unconditionally. NIOSSH has no keyboard-interactive support
        // and Synology may not advertise `password` in availableMethods, so we do NOT guard
        // on availableMethods — we just send the password offer and let the server decide.
        nextChallengePromise.succeed(
            NIOSSHUserAuthenticationOffer(
                username: username,
                serviceName: "",
                offer: .password(.init(password: password))
            )
        )
    }
}

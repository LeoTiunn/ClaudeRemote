package com.claude.remote.core.ssh

/** Shaping of commands sent over a per-command SSH exec channel. */
object ExecCommand {
    /**
     * An exec channel runs a non-interactive shell with sshd's bare PATH, so tmux
     * (/opt/homebrew/bin) and helpers like cr-sessions' python are not found unless
     * we add them. Same idea as the iOS client's pathPrefix.
     */
    private const val PATH_PREFIX =
        "export PATH=\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH; "

    fun wrap(command: String): String = PATH_PREFIX + command
}

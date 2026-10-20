package com.claude.remote.core.tmux

/**
 * Commands that let the app switch ITS OWN tmux client, never someone else's.
 *
 * `tmux switch-client -t X` with no `-c` moves whichever client tmux considers
 * current — from an exec channel that is the most recently active one, which can
 * be another phone, a tablet, or the Mac's own terminal. So before attaching we
 * record this shell's tty in a tmux user option, and every switch passes it as -c.
 */
object OwnClient {
    private val TTY = Regex("^/dev/(ttys?[0-9]+|pts/[0-9]+)$")

    private fun option(id: String) = "@cr-client-$id"

    /** Run in the app's terminal shell just before `tmux attach`. */
    fun recordTty(id: String) = "tmux set -g ${option(id)} \"\$(tty)\""

    fun readTty(id: String) = "tmux show -gv ${option(id)} 2>/dev/null"

    /** A tty path, or null when we cannot be sure which client is ours. */
    fun parseTty(output: String): String? = output.trim().takeIf { TTY.matches(it) }

    fun switchTo(tty: String, session: String) =
        "tmux switch-client -c '$tty' -t '${session.replace("'", "'\\''")}'"
}

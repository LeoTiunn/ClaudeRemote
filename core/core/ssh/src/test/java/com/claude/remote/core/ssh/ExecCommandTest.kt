package com.claude.remote.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecCommandTest {

    // An exec channel gets sshd's bare PATH (/usr/bin:/bin:...), not the login
    // shell's, so tmux under /opt/homebrew/bin is not found without this prefix.
    @Test
    fun `wrap puts homebrew and local bin on PATH ahead of the command`() {
        val wrapped = ExecCommand.wrap("tmux list-sessions")

        assertTrue(wrapped.startsWith("export PATH=\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH; "))
        assertTrue(wrapped.endsWith("tmux list-sessions"))
    }

    // Callers already quote their own arguments; wrap must pass the command through
    // byte-for-byte or session names with quotes break.
    @Test
    fun `wrap leaves the command itself untouched`() {
        val cmd = "tmux switch-client -t 'it'\\''s here'"

        assertEquals(cmd, ExecCommand.wrap(cmd).substringAfter("; "))
    }

    // The old shared-shell path broke on long commands (PTY line buffer). An exec
    // channel has no such limit, so nothing here may truncate.
    @Test
    fun `wrap keeps very long commands whole`() {
        val cmd = "echo " + "x".repeat(5000)

        assertTrue(ExecCommand.wrap(cmd).endsWith(cmd))
    }
}

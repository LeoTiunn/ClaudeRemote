package com.claude.remote.core.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnClientTest {

    // Runs in the app's own terminal shell right before `tmux attach`, so $(tty) is
    // the tty tmux will report as this app's client_tty.
    @Test
    fun `record stores this shell's tty under a per-app option`() {
        assertEquals("tmux set -g @cr-client-abc \"\$(tty)\"", OwnClient.recordTty("abc"))
    }

    @Test
    fun `read fetches the same option quietly`() {
        assertEquals("tmux show -gv @cr-client-abc 2>/dev/null", OwnClient.readTty("abc"))
    }

    // The whole point: -c pins the switch to OUR client. Without it tmux moves the most
    // recently active client — another phone, a tablet, or the Mac's own terminal.
    @Test
    fun `switch targets only our own client`() {
        assertEquals(
            "tmux switch-client -c '/dev/ttys014' -t 'main (apa)'",
            OwnClient.switchTo("/dev/ttys014", "main (apa)")
        )
    }

    @Test
    fun `switch escapes single quotes in the session name`() {
        assertEquals(
            "tmux switch-client -c '/dev/ttys014' -t 'it'\\''s'",
            OwnClient.switchTo("/dev/ttys014", "it's")
        )
    }

    // Anything that is not a plain tty path (empty option, error text, injected shell)
    // means we do not know our client; the caller must re-attach instead of switching.
    @Test
    fun `parse accepts only a real tty path`() {
        assertEquals("/dev/ttys014", OwnClient.parseTty("/dev/ttys014\n"))
        assertEquals("/dev/pts/3", OwnClient.parseTty("/dev/pts/3"))
        assertNull(OwnClient.parseTty(""))
        assertNull(OwnClient.parseTty("not a tty"))
        assertNull(OwnClient.parseTty("/dev/ttys014; rm -rf ~"))
    }
}

package com.claude.remote.core.tmux

import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxSessionManagerTest {

    @Test
    fun `TmuxSession holds name and windowName`() {
        val session = TmuxSession(
            name = "test-session",
            windowName = "test-window"
        )
        assertEquals("test-session", session.name)
        assertEquals("test-window", session.windowName)
    }
}

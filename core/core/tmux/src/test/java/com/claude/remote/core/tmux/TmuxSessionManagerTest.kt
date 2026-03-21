package com.claude.remote.core.tmux

import org.junit.Test
import kotlin.test.assertTrue

class TmuxSessionManagerTest {

    @Test
    fun `TmuxSession holds name and windowName`() {
        val session = TmuxSession(
            name = "test-session",
            windowName = "test-window"
        )
        assertTrue(session.name == "test-session")
        assertTrue(session.windowName == "test-window")
    }
}
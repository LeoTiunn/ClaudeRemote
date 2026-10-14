package com.claude.remote.core.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class RootLayoutTest {

    // 600dp is Material's medium-width breakpoint: small tablets and open foldables.
    @Test
    fun `600dp and wider splits into sidebar and terminal`() {
        assertEquals(RootLayout.SPLIT, RootLayout.forWidthDp(600))
        assertEquals(RootLayout.SPLIT, RootLayout.forWidthDp(1280))
    }

    @Test
    fun `phone widths keep the one-screen layout`() {
        assertEquals(RootLayout.STACKED, RootLayout.forWidthDp(411))
        assertEquals(RootLayout.STACKED, RootLayout.forWidthDp(599))
    }
}

class SidebarActionTest {

    @Test
    fun `first tap opens the terminal`() {
        assertEquals(SidebarAction.Open("webmux"), SidebarAction.forTap("webmux", current = null))
    }

    @Test
    fun `tapping another session switches in place`() {
        assertEquals(SidebarAction.SwitchTo("webmux"), SidebarAction.forTap("webmux", current = "apa"))
    }

    // Re-attaching the session already on screen clears the terminal and rebuilds
    // the PTY for nothing.
    @Test
    fun `tapping the current session does nothing`() {
        assertEquals(SidebarAction.None, SidebarAction.forTap("webmux", current = "webmux"))
    }
}

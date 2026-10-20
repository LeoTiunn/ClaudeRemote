package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MouseWheelTest {

    // SGR (1006) wheel encoding, same bytes the iOS client sends: button 64 = up,
    // 65 = down, 1-based column;row, final 'M' = press.
    @Test
    public void wheelUpIsButton64() {
        assertEquals("\u001b[<64;40;5M", TerminalView.sgrWheel(true, 40, 5));
    }

    @Test
    public void wheelDownIsButton65() {
        assertEquals("\u001b[<65;40;5M", TerminalView.sgrWheel(false, 40, 5));
    }

    // Coordinates below 1 are invalid in SGR; tmux drops the event. Clamp so a touch
    // at the very top-left edge still scrolls.
    @Test
    public void coordinatesClampToOne() {
        assertEquals("\u001b[<64;1;1M", TerminalView.sgrWheel(true, 0, -3));
    }
}

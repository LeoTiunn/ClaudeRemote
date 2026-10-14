package com.claude.remote.core.ui.layout

/**
 * Top-level arrangement. Tablets and open foldables get the webmux-style session
 * sidebar beside the terminal; phones keep the one-screen-at-a-time flow.
 * Mirrors the iOS RootLayout.
 */
enum class RootLayout {
    STACKED,
    SPLIT;

    companion object {
        /** Material's medium-width breakpoint. */
        private const val SPLIT_MIN_WIDTH_DP = 600

        fun forWidthDp(widthDp: Int): RootLayout =
            if (widthDp >= SPLIT_MIN_WIDTH_DP) SPLIT else STACKED
    }
}

/** What a tap on a sidebar session should do to the terminal pane. */
sealed interface SidebarAction {
    /** Nothing attached yet — bring up the terminal on this session. */
    data class Open(val name: String) : SidebarAction

    /** A terminal is showing another session — retarget it in place. */
    data class SwitchTo(val name: String) : SidebarAction

    /** Already showing it. */
    data object None : SidebarAction

    companion object {
        fun forTap(tapped: String, current: String?): SidebarAction = when (current) {
            null -> Open(tapped)
            tapped -> None
            else -> SwitchTo(tapped)
        }
    }
}

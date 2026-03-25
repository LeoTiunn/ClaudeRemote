package com.claude.remote.features.chat

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Display-only native terminal view using Termux's Canvas-based renderer.
 * All keyboard input is handled by a separate native TextField.
 */
@Composable
fun TerminalView(
    holder: NativeTerminalHolder,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Observe session generation to trigger recomposition when session changes
    val generation by holder.sessionGeneration.collectAsState()

    AndroidView(
        factory = { ctx ->
            // Always get a fresh view for the current Activity context
            holder.detachFromParent()
            holder.getOrCreateView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { tv ->
            // generation is read here so Compose re-runs this block on session change
            @Suppress("UNUSED_VARIABLE") val gen = generation
            val session = holder.termSession
            if (session != null && tv.currentSession != session) {
                tv.attachSession(session)
                if (tv.mEmulator == null && tv.width > 0) {
                    tv.updateSize()
                }
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

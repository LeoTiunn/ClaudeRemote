package com.claude.remote.features.chat

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Display-only native terminal view using Termux's Canvas-based renderer.
 * No WebView, no JS, no renderer process — immune to Android memory pressure.
 * All keyboard input is handled by a separate native TextField.
 */
@Composable
fun TerminalView(
    onResize: ((cols: Int, rows: Int) -> Unit)? = null,
    holder: NativeTerminalHolder,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    holder.onResize = onResize

    val view = remember {
        holder.detachFromParent()
        holder.getOrCreateView(context)
    }

    AndroidView(
        factory = {
            holder.detachFromParent()
            view.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier
    )
}

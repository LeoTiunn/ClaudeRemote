package com.claude.remote.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}

@Composable
fun ConnectionStatusDot(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp
) {
    val color = when (state) {
        ConnectionState.CONNECTING -> Color(0xFFFFA000)    // Amber
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)      // Green
        ConnectionState.RECONNECTING -> Color(0xFFFFA000)   // Amber
        ConnectionState.DISCONNECTED -> Color(0xFFF44336)   // Red
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}
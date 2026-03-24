package com.claude.remote.features.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow

private val ANSI_REGEX = Regex(
    buildString {
        append("\u001B\\[\\??[0-9;]*[a-zA-Z]")        // CSI sequences
        append("|\u001B\\][^\u0007\u001B]*[\u0007]")   // OSC ending with BEL
        append("|\u001B\\][^\u0007\u001B]*\u001B\\\\")  // OSC ending with ST
        append("|\u001B\\([A-Z]")                       // Character set selection
        append("|\\]697;[^\\]]*")                        // iTerm2 sequences
        append("|[\\x00-\\x08\\x0E-\\x1F]")             // Control chars (keep \t \n \r)
    }
)

private const val MAX_BUFFER = 100_000 // characters to keep in buffer

@Composable
fun NativeTerminalView(
    outputFlow: Flow<String>,
    modifier: Modifier = Modifier
) {
    var buffer by remember { mutableStateOf("") }
    val vertScrollState = rememberScrollState()

    // Collect SSH output, strip ANSI, append to buffer
    LaunchedEffect(Unit) {
        outputFlow.collect { chunk ->
            val clean = ANSI_REGEX.replace(chunk, "")
            buffer = if (buffer.length + clean.length > MAX_BUFFER) {
                // Trim from the start to stay within limit
                (buffer + clean).takeLast(MAX_BUFFER)
            } else {
                buffer + clean
            }
        }
    }

    // Auto-scroll to bottom when buffer changes
    LaunchedEffect(buffer) {
        vertScrollState.animateScrollTo(vertScrollState.maxValue)
    }

    Box(modifier = modifier) {
        SelectionContainer {
            Text(
                text = buffer.ifEmpty { "Waiting for output..." },
                style = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFEAE1D9) // warm terminal foreground
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1C1917)) // warm terminal background
                    .verticalScroll(vertScrollState)
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp)
            )
        }
    }
}

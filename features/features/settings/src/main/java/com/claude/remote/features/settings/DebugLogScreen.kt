package com.claude.remote.features.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claude.remote.core.ssh.DebugLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen() {
    val logs by DebugLog.logs.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Debug Log") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(DebugLog.getAll()))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy All")
                }
                OutlinedButton(
                    onClick = { DebugLog.clear() },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                ) {
                    Text("Clear")
                }
            }

            Text(
                text = "${logs.size} entries",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Text(
                text = logs.joinToString("\n").ifEmpty { "(no logs yet)" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .verticalScroll(scrollState)
                    .horizontalScroll(rememberScrollState())
            )
        }
    }
}

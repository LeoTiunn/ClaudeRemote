package com.claude.remote.features.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.claude.remote.core.ui.components.ConnectionState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.claude.remote.core.ui.components.ConnectionStatusDot

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onNavigateToSessions: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSessionSheet by remember { mutableStateOf(false) }
    var killConfirmSession by remember { mutableStateOf<String?>(null) }
    var showRefreshTokenConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.uploadAndAttachFile(it) } }
    val listState = rememberLazyListState()
    val messages = uiState.messages

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        viewModel.initVoiceInput(context)
    }

    // On resume: probe SSH connection, reconnect if dead
    LaunchedEffect(lifecycleOwner) {
        var firstResume = true
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (firstResume) {
                firstResume = false
                return@repeatOnLifecycle
            }
            // Probe SSH with actual command — don't trust cached connectionState
            viewModel.checkConnectionAndReconnect()
        }
    }

    // Auto-scroll to bottom on new content
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(
                index = listState.layoutInfo.totalItemsCount - 1,
                scrollOffset = Int.MAX_VALUE / 2
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (uiState.isTerminalMode) {
                                        viewModel.loadAvailableSessions()
                                        showSessionSheet = true
                                    }
                                },
                                onLongClick = {}
                            )
                        ) {
                            ConnectionStatusDot(state = uiState.connectionState)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                uiState.sessionName.ifEmpty { "Claude Remote" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (uiState.isTerminalMode) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Switch session",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Session dropdown
                        DropdownMenu(
                            expanded = showSessionSheet,
                            onDismissRequest = { showSessionSheet = false }
                        ) {
                            if (uiState.isLoadingSessions && uiState.availableSessions.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                uiState.availableSessions.forEach { session ->
                                    val isCurrent = session.name == uiState.sessionName
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.combinedClickable(
                                                    onClick = {
                                                        showSessionSheet = false
                                                        viewModel.switchSession(session.name)
                                                    },
                                                    onLongClick = {
                                                        if (!isCurrent) {
                                                            showSessionSheet = false
                                                            killConfirmSession = session.name
                                                        }
                                                    }
                                                ),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    session.name,
                                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isCurrent) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                                    )
                                            )
                                        },
                                        trailingIcon = {
                                            if (isCurrent) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Current",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        },
                                        onClick = {
                                            showSessionSheet = false
                                            viewModel.switchSession(session.name)
                                        }
                                    )
                                }
                                Divider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "+ New Session",
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showSessionSheet = false
                                        onNavigateToSessions()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Reconnect",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = {
                                        showSessionSheet = false
                                        viewModel.reconnect()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Refresh Token",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = {
                                        showSessionSheet = false
                                        showRefreshTokenConfirm = true
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    // Reconnect button — shown when disconnected
                    if (uiState.connectionState != ConnectionState.CONNECTED && uiState.sessionName.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.reconnect() },
                            enabled = !uiState.isReconnecting
                        ) {
                            if (uiState.isReconnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Reconnect",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Attach file button — only when connected
                    if (uiState.connectionState == ConnectionState.CONNECTED) {
                        IconButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            enabled = !uiState.isUploading
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Attach file",
                                tint = if (uiState.isUploading)
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Direct settings button
                    IconButton(onClick = { onNavigateToSettings() }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (uiState.isTerminalMode) {
            // Box layout: WebView never resizes regardless of keyboard state
            val density = androidx.compose.ui.platform.LocalDensity.current
            var inputContentHeightDp by remember { mutableStateOf(56.dp) }
            val navBarHeightDp = with(density) {
                WindowInsets.navigationBars.getBottom(density).toDp()
            }
            val inputRowHeightDp = inputContentHeightDp + navBarHeightDp

            Box(modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())) {
                // Terminal + key bar: fixed, never affected by keyboard
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = inputRowHeightDp)
                ) {
                    // Status bar — reconnecting, uploading, etc.
                    uiState.statusMessage?.let { status ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    if (uiState.isUploading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(
                                "Uploading file...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    TerminalView(
                        holder = viewModel.terminalHolder,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    Divider(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp
                    )

                    TerminalKeysBar(
                        onKey = { seq -> viewModel.sendRawEscape(seq) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Only input row moves with keyboard
                var termInput by remember { mutableStateOf("") }
                val focusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current
                val scope = rememberCoroutineScope()
                val sendAction = {
                    if (termInput.isNotEmpty()) {
                        viewModel.sendRawEscape(termInput + "\r")
                        termInput = ""
                    } else {
                        viewModel.sendRawEscape("\r")
                    }
                    scope.launch {
                        kotlinx.coroutines.delay(100)
                        keyboardController?.hide()
                    }
                }

                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(300)
                    focusRequester.requestFocus()
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .onSizeChanged { size ->
                            with(density) { inputContentHeightDp = size.height.toDp() }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = termInput,
                        onValueChange = { termInput = it },
                        placeholder = { Text("Type here...", fontSize = 15.sp) },
                        maxLines = 4,
                        textStyle = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Send
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = { sendAction() }
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { sendAction() },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding()
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            onCopy = { viewModel.copyMessageContent(message.id) }
                        )
                    }

                    if (uiState.isStreaming && (messages.isEmpty() || messages.last().isUser)) {
                        item(key = "streaming_indicator") {
                            StreamingIndicator()
                        }
                    }
                }

                Divider(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )

                TerminalKeysBar(
                    onKey = { seq -> viewModel.sendRawEscape(seq) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // Refresh Token confirmation dialog
    if (showRefreshTokenConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRefreshTokenConfirm = false },
            title = { Text("Refresh Token") },
            text = { Text("This will refresh the API token and restart Claude CLI in ALL active sessions.") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showRefreshTokenConfirm = false
                        viewModel.refreshToken()
                    }
                ) {
                    Text("Refresh")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRefreshTokenConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Kill session confirmation dialog
    killConfirmSession?.let { sessionName ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { killConfirmSession = null },
            title = { Text("Kill Session") },
            text = { Text("Kill tmux session \"$sessionName\"?") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.killSession(sessionName)
                        killConfirmSession = null
                    }
                ) {
                    Text("Kill", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { killConfirmSession = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    onCopy: () -> String?
) {
    val clipboardManager = LocalClipboardManager.current

    if (message.isUser) {
        // User message — right-aligned warm bubble
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.End
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.content,
                    style = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        letterSpacing = 0.2.sp
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    } else {
        // Assistant message — terminal output, monospace, horizontally scrollable
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        onCopy()?.let { content ->
                            clipboardManager.setText(AnnotatedString(content))
                        }
                    }
                )
        ) {
            Text(
                text = message.content,
                style = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    letterSpacing = 0.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onBackground,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            )

            // Action row — always visible, like Claude iOS
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Copy button
                IconButton(
                    onClick = {
                        onCopy()?.let { content ->
                            clipboardManager.setText(AnnotatedString(content))
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingIndicator(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")
    val dotColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 500,
                        delayMillis = index * 160,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$index"
            )

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}


@Composable
fun TerminalKeysBar(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keys = listOf(
        "Esc" to "\u001b",
        "C-c" to "\u0003",
        "C-b" to "\u0002",
        "Tab" to "\t",
        "⇧Tab" to "\u001b[Z",
        "Ent" to "\r",
        "⌫" to "\u007f",
        "←" to "\u001b[D",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "→" to "\u001b[C",
    )

    Row(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        keys.forEach { (label, seq) ->
            Surface(
                onClick = { onKey(seq) },
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .weight(1f)
                    .focusable(false)
            ) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

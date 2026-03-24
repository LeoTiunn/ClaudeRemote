package com.claude.remote.features.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import com.claude.remote.core.tmux.TmuxSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Base64
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sshClient: SshClient,
    private val tmuxSessionManager: TmuxSessionManager,
    val webViewHolder: TerminalWebViewHolder,
    private val fileUploadManager: FileUploadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Raw terminal output for xterm.js WebView
    // DROP_OLDEST prevents emit() from suspending and blocking the shell reader
    private val _terminalOutput = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val terminalOutput: Flow<String> = _terminalOutput.asSharedFlow()

    companion object {
        private val PROMPT_MARKERS = listOf(
            "\$ ",
            "\u276F ",
            ">>> ",
            "\n> ",
        )

        // Cursor home: \e[H or \e[1;1H — signals a full screen refresh
        private val CURSOR_HOME_REGEX = Regex("\u001B\\[(?:H|1;1H|\\?1049[hl])")

        // Cursor position: \e[row;colH — replace with newline
        private val CURSOR_POSITION_REGEX = Regex("\u001B\\[\\d+;\\d+[Hf]")

        // Cursor forward: \e[nC — replace with n spaces
        private val CURSOR_FORWARD_REGEX = Regex("\u001B\\[(\\d*)C")

        // Erase sequences: \e[nK (erase line), \e[nJ (erase screen)
        private val ERASE_REGEX = Regex("\u001B\\[\\d*[JK]")

        // All other ANSI/terminal sequences — strip entirely
        private val ANSI_ESCAPE_REGEX = Regex(
            buildString {
                append("\u001B\\[\\??[0-9;]*[a-zA-Z]")       // CSI sequences incl. DEC private mode
                append("|\u001B\\][^\u0007\u001B]*[\u0007]")  // OSC ending with BEL
                append("|\u001B\\][^\u0007\u001B]*\u001B\\\\") // OSC ending with ST
                append("|\u001B\\([A-Z]")                      // Character set selection
                append("|\\]697;[^\\]]*")                      // Fig/iTerm2 proprietary sequences
                append("|[\\x00-\\x08\\x0E-\\x1F]")           // Control chars (keep \t \n \r)
            }
        )
    }

    init {
        // Recover terminal mode if SSH is already attached to tmux
        // (e.g., navigated away and came back — ViewModel recreated but SSH persists)
        _uiState.update { it.copy(sessionName = sshClient.currentSessionName) }
        if (sshClient.isAttachedToTmux) {
            _uiState.update { it.copy(isTerminalMode = true, isStreaming = true) }
            // Force tmux to redraw so the new WebView gets content
            viewModelScope.launch {
                kotlinx.coroutines.delay(500) // Wait for WebView to load
                try {
                    // Send refresh sequence: Ctrl+L redraws the screen
                    sshClient.sendRawBytes(byteArrayOf(0x0C)) // Ctrl+L
                } catch (_: Exception) {}
            }
        }

        viewModelScope.launch {
            while (true) {
                try {
                    sshClient.outputStream.collect { chunk ->
                        try {
                            if (sshClient.isAttachedToTmux) {
                                _uiState.update { it.copy(
                                    isTerminalMode = true,
                                    isStreaming = true,
                                    outputChunkCount = it.outputChunkCount + 1
                                ) }
                                writeToWebView(chunk)
                            } else {
                                handleOutputChunk(chunk)
                            }
                        } catch (e: Exception) {
                            DebugLog.log("CHAT", "Chunk processing error: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.log("CHAT", "Output collect failed: ${e.message}")
                }
                // If collect ended (stream closed), wait and retry
                DebugLog.log("CHAT", "Output collect ended, retrying in 1s...")
                kotlinx.coroutines.delay(1000)
            }
        }

        viewModelScope.launch {
            sshClient.connectionState.collect { state ->
                val wasDisconnected = _uiState.value.connectionState != com.claude.remote.core.ui.components.ConnectionState.CONNECTED
                _uiState.update { it.copy(connectionState = state, isReconnecting = false) }
                // Auto-reconnect to session after SSH reconnects
                if (state == com.claude.remote.core.ui.components.ConnectionState.CONNECTED && wasDisconnected) {
                    val session = sshClient.currentSessionName
                    if (session.isNotEmpty() && !sshClient.isAttachedToTmux) {
                        restoreAndAttach(session)
                    }
                }
            }
        }
    }

    fun reconnect() {
        val sessionName = sshClient.currentSessionName
        if (sessionName.isEmpty()) return
        _uiState.update { it.copy(isReconnecting = true) }
        viewModelScope.launch {
            try {
                if (sshClient.connectionState.value != com.claude.remote.core.ui.components.ConnectionState.CONNECTED) {
                    sshClient.reconnect()
                    // connectionState collector above will auto-attach via restoreAndAttach
                } else if (!sshClient.isAttachedToTmux) {
                    restoreAndAttach(sessionName)
                    _uiState.update { it.copy(isReconnecting = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isReconnecting = false, error = "Reconnect failed: ${e.message}") }
            }
        }
    }

    private suspend fun restoreAndAttach(sessionName: String) {
        try {
            // Capture scrollback history before attaching
            val history = tmuxSessionManager.capturePane(sessionName, sshClient)
            if (history.isNotBlank()) {
                // Write captured history to xterm.js so user sees previous content
                _terminalOutput.emit("\u001b[2J\u001b[H") // Clear screen first
                _terminalOutput.emit(history)
            }
        } catch (e: Exception) {
            DebugLog.log("CHAT", "capturePane failed: ${e.message}")
        }
        tmuxSessionManager.attachToSession(sessionName, sshClient)
        kotlinx.coroutines.delay(500)
        sshClient.sendRawBytes(byteArrayOf(0x0C)) // Ctrl+L redraw
    }

    private fun cleanTerminalOutput(raw: String): String {
        var text = raw

        // 1. Replace cursor-position sequences with newlines (preserves line structure)
        text = CURSOR_POSITION_REGEX.replace(text, "\n")

        // 2. Replace cursor-forward with spaces (preserves visual gaps)
        text = CURSOR_FORWARD_REGEX.replace(text) { match ->
            val n = match.groupValues[1].toIntOrNull() ?: 1
            " ".repeat(n.coerceIn(1, 40))
        }

        // 3. Remove erase sequences
        text = ERASE_REGEX.replace(text, "")

        // 4. Strip all remaining ANSI escape codes
        text = ANSI_ESCAPE_REGEX.replace(text, "")

        // 5. Clean up: collapse multiple blank lines, trim trailing whitespace per line
        text = text.lines()
            .map { it.trimEnd() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return text
    }

    private fun handleOutputChunk(rawChunk: String) {
        // Detect screen refresh (cursor home = tmux redrawing entire screen)
        val isScreenRefresh = CURSOR_HOME_REGEX.containsMatchIn(rawChunk)

        val chunk = cleanTerminalOutput(rawChunk)
        if (chunk.isEmpty()) return

        val endsWithPrompt = PROMPT_MARKERS.any { marker -> chunk.endsWith(marker) }

        _uiState.update { state ->
            val messages = state.messages.toMutableList()

            val isStreaming = state.isStreaming || sshClient.isAttachedToTmux

            if (isStreaming) {
                val cleanChunk = if (endsWithPrompt) {
                    var cleaned = chunk
                    for (marker in PROMPT_MARKERS) {
                        if (cleaned.endsWith(marker)) {
                            cleaned = cleaned.removeSuffix(marker).trimEnd()
                            break
                        }
                    }
                    cleaned
                } else {
                    chunk
                }

                if (cleanChunk.isNotEmpty()) {
                    if (messages.isNotEmpty() && !messages.last().isUser) {
                        if (isScreenRefresh) {
                            // Screen refresh: REPLACE last message (tmux redraws entire screen)
                            val lastMsg = messages.last()
                            messages[messages.lastIndex] = lastMsg.copy(
                                content = cleanChunk
                            )
                        } else {
                            // Normal output: append
                            val lastMsg = messages.last()
                            messages[messages.lastIndex] = lastMsg.copy(
                                content = lastMsg.content + cleanChunk
                            )
                        }
                    } else {
                        messages.add(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                content = cleanChunk,
                                isUser = false
                            )
                        )
                    }
                }
            }

            state.copy(
                messages = messages,
                isStreaming = if (endsWithPrompt) false else isStreaming
            )
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        sshClient.resizePty(cols, rows)
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        // Clear input immediately
        _uiState.update { it.copy(inputText = "") }

        if (_uiState.value.isTerminalMode) {
            // Terminal mode: just send to SSH, no chat messages
            viewModelScope.launch {
                try {
                    sshClient.sendInput(text)
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = e.message) }
                }
            }
        } else {
            // Chat mode: add user message bubble
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                content = text,
                isUser = true
            )
            _uiState.update {
                it.copy(
                    messages = it.messages + userMessage,
                    isStreaming = true
                )
            }
            viewModelScope.launch {
                try {
                    sshClient.sendInput(text)
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(error = e.message, isStreaming = false)
                    }
                }
            }
        }
    }

    fun uploadAndAttachFile(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isUploading = true, uploadFileName = "uploading...") }

                // Need to detach from tmux to run executeCommand for upload
                val wasAttached = sshClient.isAttachedToTmux
                if (wasAttached) {
                    sshClient.sendRawBytes(byteArrayOf(0x02)) // Ctrl+B
                    kotlinx.coroutines.delay(150)
                    sshClient.sendRawBytes("d".toByteArray())
                    sshClient.isAttachedToTmux = false
                    kotlinx.coroutines.delay(800)
                }

                val attachment = fileUploadManager.uploadFile(uri, sshClient)
                _uiState.update { it.copy(isUploading = false, uploadFileName = null) }

                // Re-attach to tmux
                if (wasAttached) {
                    val sessionName = sshClient.currentSessionName
                    sshClient.isAttachedToTmux = true
                    sshClient.sendInput("tmux attach -t '$sessionName'")
                    kotlinx.coroutines.delay(500)
                    // Type the remote path into the terminal
                    sshClient.sendRawBytes(attachment.remotePath!!.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isUploading = false, uploadFileName = null, error = "Upload failed: ${e.message}")
                }
                // Re-attach if detached
                if (!sshClient.isAttachedToTmux && sshClient.currentSessionName.isNotEmpty()) {
                    sshClient.isAttachedToTmux = true
                    sshClient.sendInput("tmux attach -t '${sshClient.currentSessionName}'")
                }
            }
        }
    }

    private fun writeToWebView(chunk: String) {
        val wv = webViewHolder.webView ?: return
        val b64 = Base64.encodeToString(
            chunk.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        wv.post {
            try {
                wv.evaluateJavascript("writeBase64('$b64')", null)
            } catch (_: Exception) {}
        }
    }

    fun sendRawEscape(sequence: String) {
        viewModelScope.launch {
            try {
                sshClient.sendRawBytes(sequence.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {}
        }
    }

    fun stopStreaming() {
        _uiState.update { it.copy(isStreaming = false) }
    }

    fun copyMessageContent(messageId: String): String? {
        return _uiState.value.messages.find { it.id == messageId }?.content
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // Voice input

    private var voiceInputManager: VoiceInputManager? = null

    fun initVoiceInput(context: Context) {
        if (voiceInputManager == null) {
            voiceInputManager = VoiceInputManager(context)
            viewModelScope.launch {
                voiceInputManager?.state?.collect { voiceState ->
                    _uiState.update {
                        it.copy(
                            isVoiceListening = voiceState.isListening,
                            voicePartialResult = voiceState.partialResult
                        )
                    }
                }
            }
        }
    }

    fun toggleVoiceInput() {
        if (_uiState.value.isVoiceListening) {
            voiceInputManager?.stopListening()
        } else {
            voiceInputManager?.startListening { recognizedText ->
                if (_uiState.value.isTerminalMode) {
                    // Terminal mode: send directly to SSH
                    viewModelScope.launch {
                        try {
                            sshClient.sendRawBytes(recognizedText.toByteArray(Charsets.UTF_8))
                        } catch (_: Exception) {}
                    }
                } else {
                    _uiState.update {
                        it.copy(inputText = it.inputText + recognizedText)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceInputManager?.destroy()
    }
}

package com.claude.remote.features.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sshClient: SshClient,
    private val tmuxSessionManager: TmuxSessionManager,
    val webViewHolder: TerminalWebViewHolder
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
            sshClient.outputStream.collect { chunk ->
                if (sshClient.isAttachedToTmux) {
                    _uiState.update { it.copy(isTerminalMode = true, isStreaming = true) }
                    _terminalOutput.emit(chunk)
                } else {
                    handleOutputChunk(chunk)
                }
            }
        }

        viewModelScope.launch {
            sshClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
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
                _uiState.update {
                    it.copy(inputText = it.inputText + recognizedText)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceInputManager?.destroy()
    }
}

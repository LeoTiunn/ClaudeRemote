package com.claude.remote.features.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claude.remote.core.ssh.SshClient
import com.claude.remote.core.tmux.TmuxSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sshClient: SshClient,
    private val tmuxSessionManager: TmuxSessionManager,
    private val settingsRepository: com.claude.remote.features.settings.SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    companion object {
        private val PROMPT_MARKERS = listOf(
            "\$ ",
            "\u276F ",
            ">>> ",
            "\n> ",
        )
        private val ANSI_ESCAPE_REGEX = Regex(
            "\u001B\\[[0-9;]*[a-zA-Z]|\u001B\\]\\d+;[^\u0007]*\u0007|\u001B\\([A-Z]"
        )
    }

    init {
        // Collect SSH output and build assistant messages
        viewModelScope.launch {
            sshClient.outputStream.collect { chunk ->
                handleOutputChunk(chunk)
            }
        }

        // Track connection state
        viewModelScope.launch {
            sshClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state, isConnecting = false) }
            }
        }

        // Auto-connect if we have saved credentials
        autoConnect()
    }

    private fun autoConnect() {
        if (settingsRepository.hasPassword()) {
            connect()
        } else {
            _uiState.update { it.copy(showPasswordPrompt = true) }
        }
    }

    fun connect() {
        val host = settingsRepository.getSshHost()
        val port = settingsRepository.getSshPort().toIntOrNull() ?: 22
        val username = settingsRepository.getSshUsername()
        val password = settingsRepository.getSshPassword()

        if (password.isEmpty()) {
            _uiState.update { it.copy(showPasswordPrompt = true) }
            return
        }

        _uiState.update { it.copy(isConnecting = true, error = null) }
        viewModelScope.launch {
            try {
                sshClient.connect(host, port, username, password)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Connection failed: ${e.message}", isConnecting = false)
                }
            }
        }
    }

    fun connectWithPassword(password: String) {
        settingsRepository.setSshPassword(password)
        _uiState.update { it.copy(showPasswordPrompt = false) }
        connect()
    }

    fun dismissPasswordPrompt() {
        _uiState.update { it.copy(showPasswordPrompt = false) }
    }

    private fun handleOutputChunk(rawChunk: String) {
        // Strip ANSI escape codes from terminal output
        val chunk = ANSI_ESCAPE_REGEX.replace(rawChunk, "")
        if (chunk.isEmpty()) return

        // Check if this chunk contains a prompt marker, indicating Claude is done
        val endsWithPrompt = PROMPT_MARKERS.any { marker -> chunk.endsWith(marker) }

        _uiState.update { state ->
            val messages = state.messages.toMutableList()

            if (state.isStreaming) {
                // Strip the prompt marker from the content if present
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
                        // Append to existing assistant message
                        val lastMsg = messages.last()
                        messages[messages.lastIndex] = lastMsg.copy(
                            content = lastMsg.content + cleanChunk
                        )
                    } else {
                        // Create new assistant message
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
                isStreaming = if (endsWithPrompt) false else state.isStreaming
            )
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text,
            isUser = true
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
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

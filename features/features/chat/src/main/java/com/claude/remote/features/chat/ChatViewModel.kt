package com.claude.remote.features.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ChatViewModel — uses JSch SSH connected to Termux TerminalEmulator.
 *
 * SSH connection is handled by JSch (pure Java). Terminal rendering
 * is handled by Termux's TerminalEmulator + TerminalView (native Canvas).
 * SshTerminalSession bridges the two — no external binary needed.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sshClient: SshClient,
    val terminalHolder: NativeTerminalHolder,
    private val fileUploadManager: FileUploadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        val sessionName = sshClient.currentSessionName
        if (terminalHolder.isSessionRunning()) {
            _uiState.update { it.copy(
                isTerminalMode = true,
                isStreaming = true,
                sessionName = sessionName,
                connectionState = com.claude.remote.core.ui.components.ConnectionState.CONNECTED
            ) }
            terminalHolder.attachExistingSession()
        } else if (sessionName.isNotEmpty()) {
            connectAndAttach(sessionName)
        }
    }

    fun connectAndAttach(sessionName: String) {
        if (sshClient.host.isBlank()) {
            _uiState.update { it.copy(error = "SSH host not configured") }
            return
        }

        terminalHolder.destroySession()

        _uiState.update { it.copy(
            isTerminalMode = true,
            isStreaming = true,
            sessionName = sessionName,
            connectionState = com.claude.remote.core.ui.components.ConnectionState.CONNECTING
        ) }

        viewModelScope.launch {
            try {
                // Open new shell channel on existing SSH session
                terminalHolder.createSshSession()

                _uiState.update { it.copy(
                    connectionState = com.claude.remote.core.ui.components.ConnectionState.CONNECTED
                ) }

                // Wait for shell prompt, then attach tmux
                kotlinx.coroutines.delay(1000)
                if (terminalHolder.isSessionRunning() && sessionName.isNotEmpty()) {
                    DebugLog.log("CHAT", "Attaching to tmux session: $sessionName")
                    terminalHolder.writeToSession("tmux attach -t '$sessionName' || tmux new-session -s '$sessionName'\r")
                    sshClient.currentSessionName = sessionName
                    sshClient.isAttachedToTmux = true
                }
            } catch (e: Exception) {
                DebugLog.log("CHAT", "SSH connect failed: ${e.message}")
                _uiState.update { it.copy(
                    connectionState = com.claude.remote.core.ui.components.ConnectionState.DISCONNECTED,
                    error = "SSH failed: ${e.message}"
                ) }
            }
        }
    }

    fun reconnect() {
        val sessionName = _uiState.value.sessionName
        if (sessionName.isNotEmpty()) {
            connectAndAttach(sessionName)
        }
    }

    fun sendRawEscape(sequence: String) {
        terminalHolder.writeBytes(sequence.toByteArray(Charsets.UTF_8))
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return
        _uiState.update { it.copy(inputText = "") }

        if (_uiState.value.isTerminalMode) {
            terminalHolder.writeToSession(text + "\r")
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun uploadAndAttachFile(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isUploading = true, uploadFileName = "uploading...") }

                terminalHolder.writeBytes(byteArrayOf(0x02)) // Ctrl+B
                kotlinx.coroutines.delay(150)
                terminalHolder.writeBytes("d".toByteArray())
                kotlinx.coroutines.delay(800)

                val attachment = fileUploadManager.uploadFile(uri, sshClient)
                _uiState.update { it.copy(isUploading = false, uploadFileName = null) }

                val sessionName = _uiState.value.sessionName
                if (sessionName.isNotEmpty()) {
                    terminalHolder.writeToSession("tmux attach -t '$sessionName'\r")
                    kotlinx.coroutines.delay(500)
                    terminalHolder.writeBytes(attachment.remotePath!!.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isUploading = false, uploadFileName = null, error = "Upload failed: ${e.message}")
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
                if (_uiState.value.isTerminalMode) {
                    terminalHolder.writeBytes(recognizedText.toByteArray(Charsets.UTF_8))
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

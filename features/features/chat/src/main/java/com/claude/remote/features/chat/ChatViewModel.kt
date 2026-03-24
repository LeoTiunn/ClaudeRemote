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
 * ChatViewModel — now uses dbclient subprocess via standard Termux TerminalSession.
 *
 * All SSH I/O goes through the PTY. No Java SSH bridge, no race conditions.
 * The SshClient is still used for settings (host, port, username, password)
 * but NOT for the actual connection — dbclient handles that.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sshClient: SshClient,
    val terminalHolder: NativeTerminalHolder,
    private val fileUploadManager: FileUploadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var passwordSent = false

    init {
        val sessionName = sshClient.currentSessionName
        if (terminalHolder.isSessionRunning()) {
            // Reattach to existing dbclient subprocess
            _uiState.update { it.copy(
                isTerminalMode = true,
                isStreaming = true,
                sessionName = sessionName,
                connectionState = com.claude.remote.core.ui.components.ConnectionState.CONNECTED
            ) }
            terminalHolder.attachExistingSession()
        } else if (sessionName.isNotEmpty()) {
            // New navigation from session switcher — start dbclient
            connectAndAttach(sessionName)
        }
    }

    /**
     * Start a new SSH connection via dbclient subprocess.
     * Called from session switcher after user picks a session.
     */
    fun connectAndAttach(sessionName: String) {
        val host = sshClient.host
        val port = sshClient.port
        val username = sshClient.username
        val password = sshClient.password

        if (host.isBlank()) {
            _uiState.update { it.copy(error = "SSH host not configured") }
            return
        }

        // Kill any existing session
        terminalHolder.destroySession()
        passwordSent = false

        _uiState.update { it.copy(
            isTerminalMode = true,
            isStreaming = true,
            sessionName = sessionName,
            connectionState = com.claude.remote.core.ui.components.ConnectionState.CONNECTING
        ) }

        // Create dbclient subprocess — this is the Termux way
        terminalHolder.createSession(host, port, username)

        // Auto-send password when prompted, then attach to tmux
        viewModelScope.launch {
            autoLogin(password, sessionName)
        }
    }

    /**
     * Watch for password prompt and tmux attachment.
     * dbclient will show "password:" on the PTY — we send the stored password.
     */
    private suspend fun autoLogin(password: String, sessionName: String) {
        // Wait for dbclient to prompt for password
        // The terminal emulator processes the output, we just need to wait a bit
        // and send the password + Enter
        kotlinx.coroutines.delay(3000) // Wait for SSH handshake + password prompt

        if (!passwordSent && terminalHolder.isSessionRunning()) {
            DebugLog.log("CHAT", "Sending password...")
            terminalHolder.writeToSession(password + "\r")
            passwordSent = true

            _uiState.update { it.copy(
                connectionState = com.claude.remote.core.ui.components.ConnectionState.CONNECTED
            ) }

            // Wait for shell, then attach to tmux session
            kotlinx.coroutines.delay(2000)
            if (terminalHolder.isSessionRunning() && sessionName.isNotEmpty()) {
                DebugLog.log("CHAT", "Attaching to tmux session: $sessionName")
                terminalHolder.writeToSession("tmux attach -t '$sessionName' || tmux new-session -s '$sessionName'\r")
                sshClient.currentSessionName = sessionName
                sshClient.isAttachedToTmux = true
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

                // Detach from tmux
                terminalHolder.writeBytes(byteArrayOf(0x02)) // Ctrl+B
                kotlinx.coroutines.delay(150)
                terminalHolder.writeBytes("d".toByteArray())
                kotlinx.coroutines.delay(800)

                val attachment = fileUploadManager.uploadFile(uri, sshClient)
                _uiState.update { it.copy(isUploading = false, uploadFileName = null) }

                // Re-attach to tmux
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

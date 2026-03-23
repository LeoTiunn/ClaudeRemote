package com.claude.remote.features.chat

import android.content.Context
import android.net.Uri
import androidx.annotation.NonNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import com.claude.remote.core.tmux.TmuxSessionManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
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
    private val fileUploadManager: FileUploadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Termux terminal session bridged to SSH. Created lazily when terminal mode starts. */
    var sshTerminalSession: SshTerminalSession? = null
        private set

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(@NonNull changedSession: TerminalSession) {
            // TerminalView observes this and redraws — nothing extra needed
        }

        override fun onTitleChanged(@NonNull changedSession: TerminalSession) {}
        override fun onSessionFinished(@NonNull finishedSession: TerminalSession) {
            DebugLog.log("VIEWMODEL", "Session finished")
        }

        override fun onCopyTextToClipboard(@NonNull session: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(@NonNull session: TerminalSession) {}
        override fun onColorsChanged(@NonNull session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(@NonNull session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int = 0

        override fun logError(tag: String, message: String) = DebugLog.log(tag, "E: $message")
        override fun logWarn(tag: String, message: String) = DebugLog.log(tag, "W: $message")
        override fun logInfo(tag: String, message: String) = DebugLog.log(tag, "I: $message")
        override fun logDebug(tag: String, message: String) = DebugLog.log(tag, "D: $message")
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            DebugLog.log(tag, "$message: ${e.message}")
        }
        override fun logStackTrace(tag: String, e: Exception) {
            DebugLog.log(tag, "Exception: ${e.message}")
        }
    }

    init {
        _uiState.update { it.copy(sessionName = sshClient.currentSessionName) }
        if (sshClient.isAttachedToTmux) {
            _uiState.update { it.copy(isTerminalMode = true, isStreaming = true) }
            ensureTerminalSession()
            viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                try {
                    sshClient.sendRawBytes(byteArrayOf(0x0C)) // Ctrl+L redraw
                } catch (_: Exception) {}
            }
        }

        viewModelScope.launch {
            sshClient.connectionState.collect { state ->
                val wasDisconnected = _uiState.value.connectionState != com.claude.remote.core.ui.components.ConnectionState.CONNECTED
                _uiState.update { it.copy(connectionState = state, isReconnecting = false) }
                if (state == com.claude.remote.core.ui.components.ConnectionState.CONNECTED && wasDisconnected) {
                    val session = sshClient.currentSessionName
                    if (session.isNotEmpty() && !sshClient.isAttachedToTmux) {
                        restoreAndAttach(session)
                    }
                }
            }
        }
    }

    private fun ensureTerminalSession(): SshTerminalSession {
        return sshTerminalSession ?: SshTerminalSession(sshClient, viewModelScope, sessionClient).also {
            sshTerminalSession = it
        }
    }

    fun getOrCreateTerminalSession(): SshTerminalSession {
        return ensureTerminalSession()
    }

    fun reconnect() {
        val sessionName = sshClient.currentSessionName
        if (sessionName.isEmpty()) return
        _uiState.update { it.copy(isReconnecting = true) }
        viewModelScope.launch {
            try {
                if (sshClient.connectionState.value != com.claude.remote.core.ui.components.ConnectionState.CONNECTED) {
                    sshClient.reconnect()
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
        tmuxSessionManager.attachToSession(sessionName, sshClient)
        kotlinx.coroutines.delay(500)
        sshClient.sendRawBytes(byteArrayOf(0x0C)) // Ctrl+L redraw
    }

    fun sendRawEscape(sequence: String) {
        val bytes = sequence.toByteArray(Charsets.UTF_8)
        DebugLog.log("VIEWMODEL", "sendRawEscape: ${bytes.size} bytes")
        viewModelScope.launch {
            try {
                sshClient.sendRawBytes(bytes)
            } catch (e: Exception) {
                DebugLog.log("VIEWMODEL", "sendRawEscape FAILED: ${e.message}")
            }
        }
    }

    fun uploadAndAttachFile(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isUploading = true, uploadFileName = "uploading...") }
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
                if (wasAttached) {
                    val sn = sshClient.currentSessionName
                    sshClient.isAttachedToTmux = true
                    sshClient.sendInput("tmux attach -t '$sn'")
                    kotlinx.coroutines.delay(500)
                    sshClient.sendRawBytes(attachment.remotePath!!.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isUploading = false, uploadFileName = null, error = "Upload failed: ${e.message}")
                }
                if (!sshClient.isAttachedToTmux && sshClient.currentSessionName.isNotEmpty()) {
                    sshClient.isAttachedToTmux = true
                    sshClient.sendInput("tmux attach -t '${sshClient.currentSessionName}'")
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
                // Send recognized text directly to terminal
                sshTerminalSession?.writeInput(recognizedText)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sshTerminalSession?.destroy()
        voiceInputManager?.destroy()
    }
}

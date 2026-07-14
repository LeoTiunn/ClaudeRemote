package com.claude.remote.features.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claude.remote.core.ssh.DebugLog
import com.claude.remote.core.ssh.SshClient
import com.claude.remote.core.tmux.TmuxSessionManager
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
    private val fileUploadManager: FileUploadManager,
    private val tmuxSessionManager: TmuxSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Keep dot in sync with actual SSH connection state
        viewModelScope.launch {
            sshClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        val sessionName = sshClient.currentSessionName
        if (terminalHolder.isSessionRunning() && terminalHolder.attachedSessionName == sessionName) {
            // Same session — reattach existing terminal view
            _uiState.update { it.copy(
                isTerminalMode = true,
                isStreaming = true,
                sessionName = sessionName
            ) }
            terminalHolder.attachExistingSession()
        } else if (sessionName.isNotEmpty()) {
            // New or different session — destroy old and connect fresh
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
            isReconnecting = true,
            sessionName = sessionName,
            statusMessage = "Connecting..."
        ) }

        viewModelScope.launch {
            try {
                // Ensure SSH is connected before opening terminal channel
                if (sshClient.connectionState.value != com.claude.remote.core.ui.components.ConnectionState.CONNECTED) {
                    DebugLog.log("CHAT", "SshClient not connected, connecting first...")
                    _uiState.update { it.copy(statusMessage = "Connecting to SSH...") }
                    sshClient.connect(sshClient.host, sshClient.port, sshClient.username, sshClient.password)
                }

                // Open new shell channel — retry once on failure (handles stale session)
                _uiState.update { it.copy(statusMessage = "Opening terminal...") }
                try {
                    terminalHolder.createSshSession()
                } catch (e: Exception) {
                    DebugLog.log("CHAT", "First createSshSession failed: ${e.message}, retrying with fresh connection")
                    _uiState.update { it.copy(statusMessage = "Reconnecting SSH...") }
                    sshClient.connect(sshClient.host, sshClient.port, sshClient.username, sshClient.password)
                    terminalHolder.createSshSession()
                }

                // Wait for shell prompt, then attach tmux
                _uiState.update { it.copy(statusMessage = "Attaching to $sessionName...") }
                kotlinx.coroutines.delay(300)
                if (terminalHolder.isSessionRunning() && sessionName.isNotEmpty()) {
                    DebugLog.log("CHAT", "Attaching to tmux session: $sessionName")
                    terminalHolder.writeToSession("tmux attach -t '$sessionName' || tmux new-session -s '$sessionName'\r")
                    terminalHolder.attachedSessionName = sessionName
                    sshClient.currentSessionName = sessionName
                    sshClient.isAttachedToTmux = true
                }
                _uiState.update { it.copy(isReconnecting = false, statusMessage = null) }
            } catch (e: Exception) {
                DebugLog.log("CHAT", "SSH connect failed: ${e.message}")
                _uiState.update { it.copy(
                    error = "SSH failed: ${e.message}",
                    isReconnecting = false,
                    statusMessage = null
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

    /** Probe SSH with a quick command + timeout. If dead or hung, reconnect. Called on resume. */
    fun checkConnectionAndReconnect() {
        val sessionName = _uiState.value.sessionName
        if (sessionName.isEmpty()) return

        _uiState.update { it.copy(statusMessage = "Checking connection...") }

        viewModelScope.launch {
            val wasAttached = sshClient.isAttachedToTmux
            sshClient.isAttachedToTmux = false
            try {
                // Probe with 2s timeout — half-dead SSH may hang instead of throwing
                kotlinx.coroutines.withTimeout(2000) {
                    sshClient.executeCommand("echo ok")
                }
                sshClient.isAttachedToTmux = wasAttached
                _uiState.update { it.copy(statusMessage = null) }
                // SSH alive — just redraw terminal
                terminalHolder.terminalView?.invalidate()
                terminalHolder.writeToSession("\u000c") // Ctrl+L
            } catch (_: Exception) {
                sshClient.isAttachedToTmux = wasAttached
                DebugLog.log("CHAT", "SSH probe failed/timed out on resume, reconnecting")
                connectAndAttach(sessionName)
            }
        }
    }

    fun loadAvailableSessions() {
        _uiState.update { it.copy(isLoadingSessions = true) }
        viewModelScope.launch {
            // Command shell is independent of terminal channel — temporarily
            // allow executeCommand while terminal is attached to tmux
            val wasAttached = sshClient.isAttachedToTmux
            sshClient.isAttachedToTmux = false
            try {
                val sessions = tmuxSessionManager.listSessions(sshClient)
                _uiState.update { it.copy(availableSessions = sessions, isLoadingSessions = false) }
            } catch (e: Exception) {
                DebugLog.log("CHAT", "Failed to load sessions: ${e.message}")
                _uiState.update { it.copy(isLoadingSessions = false) }
            } finally {
                sshClient.isAttachedToTmux = wasAttached
            }
        }
    }

    fun switchSession(sessionName: String) {
        if (sessionName == _uiState.value.sessionName) return
        // Clear old content + update UI immediately so user sees the switch
        terminalHolder.clearScreen()
        terminalHolder.attachedSessionName = sessionName
        sshClient.currentSessionName = sessionName
        _uiState.update { it.copy(sessionName = sessionName) }

        val sshAlive = sshClient.connectionState.value == com.claude.remote.core.ui.components.ConnectionState.CONNECTED
        if (sshAlive && terminalHolder.isSessionRunning()) {
            // Fast path: SSH alive, just switch tmux client
            viewModelScope.launch {
                val wasAttached = sshClient.isAttachedToTmux
                sshClient.isAttachedToTmux = false
                try {
                    sshClient.executeCommand("tmux switch-client -t '${sessionName.replace("'", "'\\''")}'")
                    sshClient.isAttachedToTmux = wasAttached
                } catch (_: Exception) {
                    // Command failed — SSH likely dead, full reconnect
                    sshClient.isAttachedToTmux = wasAttached
                    connectAndAttach(sessionName)
                }
            }
        } else {
            // SSH dead or terminal dead — full reconnect
            connectAndAttach(sessionName)
        }
    }

    fun restartCli(allSessions: Boolean) {
        viewModelScope.launch {
            if (!allSessions) {
                // This session only — send /exit then ccx to current terminal
                terminalHolder.writeToSession("/exit\r")
                kotlinx.coroutines.delay(2000)
                terminalHolder.writeToSession("ccx\r")
                return@launch
            }
            // All sessions
            val wasAttached = sshClient.isAttachedToTmux
            sshClient.isAttachedToTmux = false
            try {
                val sessions = tmuxSessionManager.listSessions(sshClient)
                val sessionNames = sessions.map { it.name }
                DebugLog.log("CHAT", "Restart CLI: ${sessionNames.size} sessions")

                for (name in sessionNames) {
                    val escaped = name.replace("'", "'\\''")
                    sshClient.executeCommand("tmux send-keys -t '$escaped' '/exit' Enter")
                }
                kotlinx.coroutines.delay(2000)
                for (name in sessionNames) {
                    val escaped = name.replace("'", "'\\''")
                    sshClient.executeCommand("tmux send-keys -t '$escaped' 'ccx' Enter")
                }
                sshClient.isAttachedToTmux = wasAttached
            } catch (e: Exception) {
                DebugLog.log("CHAT", "Restart CLI failed: ${e.message}")
                _uiState.update { it.copy(error = "Restart CLI failed: ${e.message}") }
                sshClient.isAttachedToTmux = wasAttached
            }
        }
    }

    fun refreshToken(allSessions: Boolean) {
        viewModelScope.launch {
            if (!allSessions) {
                // This session only — /exit then ca refresh && ccx in current terminal
                terminalHolder.writeToSession("/exit\r")
                kotlinx.coroutines.delay(2000)
                terminalHolder.writeToSession("ca refresh && ccx\r")
                return@launch
            }
            // All sessions
            val wasAttached = sshClient.isAttachedToTmux
            sshClient.isAttachedToTmux = false
            try {
                val sessions = tmuxSessionManager.listSessions(sshClient)
                val sessionNames = sessions.map { it.name }
                DebugLog.log("CHAT", "Refresh token: ${sessionNames.size} sessions to restart")

                for (name in sessionNames) {
                    val escaped = name.replace("'", "'\\''")
                    sshClient.executeCommand("tmux send-keys -t '$escaped' '/exit' Enter")
                }
                kotlinx.coroutines.delay(2000)

                val restartChain = sessionNames.joinToString(" && ") { name ->
                    val escaped = name.replace("'", "'\\''")
                    "tmux send-keys -t '$escaped' 'ccx' Enter"
                }
                val fullCommand = "ca refresh && $restartChain"
                DebugLog.log("CHAT", "Sending refresh chain to terminal: $fullCommand")

                sshClient.isAttachedToTmux = wasAttached
                terminalHolder.writeToSession(fullCommand + "\r")
            } catch (e: Exception) {
                DebugLog.log("CHAT", "Refresh token failed: ${e.message}")
                _uiState.update { it.copy(error = "Refresh token failed: ${e.message}") }
                sshClient.isAttachedToTmux = wasAttached
            }
        }
    }

    fun killSession(sessionName: String) {
        viewModelScope.launch {
            val wasAttached = sshClient.isAttachedToTmux
            sshClient.isAttachedToTmux = false
            try {
                sshClient.executeCommand("tmux kill-session -t '${sessionName.replace("'", "'\\''")}'")
            } catch (_: Exception) {}
            sshClient.isAttachedToTmux = wasAttached
        }
    }

    fun sendRawEscape(sequence: String) {
        terminalHolder.writeBytes(sequence.toByteArray(Charsets.UTF_8))
    }

    /**
     * Send terminal input, but first snap tmux back to the live bottom. When the
     * user has scrolled up, tmux is in copy-mode and would EAT the keystrokes as
     * copy-mode commands (so the typed text vanishes and never reaches Claude).
     * `send-keys -X cancel` exits copy-mode over the command channel (no-op when
     * not in copy-mode, so it's safe either way and injects nothing literal).
     */
    fun sendInput(text: String) {
        val sessionName = _uiState.value.sessionName
        viewModelScope.launch {
            if (sessionName.isNotEmpty()) {
                val escaped = sessionName.replace("'", "'\\''")
                val wasAttached = sshClient.isAttachedToTmux
                sshClient.isAttachedToTmux = false
                try {
                    sshClient.executeCommand("tmux send-keys -t '$escaped' -X cancel 2>/dev/null || true")
                } catch (_: Exception) {}
                sshClient.isAttachedToTmux = wasAttached
            }
            terminalHolder.writeBytes(text.toByteArray(Charsets.UTF_8))
        }
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
        // Upload over the command channel (independent of the tmux PTY — no detach
        // needed). The command channel is gated by isAttachedToTmux, so bypass it
        // during upload, then paste the resulting remote path into the terminal.
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, uploadFileName = "uploading...", statusMessage = "Uploading file...") }
            val wasAttached = sshClient.isAttachedToTmux
            sshClient.isAttachedToTmux = false
            try {
                val attachment = fileUploadManager.uploadFile(uri, sshClient)
                sshClient.isAttachedToTmux = wasAttached
                _uiState.update { it.copy(isUploading = false, uploadFileName = null, statusMessage = null) }
                // Type the path into the terminal (trailing space, no newline) so the
                // user can keep typing their prompt around the attached file path.
                attachment.remotePath?.let {
                    terminalHolder.writeBytes(("$it ").toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                sshClient.isAttachedToTmux = wasAttached
                _uiState.update {
                    it.copy(isUploading = false, uploadFileName = null, statusMessage = null, error = "Upload failed: ${e.message}")
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

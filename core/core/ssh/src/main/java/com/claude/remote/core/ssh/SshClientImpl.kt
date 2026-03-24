package com.claude.remote.core.ssh

import com.claude.remote.core.ui.components.ConnectionState
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshClientImpl @Inject constructor() : SshClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _outputFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val outputStream: Flow<String> = _outputFlow

    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellOutputStream: OutputStream? = null
    @Volatile private var shellReaderGeneration: Int = 0

    @Volatile override var host: String = ""
        private set
    @Volatile override var port: Int = 22
        private set
    @Volatile override var username: String = ""
        private set
    @Volatile override var password: String = ""
        private set
    @Volatile override var isAttachedToTmux: Boolean = false
    @Volatile override var currentSessionName: String = ""

    // For shell-based command execution
    private val execMutex = Mutex()
    @Volatile private var pendingCommand: PendingCommand? = null

    private class PendingCommand(
        val marker: String,
        val deferred: CompletableDeferred<String>,
        val outputBuffer: StringBuilder = StringBuilder()
    ) {
        var startCount = 0  // Track how many times we see start marker (1st=echo, 2nd=real)
        var capturing = false
    }

    override suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String
    ) {
        withContext(Dispatchers.IO) {
            this@SshClientImpl.host = host
            this@SshClientImpl.port = port
            this@SshClientImpl.username = username
            this@SshClientImpl.password = password

            // Clean up any existing connection first
            shellReaderGeneration++  // Invalidate old reader before disconnect
            try {
                shellOutputStream = null
                shellChannel?.disconnect()
                jschSession?.disconnect()
            } catch (_: Exception) {}
            shellChannel = null
            jschSession = null
            pendingCommand?.deferred?.complete("")
            pendingCommand = null
            isAttachedToTmux = false

            _connectionState.value = ConnectionState.CONNECTING
            DebugLog.log("SSH", "Connecting to $host:$port as $username")

            try {
                val addr = java.net.InetAddress.getAllByName(host)
                    .firstOrNull { it is java.net.Inet4Address }
                    ?: java.net.InetAddress.getByName(host)
                val resolvedHost = addr.hostAddress ?: host
                DebugLog.log("SSH", "Resolved to $resolvedHost (IPv4)")

                val jsch = JSch()
                val session = jsch.getSession(username, resolvedHost, port)
                session.setPassword(password)

                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                session.setConfig(config)
                session.timeout = 0  // No socket timeout — rely on keep-alive
                session.setServerAliveInterval(15_000)
                session.setServerAliveCountMax(3)

                DebugLog.log("SSH", "Calling session.connect()...")
                session.connect(15_000)
                DebugLog.log("SSH", "Session connected: ${session.isConnected}")
                this@SshClientImpl.jschSession = session

                val channel = session.openChannel("shell") as ChannelShell
                // Start with phone-sized terminal; will be resized by TerminalView
                channel.setPtyType("xterm-256color", 52, 40, 0, 0)

                val shellIn = channel.inputStream
                this@SshClientImpl.shellOutputStream = channel.outputStream

                DebugLog.log("SSH", "Opening shell channel...")
                channel.connect(10_000)
                DebugLog.log("SSH", "Shell channel connected: ${channel.isConnected}")
                this@SshClientImpl.shellChannel = channel

                _connectionState.value = ConnectionState.CONNECTED
                DebugLog.log("SSH", "State -> CONNECTED")

                // Background reader for shell output
                startShellReader(channel, shellIn, shellReaderGeneration)
            } catch (e: Exception) {
                DebugLog.log("SSH", "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
                throw e
            }
        }
    }

    private fun startShellReader(channel: ChannelShell, shellIn: java.io.InputStream, generation: Int) {
        scope.launch {
            DebugLog.log("SHELL", "Reader started (gen=$generation)")
            try {
                val buffer = ByteArray(4096)
                while (isActive && channel.isConnected && !channel.isClosed) {
                    val len = shellIn.read(buffer)
                    if (len == -1) {
                        DebugLog.log("SHELL", "read() returned -1, EOF")
                        break
                    }
                    if (len > 0) {
                        val text = String(buffer, 0, len, Charsets.UTF_8)
                        DebugLog.log("SHELL", "Read ${len}b: ${text.take(120)}")
                        processShellOutput(text)
                    }
                }
                DebugLog.log("SHELL", "Reader loop exited. connected=${channel.isConnected} closed=${channel.isClosed}")
            } catch (e: Exception) {
                DebugLog.log("SHELL", "Reader exception: ${e.javaClass.simpleName}: ${e.message}")
            }
            // Only reconnect if THIS reader is still the current one
            if (generation != shellReaderGeneration) {
                DebugLog.log("SHELL", "Stale reader (gen=$generation, current=${shellReaderGeneration}), skipping reconnect")
                return@launch
            }
            if (_connectionState.value == ConnectionState.CONNECTED) {
                DebugLog.log("SHELL", "Shell died, triggering reconnect")
                pendingCommand?.deferred?.complete("")
                pendingCommand = null
                _connectionState.value = ConnectionState.DISCONNECTED
                attemptReconnect()
            }
        }
    }

    private suspend fun processShellOutput(text: String) {
        // If attached to tmux, ALWAYS emit to UI — never block output
        if (isAttachedToTmux) {
            _outputFlow.emit(text)
            return
        }

        val pending = pendingCommand
        if (pending != null) {
            val startMarker = "START_${pending.marker}"
            val endMarker = "END_${pending.marker}"

            // Accumulate all text
            pending.outputBuffer.append(text)
            val content = pending.outputBuffer.toString()

            if (!pending.capturing) {
                // Find the real start marker (not from echo).
                // Echo: echo 'START_CMD_xxx' — marker preceded by single quote
                // Real: ...PreExecSTART_CMD_xxx\n — marker NOT preceded by quote
                var searchFrom = 0
                while (true) {
                    val idx = content.indexOf(startMarker, searchFrom)
                    if (idx < 0) break
                    if (idx > 0 && content[idx - 1] == '\'') {
                        // This is from echo (echo 'START_CMD_xxx'), skip it
                        searchFrom = idx + startMarker.length
                        continue
                    }
                    // Real start marker found
                    pending.capturing = true
                    val afterStart = content.substring(idx + startMarker.length).trimStart('\n')
                    pending.outputBuffer.clear()
                    pending.outputBuffer.append(afterStart)
                    DebugLog.log("EXEC", "Found real START marker")
                    break
                }
            }

            if (pending.capturing) {
                val currentContent = pending.outputBuffer.toString()
                // Find end marker NOT preceded by quote
                var searchFrom = 0
                while (true) {
                    val idx = currentContent.indexOf(endMarker, searchFrom)
                    if (idx < 0) break
                    if (idx > 0 && currentContent[idx - 1] == '\'') {
                        searchFrom = idx + endMarker.length
                        continue
                    }
                    // Real end marker found
                    val result = stripAnsi(currentContent.substring(0, idx).trim())
                    DebugLog.log("EXEC", "Captured result(${result.length}): ${result.take(200)}")
                    pending.deferred.complete(result)
                    return
                }
            }

            // Don't emit to outputFlow while waiting for command result
            return
        }

        // Normal shell output — emit to UI
        _outputFlow.emit(text)
    }

    private fun attemptReconnect() {
        if (host.isBlank()) return
        scope.launch {
            for (attempt in 1..5) {
                DebugLog.log("SSH", "Reconnect attempt $attempt/5")
                delay(attempt * 3000L)
                try {
                    connect(host, port, username, password)
                    return@launch
                } catch (e: Exception) {
                    DebugLog.log("SSH", "Reconnect $attempt failed: ${e.message}")
                }
            }
            DebugLog.log("SSH", "All reconnect attempts exhausted")
        }
    }

    override suspend fun reconnect() {
        if (host.isBlank() || password.isBlank()) throw IllegalStateException("No saved credentials")
        connect(host, port, username, password)
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            DebugLog.log("SSH", "Disconnecting")
            _connectionState.value = ConnectionState.DISCONNECTED
            shellOutputStream = null
            try { shellChannel?.disconnect() } catch (_: Exception) {}
            try { jschSession?.disconnect() } catch (_: Exception) {}
            shellChannel = null
            jschSession = null
        }
    }

    override suspend fun executeCommand(command: String): String {
        DebugLog.log("EXEC", "Command: ${command.take(80)}")

        if (isAttachedToTmux) {
            DebugLog.log("EXEC", "Skipping — attached to tmux")
            return ""
        }

        if (shellOutputStream == null) {
            throw IllegalStateException("Not connected (no shell)")
        }

        return execMutex.withLock {
            val marker = "CMD_${System.nanoTime()}"
            val startMarker = "START_$marker"
            val endMarker = "END_$marker"
            val deferred = CompletableDeferred<String>()
            val pending = PendingCommand(marker, deferred)
            pendingCommand = pending

            DebugLog.log("EXEC", "Sending via shell with marker $marker")

            withContext(Dispatchers.IO) {
                shellOutputStream?.let { stream ->
                    val wrappedCommand = "echo '$startMarker'; $command; echo '$endMarker'\n"
                    stream.write(wrappedCommand.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: throw IllegalStateException("Shell disconnected")
            }

            // Wait for result with timeout
            val result = withTimeoutOrNull(30_000L) {
                deferred.await()
            }

            pendingCommand = null

            if (result == null) {
                DebugLog.log("EXEC", "TIMEOUT after 30s")
                ""
            } else {
                // Strip ANSI escape sequences from result
                val cleaned = stripAnsi(result)
                DebugLog.log("EXEC", "Result(${cleaned.length}): ${cleaned.take(200)}")
                cleaned
            }
        }
    }

    private fun stripAnsi(text: String): String {
        // Remove ANSI escape sequences, OSC sequences, and control chars
        return text
            .replace(Regex("\\x1b\\[[0-9;]*[a-zA-Z]"), "")  // CSI sequences
            .replace(Regex("\\x1b\\][^\u0007]*[\u0007\u001b\\\\]"), "")  // OSC sequences
            .replace(Regex("\\x1b\\[\\?[0-9]+[hl]"), "")  // DEC private mode
            .replace(Regex("[\\x00-\\x08\\x0e-\\x1f]"), "")  // control chars except \t \n \r
    }

    override suspend fun openShellChannel(cols: Int, rows: Int): ShellChannelHandle {
        return withContext(Dispatchers.IO) {
            val session = jschSession ?: throw IllegalStateException("SSH not connected")
            DebugLog.log("SSH", "Opening new shell channel on existing session")

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color", cols, rows, 0, 0)

            val input = channel.inputStream
            val output = channel.outputStream

            channel.connect(10_000)
            DebugLog.log("SSH", "New shell channel connected: ${channel.isConnected}")

            ShellChannelHandle(
                inputStream = input,
                outputStream = output,
                resizePty = { c, r ->
                    try {
                        channel.setPtySize(c, r, c * 8, r * 16)
                    } catch (e: Exception) {
                        DebugLog.log("SSH", "PTY resize failed: ${e.message}")
                    }
                },
                disconnect = {
                    try { channel.disconnect() } catch (_: Exception) {}
                }
            )
        }
    }

    override fun resizePty(cols: Int, rows: Int) {
        DebugLog.log("SSH", "Resizing PTY to ${cols}x${rows}")
        try {
            shellChannel?.setPtySize(cols, rows, cols * 8, rows * 16)
        } catch (e: Exception) {
            DebugLog.log("SSH", "PTY resize failed: ${e.message}")
        }
    }

    override suspend fun sendRawBytes(data: ByteArray) {
        withContext(Dispatchers.IO) {
            shellOutputStream?.let { stream ->
                stream.write(data)
                stream.flush()
            }
        }
    }

    override suspend fun sendInput(input: String) {
        DebugLog.log("INPUT", "Sending: ${input.take(50)}")
        withContext(Dispatchers.IO) {
            shellOutputStream?.let { stream ->
                // Use \r (carriage return) for Enter — required when attached to tmux PTY
                val suffix = if (isAttachedToTmux) "\r" else "\n"
                stream.write("$input$suffix".toByteArray(Charsets.UTF_8))
                stream.flush()
                DebugLog.log("INPUT", "Sent OK (suffix=${if (isAttachedToTmux) "CR" else "LF"})")
            } ?: DebugLog.log("INPUT", "ERROR: shellOutputStream is null")
        }
    }
}

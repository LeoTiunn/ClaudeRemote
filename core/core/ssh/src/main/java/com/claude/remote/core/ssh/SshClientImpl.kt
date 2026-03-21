package com.claude.remote.core.ssh

import com.claude.remote.core.ui.components.ConnectionState
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
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
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshClientImpl @Inject constructor() : SshClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _outputFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    override val outputStream: Flow<String> = _outputFlow

    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellOutputStream: OutputStream? = null

    @Volatile private var host: String = ""
    @Volatile private var port: Int = 22
    @Volatile private var username: String = ""
    @Volatile private var password: String = ""

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
                session.timeout = 15_000
                session.setServerAliveInterval(15_000)
                session.setServerAliveCountMax(3)

                DebugLog.log("SSH", "Calling session.connect()...")
                session.connect(15_000)
                DebugLog.log("SSH", "Session connected: ${session.isConnected}")
                this@SshClientImpl.jschSession = session

                val channel = session.openChannel("shell") as ChannelShell
                channel.setPtyType("xterm-256color", 120, 40, 0, 0)

                val shellIn = channel.inputStream
                this@SshClientImpl.shellOutputStream = channel.outputStream

                DebugLog.log("SSH", "Opening shell channel...")
                channel.connect(10_000)
                DebugLog.log("SSH", "Shell channel connected: ${channel.isConnected}")
                this@SshClientImpl.shellChannel = channel

                _connectionState.value = ConnectionState.CONNECTED
                DebugLog.log("SSH", "State -> CONNECTED")

                // Background reader for shell output
                scope.launch {
                    DebugLog.log("SHELL", "Reader started")
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
                                DebugLog.log("SHELL", "Read ${len}b: ${text.take(100)}")
                                _outputFlow.emit(text)
                            }
                        }
                        DebugLog.log("SHELL", "Reader loop exited. connected=${channel.isConnected} closed=${channel.isClosed}")
                    } catch (e: Exception) {
                        DebugLog.log("SHELL", "Reader exception: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    // Only reconnect if the SSH session itself is dead
                    val sess = jschSession
                    if (sess == null || !sess.isConnected) {
                        if (_connectionState.value == ConnectionState.CONNECTED) {
                            DebugLog.log("SHELL", "Session dead, triggering reconnect")
                            _connectionState.value = ConnectionState.DISCONNECTED
                            attemptReconnect()
                        }
                    } else {
                        DebugLog.log("SHELL", "Shell closed but session alive, reopening shell")
                        // Reopen the shell channel
                        try {
                            val newChannel = sess.openChannel("shell") as ChannelShell
                            newChannel.setPtyType("xterm-256color", 120, 40, 0, 0)
                            val newShellIn = newChannel.inputStream
                            this@SshClientImpl.shellOutputStream = newChannel.outputStream
                            newChannel.connect(10_000)
                            this@SshClientImpl.shellChannel = newChannel
                            DebugLog.log("SHELL", "Shell reopened successfully")
                            // Restart reader for new channel
                            scope.launch {
                                try {
                                    val buf = ByteArray(4096)
                                    while (isActive && newChannel.isConnected && !newChannel.isClosed) {
                                        val l = newShellIn.read(buf)
                                        if (l == -1) break
                                        if (l > 0) {
                                            _outputFlow.emit(String(buf, 0, l, Charsets.UTF_8))
                                        }
                                    }
                                } catch (_: Exception) {}
                                DebugLog.log("SHELL", "Reopened shell reader exited")
                            }
                        } catch (e: Exception) {
                            DebugLog.log("SHELL", "Failed to reopen shell: ${e.message}")
                            _connectionState.value = ConnectionState.DISCONNECTED
                            attemptReconnect()
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("SSH", "Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
                throw e
            }
        }
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

    override suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        DebugLog.log("EXEC", "Command: ${command.take(80)}")
        val session = jschSession
        if (session == null || !session.isConnected) {
            DebugLog.log("EXEC", "ERROR: session null=${session == null} connected=${session?.isConnected}")
            throw IllegalStateException("Not connected (session=${session != null}, connected=${session?.isConnected})")
        }

        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)

        val inputStream = channel.inputStream
        val errStream = channel.errStream

        DebugLog.log("EXEC", "Connecting exec channel...")
        channel.connect(10_000)
        DebugLog.log("EXEC", "Exec channel connected")

        val output = StringBuilder()
        val errOutput = StringBuilder()
        val buffer = ByteArray(4096)

        try {
            var iterations = 0
            while (true) {
                iterations++
                while (inputStream.available() > 0) {
                    val len = inputStream.read(buffer)
                    if (len < 0) break
                    output.append(String(buffer, 0, len, Charsets.UTF_8))
                }
                while (errStream.available() > 0) {
                    val len = errStream.read(buffer)
                    if (len < 0) break
                    errOutput.append(String(buffer, 0, len, Charsets.UTF_8))
                }
                if (channel.isClosed) {
                    if (inputStream.available() > 0 || errStream.available() > 0) continue
                    break
                }
                if (iterations > 600) { // 30 second timeout (600 * 50ms)
                    DebugLog.log("EXEC", "TIMEOUT after 30s")
                    break
                }
                Thread.sleep(50)
            }
            DebugLog.log("EXEC", "Done in $iterations iterations, exit=${channel.exitStatus}")
            DebugLog.log("EXEC", "stdout(${output.length}): ${output.toString().take(200)}")
            if (errOutput.isNotEmpty()) {
                DebugLog.log("EXEC", "stderr(${errOutput.length}): ${errOutput.toString().take(200)}")
                output.append(errOutput)
            }
        } finally {
            channel.disconnect()
        }

        output.toString().trim()
    }

    override fun sendInput(input: String) {
        DebugLog.log("INPUT", "Sending: ${input.take(50)}")
        shellOutputStream?.let { stream ->
            stream.write("$input\n".toByteArray(Charsets.UTF_8))
            stream.flush()
            DebugLog.log("INPUT", "Sent OK")
        } ?: DebugLog.log("INPUT", "ERROR: shellOutputStream is null")
    }
}

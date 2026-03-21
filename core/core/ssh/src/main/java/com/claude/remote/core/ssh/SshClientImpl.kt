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

            try {
                // Resolve hostname to IPv4 to avoid IPv6 connection issues
                val addr = java.net.InetAddress.getAllByName(host)
                    .firstOrNull { it is java.net.Inet4Address }
                    ?: java.net.InetAddress.getByName(host)
                val resolvedHost = addr.hostAddress ?: host

                val jsch = JSch()
                val session = jsch.getSession(username, resolvedHost, port)
                session.setPassword(password)

                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                session.setConfig(config)
                session.timeout = 15_000
                session.setServerAliveInterval(15_000)
                session.setServerAliveCountMax(3)

                session.connect(15_000)
                this@SshClientImpl.jschSession = session

                val channel = session.openChannel("shell") as ChannelShell
                channel.setPtyType("xterm-256color", 120, 40, 0, 0)

                val shellIn = channel.inputStream
                this@SshClientImpl.shellOutputStream = channel.outputStream

                channel.connect(10_000)
                this@SshClientImpl.shellChannel = channel

                _connectionState.value = ConnectionState.CONNECTED

                // Background reader for shell output
                scope.launch {
                    try {
                        val buffer = ByteArray(4096)
                        while (isActive && channel.isConnected && !channel.isClosed) {
                            val len = shellIn.read(buffer)
                            if (len == -1) break
                            if (len > 0) {
                                _outputFlow.emit(String(buffer, 0, len, Charsets.UTF_8))
                            }
                        }
                    } catch (_: Exception) {
                        // Stream closed
                    }
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        attemptReconnect()
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.DISCONNECTED
                throw e
            }
        }
    }

    private fun attemptReconnect() {
        if (host.isBlank()) return
        scope.launch {
            for (attempt in 1..5) {
                delay(attempt * 3000L)
                try {
                    connect(host, port, username, password)
                    return@launch
                } catch (_: Exception) {
                    // Retry
                }
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            _connectionState.value = ConnectionState.DISCONNECTED
            shellOutputStream = null
            try { shellChannel?.disconnect() } catch (_: Exception) {}
            try { jschSession?.disconnect() } catch (_: Exception) {}
            shellChannel = null
            jschSession = null
        }
    }

    override suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val session = jschSession ?: throw IllegalStateException("Not connected")
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)

        val inputStream = channel.inputStream
        val errStream = channel.errStream

        channel.connect(10_000)

        val output = StringBuilder()
        val buffer = ByteArray(4096)

        try {
            // Standard JSch read pattern: use blocking read + isClosed check
            while (true) {
                while (inputStream.available() > 0) {
                    val len = inputStream.read(buffer)
                    if (len < 0) break
                    output.append(String(buffer, 0, len, Charsets.UTF_8))
                }
                while (errStream.available() > 0) {
                    val len = errStream.read(buffer)
                    if (len < 0) break
                    output.append(String(buffer, 0, len, Charsets.UTF_8))
                }
                if (channel.isClosed) {
                    if (inputStream.available() > 0 || errStream.available() > 0) continue
                    break
                }
                Thread.sleep(50)
            }
        } finally {
            channel.disconnect()
        }

        output.toString().trim()
    }

    override fun sendInput(input: String) {
        shellOutputStream?.let { stream ->
            stream.write("$input\n".toByteArray(Charsets.UTF_8))
            stream.flush()
        }
    }
}

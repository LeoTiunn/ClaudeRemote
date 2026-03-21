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
import java.io.InputStream
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
    ) = withContext(Dispatchers.IO) {
        this@SshClientImpl.host = host
        this@SshClientImpl.port = port
        this@SshClientImpl.username = username
        this@SshClientImpl.password = password

        _connectionState.value = ConnectionState.CONNECTING

        try {
            val jsch = JSch()
            val session = jsch.getSession(username, host, port)
            session.setPassword(password)

            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            session.setConfig(config)
            session.timeout = 15_000
            session.connect(15_000)

            this@SshClientImpl.jschSession = session

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color", 120, 40, 0, 0)

            val inputStream: InputStream = channel.inputStream
            this@SshClientImpl.shellOutputStream = channel.outputStream

            channel.connect(10_000)
            this@SshClientImpl.shellChannel = channel

            // Read shell output in background
            scope.launch {
                try {
                    val buffer = ByteArray(4096)
                    while (isActive && channel.isConnected) {
                        val available = inputStream.available()
                        if (available > 0) {
                            val len = inputStream.read(buffer, 0, minOf(available, buffer.size))
                            if (len > 0) {
                                _outputFlow.emit(String(buffer, 0, len, Charsets.UTF_8))
                            }
                        } else {
                            delay(50)
                        }
                    }
                } catch (e: Exception) {
                    // Stream closed
                }
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    attemptReconnect()
                }
            }

            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            throw e
        }
    }

    private fun attemptReconnect() {
        if (host.isBlank()) return
        scope.launch {
            for (attempt in 1..5) {
                delay(attempt * 2000L)
                try {
                    connect(host, port, username, password)
                    return@launch
                } catch (_: Exception) {
                    // Retry
                }
            }
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        shellOutputStream = null
        try { shellChannel?.disconnect() } catch (_: Exception) {}
        try { jschSession?.disconnect() } catch (_: Exception) {}
        shellChannel = null
        jschSession = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val session = jschSession ?: throw IllegalStateException("Not connected")
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.inputStream = null

        val output = StringBuilder()
        val inputStream = channel.inputStream

        try {
            channel.connect(10_000)
            val buffer = ByteArray(4096)
            while (true) {
                val len = inputStream.read(buffer)
                if (len == -1) break
                if (len > 0) {
                    output.append(String(buffer, 0, len, Charsets.UTF_8))
                }
                if (channel.isClosed) break
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

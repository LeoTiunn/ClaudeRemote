package com.claude.remote.core.ssh

import com.claude.remote.core.ui.components.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshClientImpl @Inject constructor() : SshClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _outputFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    override val outputStream: Flow<String> = _outputFlow

    private var client: org.apache.sshd.client.SshClient? = null
    private var session: ClientSession? = null
    private var channel: ClientChannel? = null
    private var channelInput: OutputStream? = null

    // Stored credentials for auto-reconnect
    @Volatile private var host: String = ""
    @Volatile private var port: Int = 22
    @Volatile private var username: String = ""
    @Volatile private var password: String = ""

    override suspend fun connect(host: String, port: Int, username: String, password: String) {
        // Store credentials for reconnect
        this.host = host
        this.port = port
        this.username = username
        this.password = password

        _connectionState.value = ConnectionState.CONNECTING

        try {
            val sshClient = org.apache.sshd.client.SshClient.setUpDefaultClient().apply { start() }
            this.client = sshClient

            val sess = sshClient.connect(username, host, port).verify(10_000).session
            sess.addPasswordIdentity(password)
            sess.auth().verify(10_000)
            this.session = sess

            val ch = sess.createShellChannel()
            ch.open().verify(10_000)
            this.channel = ch

            // invertedIn is the OutputStream we write to, which feeds the channel's stdin
            this.channelInput = ch.invertedIn

            // Read output from the channel's stdout in background
            scope.launch {
                try {
                    val buffer = ByteArray(4096)
                    val input: InputStream = ch.invertedOut
                    while (true) {
                        val len = input.read(buffer)
                        if (len == -1) break
                        if (len > 0) {
                            _outputFlow.emit(String(buffer, 0, len, Charsets.UTF_8))
                        }
                    }
                } catch (e: Exception) {
                    // Stream ended or error
                }
                // Connection dropped — attempt auto-reconnect
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    attemptReconnect()
                }
            }

            // Also read stderr in background
            scope.launch {
                try {
                    val buffer = ByteArray(4096)
                    val errInput: InputStream = ch.invertedErr
                    while (true) {
                        val len = errInput.read(buffer)
                        if (len == -1) break
                        if (len > 0) {
                            _outputFlow.emit(String(buffer, 0, len, Charsets.UTF_8))
                        }
                    }
                } catch (_: Exception) {
                    // Stream ended
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

    override suspend fun disconnect() {
        channelInput = null
        channel?.close()
        session?.close()
        client?.stop()
        client?.close()
        channel = null
        session = null
        client = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun executeCommand(command: String): String {
        val sess = session ?: throw IllegalStateException("Not connected")
        val execChannel = sess.createExecChannel(command)
        val output = StringBuilder()

        try {
            execChannel.open().verify(10_000)

            // Read all output from the exec channel
            val buffer = ByteArray(4096)
            val input: InputStream = execChannel.invertedOut
            while (true) {
                val len = input.read(buffer)
                if (len == -1) break
                if (len > 0) {
                    output.append(String(buffer, 0, len, Charsets.UTF_8))
                }
            }

            execChannel.waitFor(
                setOf(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS),
                30_000
            )
        } finally {
            execChannel.close()
        }

        return output.toString().trim()
    }

    override fun sendInput(input: String) {
        channelInput?.let { stream ->
            stream.write("$input\n".toByteArray(Charsets.UTF_8))
            stream.flush()
        }
    }
}

package com.claude.remote.core.ssh

import com.claude.remote.core.ui.components.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import java.io.PipedInputStream
import java.io.PipedOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshClientImpl @Inject constructor() : SshClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _outputFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    override val outputStream: Flow<String> = _outputFlow

    private var client: SshClient? = null
    private var session: ClientSession? = null
    private var channel: ClientChannel? = null

    @Volatile
    private var outputWriter: PipedOutputStream? = null

    override suspend fun connect(host: String, port: Int, username: String, password: String) {
        _connectionState.value = ConnectionState.CONNECTING

        try {
            client = SshClient.setUpDefaultClient().apply { start() }
            session = client?.connect(username, host, port)?.verify(10_000)?.session?.apply {
                addPasswordIdentity(password)
                auth().verify(10_000)
            }

            val pipedOut = PipedOutputStream()
            outputWriter = pipedOut
            val inputStream = PipedInputStream(pipedOut)

            channel = session?.createChannel(ClientChannel.CHANNEL_SHELL).apply {
                setIn(inputStream)
                setOut(pipedOut)
                setErr(pipedOut)
                open()

                // Stream output in background
                scope.launch {
                    val buffer = ByteArray(4096)
                    try {
                        while (true) {
                            val len = pipedOut.read(buffer)
                            if (len > 0) {
                                _outputFlow.emit(String(buffer, 0, len, Charsets.UTF_8))
                            }
                        }
                    } catch (e: Exception) {
                        // Stream closed
                    }
                }
            }

            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            throw e
        }
    }

    override suspend fun disconnect() {
        channel?.close()
        session?.close()
        client?.stop()
        client?.close()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun executeCommand(command: String): String {
        val channel = session?.createExecChannel(command)
        val output = StringBuilder()

        channel?.let {
            it.open()
            val buffer = ByteArray(1024)
            val outStream = it.invertedEnv?.getOutputStream()
            val inStream = it.invertedEnv?.getInputStream()

            outStream?.write("$command\n".toByteArray())
            outStream?.flush()
            outStream?.close()

            val events = it.waitFor(
                setOf(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS),
                30_000
            )

            inStream?.use { input ->
                val len = input.read(buffer)
                if (len > 0) {
                    output.append(String(buffer, 0, len, Charsets.UTF_8))
                }
            }

            if (events.contains(ClientChannelEvent.EXIT_STATUS)) {
                return output.toString().trim() + "\nexit: ${it.exitStatus}"
            }
        }

        return output.toString().trim()
    }

    override fun sendInput(input: String) {
        val outputStream = channel?.invertedEnv?.getOutputStream()
        outputStream?.write("$input\n".toByteArray())
        outputStream?.flush()
    }
}
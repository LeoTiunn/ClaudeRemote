package com.claude.remote.core.ssh

import app.cash.turbine.test
import com.claude.remote.core.ui.components.ConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class SshClientTest {

    @Test
    fun `initial state is DISCONNECTED`() = runTest {
        val client = SshClientImpl()

        client.connectionState.test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
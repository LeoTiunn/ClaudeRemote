package com.claude.remote.core.ssh

import com.claude.remote.core.ui.components.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SshClientTest {

    @Test
    fun `initial state is DISCONNECTED`() {
        val client = SshClientImpl()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
    }
}

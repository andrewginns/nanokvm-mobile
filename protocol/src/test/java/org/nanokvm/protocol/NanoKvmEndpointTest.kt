package org.nanokvm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NanoKvmEndpointTest {
    @Test
    fun `scheme defaults to HTTPS and origin is normalized`() {
        val endpoint = NanoKvmEndpoint.parse(" 192.0.2.250 ")

        assertEquals("https://192.0.2.250", endpoint.toString())
        assertEquals("192.0.2.250:443", endpoint.authorityKey)
        assertEquals("wss://192.0.2.250/api/ws", endpoint.webSocketUrl("/api/ws"))
    }

    @Test
    fun `explicit port is retained`() {
        val endpoint = NanoKvmEndpoint.parse("http://nanokvm.local:8080/")

        assertEquals("http://nanokvm.local:8080", endpoint.toString())
        assertEquals("http://nanokvm.local:8080/api/vm/info", endpoint.apiUrl("api/vm/info").toString())
    }

    @Test
    fun `credentials paths queries and unsupported schemes are rejected`() {
        listOf(
            "https://user:pass@nanokvm.local",
            "https://nanokvm.local/admin",
            "https://nanokvm.local?token=nope",
            "ftp://nanokvm.local",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                NanoKvmEndpoint.parse(value)
            }
        }
    }
}

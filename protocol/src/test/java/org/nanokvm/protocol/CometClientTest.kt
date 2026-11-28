package org.nanokvm.protocol

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class CometClientTest {
    private val leaf = HeldCertificate.Builder().commonName("localhost").build()

    private fun fixture(block: (MockWebServer, NanoKvmEndpoint) -> Unit) {
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf).build().sslSocketFactory(), false)
            server.start()
            block(server, NanoKvmEndpoint.parse("https://127.0.0.1:" + server.port))
        }
    }

    @Test fun reviewedLeafAllowsStockNameAndBindsCookieToBothSocketHandshakes() = fixture { server, endpoint ->
        CometClient(endpoint, CertificateFingerprint.from(leaf.certificate)).use { client ->
            for (code in listOf("", "012345")) {
                server.enqueue(MockResponse().addHeader("Set-Cookie", "auth_token=fixture; Path=/; Secure"))
                client.login("admin", "space &".toCharArray(), code.toCharArray())
                val login = server.takeRequest()
                assertEquals("/api/auth/login", login.path)
                assertEquals("POST", login.method)
                assertEquals("user=admin&passwd=space+%26" + code, login.body.readUtf8())
                assertNull(login.getHeader("Cookie"))
            }
            for (media in listOf(false, true)) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                    }
                }))
                val socket = client.openSocket(media, object : WebSocketListener() {})
                val handshake = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals(if (media) "/api/media/ws" else "/api/ws", handshake.path)
                assertEquals("auth_token=fixture", handshake.getHeader("Cookie"))
                assertEquals(endpoint.toString(), handshake.getHeader("Origin"))
                socket.close(1000, null)
            }
            server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(403, assertThrows(CometHttpFailure::class.java) { client.checkSession() }.status)
        }
    }

    @Test fun refusesWrongPinRedirectAndThrottledLogin() = fixture { server, endpoint ->
        val wrong = CertificateFingerprint.sha256OfDer(byteArrayOf(1))
        CometClient(endpoint, wrong).use { client ->
            assertThrows(IOException::class.java) { client.login("admin", charArrayOf('x'), charArrayOf()) }
            assertEquals(0, server.requestCount)
        }
        CometClient(endpoint, CertificateFingerprint.from(leaf.certificate)).use { client ->
            for (code in listOf(302, 403, 429)) {
                server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", "/unexpected"))
                assertEquals(code, assertThrows(CometHttpFailure::class.java) {
                    client.login("admin", charArrayOf('x'), charArrayOf())
                }.status)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun hidUsesSignedAbsoluteEndpointsAndIndependentWheelAxes() {
        assertEquals("0380007fff", CometHid.absolute(0, 32767).hex())
        assertEquals("037fff8000", CometHid.absolute(32767, 0).hex())
        assertEquals("0500ff01", CometHid.delta(true, -1, 1).hex())
        assertEquals("0400817f", CometHid.delta(false, -128, 128).hex())
    }
}

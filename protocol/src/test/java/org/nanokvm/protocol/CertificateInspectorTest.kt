package org.nanokvm.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CertificateInspectorTest {
    @Test
    fun `one-shot inspection captures self-signed metadata and verifies hostname`() {
        val held = HeldCertificate.Builder()
            .commonName("NanoKVM Test")
            .addSubjectAlternativeName("localhost")
            .build()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(held)
            .build()
        val server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start()

        try {
            val inspection = CertificateInspector.inspectBlocking(
                NanoKvmEndpoint.parse(server.url("/").toString()),
            )

            assertEquals(CertificateFingerprint.from(held.certificate), inspection.fingerprint)
            assertTrue(inspection.subject.contains("NanoKVM Test"))
            assertTrue("localhost" in inspection.subjectAlternativeNames.dnsNames)
            assertTrue(inspection.currentlyValid)
            assertTrue(inspection.hostnameVerified)
            assertFalse(inspection.systemTrusted)
            assertEquals(1, inspection.chainLength)
            assertFalse(inspection.metadataTruncated)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cancelling a stalled TLS handshake promptly closes the peer socket`() = runBlocking {
        val clientHelloReceived = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val peerFailure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val peer = thread(isDaemon = true, name = "stalled-certificate-inspection-peer") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val buffer = ByteArray(8_192)

                    check(input.read(buffer) > 0) { "TLS client closed before sending ClientHello" }
                    clientHelloReceived.countDown()
                    try {
                        while (input.read(buffer) != -1) {
                            // Drain until cancellation closes the client transport.
                        }
                    } catch (error: SocketException) {
                        check(error.message.orEmpty().contains("reset", ignoreCase = true)) {
                            "Unexpected socket failure while observing cancellation: ${error.message}"
                        }
                    }
                }
            } catch (error: Throwable) {
                peerFailure.set(error)
            } finally {
                // EOF and connection reset are both valid evidence of prompt remote closure.
                peerClosed.countDown()
            }
        }

        val inspection = launch(Dispatchers.Default) {
            CertificateInspector.inspect(
                endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:${server.localPort}"),
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 30_000,
            )
        }

        try {
            assertTrue(clientHelloReceived.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { inspection.cancelAndJoin() }

            assertTrue(inspection.isCancelled)
            assertTrue(peerClosed.await(2, TimeUnit.SECONDS))
            peerFailure.get()?.let { throw it }
        } finally {
            inspection.cancel()
            server.close()
            peer.join(2_000)
        }
        Unit
    }
}

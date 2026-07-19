package org.nanokvm.protocol

import java.time.Instant
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTrustPreflightTest {
    @Test
    fun `valid self-signed certificate requires review without sending HTTP`() = runBlocking {
        withTlsServer { server, held ->
            val result = EndpointTrustPreflight.inspect(
                NanoKvmEndpoint.parse(server.url("/").toString()),
            )

            assertTrue(result is EndpointTrustPreflightResult.ReviewRequired)
            result as EndpointTrustPreflightResult.ReviewRequired
            assertEquals(CertificateFingerprint.from(held.certificate), result.inspection.fingerprint)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `matching saved leaf pin is trusted and mismatch is terminal`() = runBlocking {
        withTlsServer { server, held ->
            val endpoint = NanoKvmEndpoint.parse(server.url("/").toString())
            val matching = CertificateFingerprint.from(held.certificate)

            val trusted = EndpointTrustPreflight.inspect(endpoint, matching)
            val rejected = EndpointTrustPreflight.inspect(
                endpoint,
                CertificateFingerprint.sha256OfDer(byteArrayOf(1, 2, 3)),
            )

            assertEquals(
                CertificateTrustSource.SAVED_LEAF_PIN,
                (trusted as EndpointTrustPreflightResult.Trusted).source,
            )
            assertEquals(
                TrustPreflightRejection.PIN_MISMATCH,
                (rejected as EndpointTrustPreflightResult.Rejected).reason,
            )
            assertNotNull(rejected.inspection)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `hostname mismatch is terminal even when certificate could be reviewed`() = runBlocking {
        withTlsServer { server, _ ->
            val endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:${server.port}")

            val result = EndpointTrustPreflight.inspect(endpoint)

            assertEquals(
                TrustPreflightRejection.HOSTNAME_MISMATCH,
                (result as EndpointTrustPreflightResult.Rejected).reason,
            )
        }
    }

    @Test
    fun `system trust and invalid dates have deterministic precedence`() {
        val base = inspection(systemTrusted = true)
        val trusted = EndpointTrustPreflight.evaluate(base, savedLeafPin = null)
        val expired = EndpointTrustPreflight.evaluate(
            base.copy(currentlyValid = false),
            savedLeafPin = null,
        )

        assertEquals(
            CertificateTrustSource.SYSTEM,
            (trusted as EndpointTrustPreflightResult.Trusted).source,
        )
        assertEquals(
            TrustPreflightRejection.CERTIFICATE_DATE_INVALID,
            (expired as EndpointTrustPreflightResult.Rejected).reason,
        )
    }

    @Test
    fun `cleartext endpoint is terminal without network access`() = runBlocking {
        val result = EndpointTrustPreflight.inspect(NanoKvmEndpoint.parse("http://127.0.0.1:1"))

        assertEquals(
            TrustPreflightRejection.INSECURE_ENDPOINT,
            (result as EndpointTrustPreflightResult.Rejected).reason,
        )
    }

    private suspend fun withTlsServer(block: suspend (MockWebServer, HeldCertificate) -> Unit) {
        val held = HeldCertificate.Builder()
            .commonName("NanoKVM Test")
            .addSubjectAlternativeName("localhost")
            .build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        val server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start()
        try {
            block(server, held)
        } finally {
            server.shutdown()
        }
    }

    private fun inspection(systemTrusted: Boolean) = CertificateInspection(
        fingerprint = CertificateFingerprint.sha256OfDer(byteArrayOf(9)),
        subject = "CN=NanoKVM",
        issuer = "CN=Test",
        subjectAlternativeNames = CertificateSubjectAlternativeNames(listOf("localhost"), emptyList()),
        validFrom = Instant.EPOCH,
        validUntil = Instant.ofEpochSecond(Long.MAX_VALUE / 1_000_000_000L),
        currentlyValid = true,
        hostnameVerified = true,
        systemTrusted = systemTrusted,
        publicKeyAlgorithm = "RSA",
        chainLength = 1,
        tlsProtocol = "TLSv1.3",
        cipherSuite = "test",
    )
}

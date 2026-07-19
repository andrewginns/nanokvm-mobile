package org.nanokvm.protocol

import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        } finally {
            server.shutdown()
        }
    }
}

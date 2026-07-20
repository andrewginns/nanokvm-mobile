package org.nanokvm.protocol

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateMetadataBoundsTest {
    @Test
    fun `exact UTF-8 boundaries are retained without claiming truncation`() {
        val exactPrincipal = "é".repeat(MAX_CERTIFICATE_PRINCIPAL_DISPLAY_UTF8_BYTES / 2)
        val exactSan = "é".repeat(MAX_CERTIFICATE_SAN_DISPLAY_UTF8_BYTES / 2)

        val metadata = boundCertificateDisplayMetadata(
            subject = exactPrincipal,
            issuer = exactPrincipal,
            dnsNames = listOf(exactSan),
            ipAddresses = emptyList(),
            verifiedHost = null,
        )

        assertEquals(
            MAX_CERTIFICATE_PRINCIPAL_DISPLAY_UTF8_BYTES,
            metadata.subject.encodeToByteArray().size,
        )
        assertEquals(
            MAX_CERTIFICATE_PRINCIPAL_DISPLAY_UTF8_BYTES,
            metadata.issuer.encodeToByteArray().size,
        )
        assertEquals(
            MAX_CERTIFICATE_SAN_DISPLAY_UTF8_BYTES,
            metadata.subjectAlternativeNames.dnsNames.single().encodeToByteArray().size,
        )
        assertFalse(metadata.metadataTruncated)
    }

    @Test
    fun `UTF-8 bounds never split a supplementary code point`() {
        val prefix = "a".repeat(MAX_CERTIFICATE_PRINCIPAL_DISPLAY_UTF8_BYTES - 1)

        val metadata = boundCertificateDisplayMetadata(
            subject = "$prefix🙂tail",
            issuer = "issuer",
            dnsNames = emptyList(),
            ipAddresses = emptyList(),
            verifiedHost = null,
        )

        assertEquals(prefix, metadata.subject)
        assertTrue(metadata.subject.encodeToByteArray().size <= MAX_CERTIFICATE_PRINCIPAL_DISPLAY_UTF8_BYTES)
        assertTrue(metadata.metadataTruncated)
    }

    @Test
    fun `controls bidi formatting and malformed surrogates become visible neutral text`() {
        val metadata = boundCertificateDisplayMetadata(
            subject = "safe\nname\u202Ehidden\u2066text\u2069\uD800",
            issuer = "issuer\u200Djoin",
            dnsNames = listOf("host\tname.example"),
            ipAddresses = emptyList(),
            verifiedHost = null,
        )

        assertEquals("safe�name�hidden�text��", metadata.subject)
        assertEquals("issuer�join", metadata.issuer)
        assertEquals(listOf("host�name.example"), metadata.subjectAlternativeNames.dnsNames)
        assertTrue(metadata.metadataTruncated)
        (listOf(metadata.subject, metadata.issuer) + metadata.subjectAlternativeNames.dnsNames)
            .forEach { value ->
                assertFalse(value.any(Char::isISOControl))
                assertFalse(value.any { Character.getType(it) == Character.FORMAT.toInt() })
            }
    }

    @Test
    fun `SAN count is bounded while the verified wildcard SAN keeps a slot`() {
        val ordinary = List(MAX_CERTIFICATE_DISPLAY_SAN_COUNT) { index ->
            "ordinary-$index.invalid"
        }
        val verifiedSan = "*.example.test"

        val metadata = boundCertificateDisplayMetadata(
            subject = "subject",
            issuer = "issuer",
            dnsNames = ordinary + verifiedSan + "omitted.invalid",
            ipAddresses = emptyList(),
            verifiedHost = "console.example.test",
        )

        assertEquals(
            MAX_CERTIFICATE_DISPLAY_SAN_COUNT,
            metadata.subjectAlternativeNames.dnsNames.size,
        )
        assertTrue(verifiedSan in metadata.subjectAlternativeNames.dnsNames)
        assertFalse(ordinary.last() in metadata.subjectAlternativeNames.dnsNames)
        assertTrue(metadata.metadataTruncated)
    }

    @Test
    fun `an IP host reserves the matching IP SAN rather than a lookalike DNS SAN`() {
        val dnsLookalike = "127.0.0.1"
        val ordinary = List(MAX_CERTIFICATE_DISPLAY_SAN_COUNT - 1) { index ->
            "ordinary-$index.invalid"
        }

        val metadata = boundCertificateDisplayMetadata(
            subject = "subject",
            issuer = "issuer",
            dnsNames = listOf(dnsLookalike) + ordinary,
            ipAddresses = listOf("127.0.0.1"),
            verifiedHost = "127.0.0.1",
        )

        assertEquals(MAX_CERTIFICATE_DISPLAY_SAN_COUNT, metadata.subjectAlternativeNames.run {
            dnsNames.size + ipAddresses.size
        })
        assertEquals(listOf("127.0.0.1"), metadata.subjectAlternativeNames.ipAddresses)
        assertTrue(metadata.metadataTruncated)
    }

    @Test
    fun `manually constructed inspection defaults to conservative metadata state`() {
        val inspection = CertificateInspection(
            fingerprint = CertificateFingerprint.sha256OfDer(byteArrayOf(1)),
            subject = "subject",
            issuer = "issuer",
            subjectAlternativeNames = CertificateSubjectAlternativeNames(emptyList(), emptyList()),
            validFrom = Instant.EPOCH,
            validUntil = Instant.EPOCH,
            currentlyValid = false,
            hostnameVerified = false,
            systemTrusted = false,
            publicKeyAlgorithm = "test",
            chainLength = 1,
            tlsProtocol = "test",
            cipherSuite = "test",
        )

        assertTrue(inspection.metadataTruncated)
    }
}

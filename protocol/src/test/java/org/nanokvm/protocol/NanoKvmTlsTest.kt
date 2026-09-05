package org.nanokvm.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class NanoKvmTlsTest {
    @Test
    fun `DER SHA-256 fingerprint has canonical and display forms`() {
        val fingerprint = CertificateFingerprint.sha256OfDer("abc".encodeToByteArray())

        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            fingerprint.hex,
        )
        assertEquals(
            fingerprint,
            CertificateFingerprint.parse(fingerprint.colonSeparated().lowercase()),
        )
    }

}

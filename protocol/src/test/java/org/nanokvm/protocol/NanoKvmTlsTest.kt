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

    @Test
    fun `TOFU store atomically accepts first match and rejects change`() {
        val store = InMemoryTofuPinStore()
        val first = CertificateFingerprint.sha256OfDer(byteArrayOf(1))
        val changed = CertificateFingerprint.sha256OfDer(byteArrayOf(2))

        assertEquals(TofuDecision.TRUSTED_FIRST_USE, store.verifyOrStore("host:443", first))
        assertEquals(TofuDecision.TRUSTED_EXISTING, store.verifyOrStore("host:443", first))
        assertEquals(TofuDecision.REJECTED_CHANGED, store.verifyOrStore("host:443", changed))
        assertEquals(first, store.fingerprint("host:443"))
    }
}

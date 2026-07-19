package org.nanokvm.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCodecTest {
    @Test
    fun legacyJsonRoundTripsWithoutChangingIdentityOrTrust() {
        val legacy = """[{"id":"desk","name":"Desk","host":"192.0.2.4","port":8443,"https":true,"username":"admin","certificate":"AA:BB"}]"""

        val profile = ProfileCodec.decode(legacy).getOrThrow().single()
        val roundTrip = ProfileCodec.decode(ProfileCodec.encode(listOf(profile))).getOrThrow().single()

        assertEquals(profile, roundTrip)
        assertEquals("AA:BB", roundTrip.trustedCertificateSha256)
    }

    @Test
    fun malformedOrInvalidProfilesAreReportedInsteadOfSilentlyDefaulted() {
        assertTrue(ProfileCodec.decode("not-json").isFailure)
        assertTrue(
            ProfileCodec.decode("""[{"id":"desk","host":"kvm.local","port":70000}]""")
                .isFailure,
        )
    }

    @Test
    fun emptyCatalogRemainsDistinguishableFromCorruption() {
        assertEquals(emptyList<HostProfile>(), ProfileCodec.decode("[]").getOrThrow())
    }
}

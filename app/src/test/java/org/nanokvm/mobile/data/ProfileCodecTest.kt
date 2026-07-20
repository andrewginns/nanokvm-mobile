package org.nanokvm.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCodecTest {
    @Test
    fun legacyJsonRoundTripsWithoutChangingIdentityOrTrust() {
        val fingerprint = List(32) { "AA" }.joinToString(":")
        val legacy = """[{"id":"desk","name":"Desk","host":"192.0.2.4","port":8443,"https":true,"username":"admin","certificate":"$fingerprint"}]"""

        val profile = ProfileCodec.decode(legacy).getOrThrow().single()
        val roundTrip = ProfileCodec.decode(ProfileCodec.encode(listOf(profile))).getOrThrow().single()

        assertEquals(profile, roundTrip)
        assertEquals(fingerprint, roundTrip.trustedCertificateSha256)
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

    @Test
    fun profileInputPolicyAcceptsOnlyBoundedHttpsOrigins() {
        val valid = HostProfile(
            id = "desk",
            name = "Desk KVM",
            host = "kvm.example.test",
            port = 8443,
            useHttps = true,
            username = "admin",
        )

        assertTrue(ProfileInputPolicy.isValid(valid))
        assertFalse(ProfileInputPolicy.isValid(valid.copy(useHttps = false)))
        assertFalse(ProfileInputPolicy.isValid(valid.copy(name = "Desk\nKVM")))
        assertFalse(
            ProfileInputPolicy.isValid(
                valid.copy(name = "x".repeat(ProfileInputPolicy.MAX_NAME_CHARS + 1)),
            ),
        )
        assertFalse(ProfileInputPolicy.isValid(valid.copy(host = "kvm.example.test/path")))
        assertFalse(ProfileInputPolicy.isValid(valid.copy(host = "kvm.example.test:8443")))
        assertFalse(
            ProfileInputPolicy.isValid(
                valid.copy(username = "x".repeat(ProfileInputPolicy.MAX_USERNAME_CHARS + 1)),
            ),
        )
        assertFalse(ProfileInputPolicy.isValid(valid.copy(trustedCertificateSha256 = "not-a-pin")))
        assertFalse(
            ProfileInputPolicy.isValid(
                valid.copy(trustedCertificateSha256 = "A".repeat(10_000)),
            ),
        )
        assertTrue(
            ProfileInputPolicy.isValid(
                valid.copy(
                    trustedCertificateSha256 =
                        List(32) { "AA" }.joinToString(":"),
                ),
            ),
        )
    }

    @Test
    fun legacyHttpProfileIsDecodedButItsProspectiveHttpsReplacementIsValidated() {
        val legacy = """[{"id":"desk","name":"Desk","host":"192.0.2.4","port":80,"https":false,"username":"admin"}]"""

        val decoded = ProfileCodec.decode(legacy).getOrThrow().single()
        val replacement = ProfileInputPolicy.prospectiveHttps(decoded)

        assertFalse(decoded.useHttps)
        assertEquals(443, replacement.port)
        assertTrue(replacement.useHttps)
        assertTrue(ProfileInputPolicy.isValid(replacement))
        assertEquals(
            decoded,
            ProfileCodec.decode(ProfileCodec.encode(listOf(decoded))).getOrThrow().single(),
        )
    }

    @Test
    fun persistedProfilesUseTheSameInputPolicyAsTheEditor() {
        val controlledName = """[{"id":"desk","name":"Desk\nKVM","host":"kvm.local","port":443,"https":true,"username":"admin"}]"""
        val pathInHost = """[{"id":"desk","name":"Desk","host":"kvm.local/path","port":443,"https":true,"username":"admin"}]"""

        assertTrue(ProfileCodec.decode(controlledName).isFailure)
        assertTrue(ProfileCodec.decode(pathInHost).isFailure)
    }
}

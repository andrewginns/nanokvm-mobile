package org.nanokvm.mobile.security

import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.runtime.ConnectRequest

class SavedCredentialStoreTest {
    @Test
    fun profileHashIsStableAndDoesNotExposeTheProfileId() {
        val profileId = "private-profile-id"

        val first = SavedCredentialStore.profileHash(profileId)
        val second = SavedCredentialStore.profileHash(profileId)

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertFalse(first.contains(profileId))
    }

    @Test
    fun credentialBindingTracksLoginIdentityButNotPresentationOrCertificate() {
        val profile = HostProfile(
            id = "profile-1",
            name = "Desk KVM",
            host = "192.0.2.250",
            port = 443,
            useHttps = true,
            username = "admin",
            trustedCertificateSha256 = "AA:BB",
        )
        val original = SavedCredentialStore.credentialBinding(profile)

        assertArrayEquals(
            original,
            SavedCredentialStore.credentialBinding(
                profile.copy(name = "Renamed", trustedCertificateSha256 = "CC:DD"),
            ),
        )
        assertArrayEquals(
            original,
            SavedCredentialStore.credentialBinding(
                profile.copy(host = "  192.0.2.250  ", username = "  admin  "),
            ),
        )
        assertFalse(
            original.contentEquals(
                SavedCredentialStore.credentialBinding(profile.copy(host = "nanokvm.local")),
            ),
        )
        assertFalse(
            original.contentEquals(
                SavedCredentialStore.credentialBinding(profile.copy(username = "operator")),
            ),
        )
        assertFalse(
            original.contentEquals(
                SavedCredentialStore.credentialBinding(profile.copy(useHttps = false)),
            ),
        )
        assertFalse(
            original.contentEquals(
                SavedCredentialStore.credentialBinding(profile.copy(port = 8443)),
            ),
        )
        original.fill(0)
    }

    @Test
    fun credentialIdentityCanonicalizesHostWithRootLocaleAndKeepsExplicitEndpointFields() {
        val priorLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val identity = SavedCredentialStore.credentialIdentity(
                HostProfile(
                    id = "profile-1",
                    host = "  I.EXAMPLE  ",
                    port = 443,
                    useHttps = true,
                    username = "  Admin  ",
                ),
            )

            assertEquals(
                CredentialIdentity(
                    profileId = "profile-1",
                    scheme = "https",
                    host = "i.example",
                    port = 443,
                    username = "Admin",
                ),
                identity,
            )
        } finally {
            Locale.setDefault(priorLocale)
        }
    }

    @Test
    fun credentialBindingTreatsHostCaseAndOuterWhitespaceAsTheSameIdentity() {
        val profile = HostProfile(
            id = "profile-1",
            host = "NanoKVM.Local",
            port = 80,
            useHttps = false,
            username = "admin",
        )

        assertArrayEquals(
            SavedCredentialStore.credentialBinding(profile),
            SavedCredentialStore.credentialBinding(
                profile.copy(host = "  NANOKVM.LOCAL  ", username = " admin "),
            ),
        )
        assertFalse(
            SavedCredentialStore.credentialBinding(profile).contentEquals(
                SavedCredentialStore.credentialBinding(profile.copy(username = "Admin")),
            ),
        )
    }

    @Test
    fun passwordCodecRoundTripsStrictUtf8WithoutAStringConversion() {
        val password = charArrayOf('p', '\u00e4', '\ud83d', '\udd10', '\u0000', 'x')
        val encoded = SavedCredentialStore.encodePassword(password)
        val decoded = SavedCredentialStore.decodePassword(encoded)

        assertArrayEquals(password, decoded)

        password.fill('\u0000')
        encoded.fill(0)
        decoded.fill('\u0000')
    }

    @Test
    fun passwordCodecRejectsMalformedUtf8AndUnpairedSurrogates() {
        assertThrows(SavedCredentialUnavailableException::class.java) {
            SavedCredentialStore.decodePassword(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedCredentialStore.encodePassword(charArrayOf('\ud83d'))
        }
    }

    @Test
    fun envelopeRoundTripsAndRejectsCorruptionOrTrailingData() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(48) { (it * 3).toByte() }

        val encoded = SavedCredentialStore.encodeEnvelope(iv, ciphertext)
        val decoded = SavedCredentialStore.decodeEnvelope(encoded)

        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
        decoded.clear()

        val corrupted = encoded.copyOf().also { it[0] = 0 }
        assertThrows(SavedCredentialUnavailableException::class.java) {
            SavedCredentialStore.decodeEnvelope(corrupted)
        }
        assertThrows(SavedCredentialUnavailableException::class.java) {
            SavedCredentialStore.decodeEnvelope(encoded + byteArrayOf(1))
        }
    }

    @Test
    fun clearingAStagedCredentialErasesItsInMemoryEnvelope() {
        val staged = StagedCredential("profile-1", byteArrayOf(1, 2, 3, 4))
        assertNotEquals(0, staged.copyPayload().sum())

        staged.clear()

        assertTrue(staged.copyPayload().all { it == 0.toByte() })
    }

    @Test
    fun clearingAConnectRequestErasesTheOwnedPasswordBuffer() {
        val password = "temporary-test-password".toCharArray()
        val request = ConnectRequest(HostProfile.Default, password)

        request.clearPassword()

        assertTrue(password.all { it == '\u0000' })
    }
}

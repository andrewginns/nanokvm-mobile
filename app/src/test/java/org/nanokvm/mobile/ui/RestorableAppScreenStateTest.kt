package org.nanokvm.mobile.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.runtime.CertificateDetails

class RestorableAppScreenStateTest {
    @Test
    fun editorDestinationAndNonSecretProfileRestoreAfterProcessDeath() {
        val handle = SavedStateHandle()
        val expected = AppScreen.EditProfile(
            HostProfile(
                id = "draft",
                name = "Desk",
                host = "kvm.local",
                port = 8443,
                username = "operator",
                trustedCertificateSha256 = "AA:BB",
            ),
            isNew = true,
        )

        RestorableAppScreenState.persist(handle, expected)

        assertEquals(expected, RestorableAppScreenState.restore(handle))
        assertTrue(handle.keys().none { it.contains("password", ignoreCase = true) })
    }

    @Test
    fun liveAndTrustScreensResetToProfilesInsteadOfRestoringASecretSession() {
        val handle = SavedStateHandle()
        val profile = HostProfile.Default
        val certificate = CertificateDetails("AA", "subject", "issuer", emptyList(), "from", "to")

        listOf<AppScreen>(
            AppScreen.Connecting(profile),
            AppScreen.ReviewCertificate(profile, certificate),
            AppScreen.Console(profile),
        ).forEach { screen ->
            RestorableAppScreenState.persist(handle, screen)
            assertSame(AppScreen.Profiles, RestorableAppScreenState.restore(handle))
        }
    }
}

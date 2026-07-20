package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdministrationSurfaceLifecycleTest {
    @Test
    fun `approved destination requires exact profile authority and generation`() {
        val binding = binding(generation = 7L)
        val approved = ApprovedAdministrationDestination(
            profileId = "office",
            authority = "192.0.2.4",
            sessionGeneration = 7L,
        )

        assertTrue(approved.matches(binding))
        assertFalse(approved.copy(profileId = "other").matches(binding))
        assertFalse(approved.copy(authority = "192.0.2.5").matches(binding))
        assertFalse(approved.copy(sessionGeneration = 8L).matches(binding))
        assertFalse(approved.toString().contains("192.0.2.4"))
    }

    @Test
    fun `administration loads only for visible foreground exact installed binding`() {
        val current = binding(generation = 11L)

        assertTrue(
            administrationSurfaceCanLoad(
                visible = true,
                foreground = true,
                currentBinding = current,
                installedBinding = current,
            ),
        )
        assertFalse(
            administrationSurfaceCanLoad(
                visible = false,
                foreground = true,
                currentBinding = current,
                installedBinding = current,
            ),
        )
        assertFalse(
            administrationSurfaceCanLoad(
                visible = true,
                foreground = false,
                currentBinding = current,
                installedBinding = current,
            ),
        )
        assertFalse(
            administrationSurfaceCanLoad(
                visible = true,
                foreground = true,
                currentBinding = current,
                installedBinding = binding(generation = 12L),
            ),
        )
    }

    @Test
    fun `pending https navigation is redacted and acknowledged only by exact destination and id`() {
        val request = PendingAdministrationHttpsNavigationRequest(
            requestId = 41L,
            profileId = "office",
            authority = "192.0.2.4",
            sessionGeneration = 7L,
            value = "https://login.tailscale.com/a/auth-token",
        )
        val state = AdministrationUiState(pendingHttpsNavigation = request)
        val destination = ApprovedAdministrationDestination(
            profileId = "office",
            authority = "192.0.2.4",
            sessionGeneration = 7L,
        )

        assertSame(state, state.acknowledgeOpenedHttpsNavigation(destination, 40L))
        assertSame(
            state,
            state.acknowledgeOpenedHttpsNavigation(
                destination.copy(sessionGeneration = 8L),
                41L,
            ),
        )

        var openedUrl: String? = null
        request.open { openedUrl = it }
        assertEquals("https://login.tailscale.com/a/auth-token", openedUrl)

        val acknowledged = state.acknowledgeOpenedHttpsNavigation(destination, 41L)
        assertNull(acknowledged.pendingHttpsNavigation)
        assertEquals(
            AdministrationNotice.Guidance(
                AdministrationNotice.GuidanceReason.TailscaleAuthorizationPageOpened,
            ),
            acknowledged.notice,
        )

        val diagnostic = request.toString()
        assertFalse(diagnostic.contains("192.0.2.4"))
        assertFalse(diagnostic.contains("login.tailscale.com"))
        assertFalse(diagnostic.contains("auth-token"))
    }

    @Test
    fun `pending administration navigation rejects cleartext urls`() {
        assertThrows(IllegalArgumentException::class.java) {
            PendingAdministrationHttpsNavigationRequest(
                requestId = 1L,
                profileId = "office",
                authority = "192.0.2.4",
                sessionGeneration = 7L,
                value = "http://login.tailscale.com/a/auth-token",
            )
        }
    }

    private fun binding(generation: Long) = NanoKvmSessionBinding(
        profileId = "office",
        authority = "192.0.2.4",
        sessionGeneration = generation,
    )
}

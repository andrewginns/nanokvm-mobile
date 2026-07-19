package org.nanokvm.mobile.runtime

import org.junit.Assert.assertFalse
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

    private fun binding(generation: Long) = NanoKvmSessionBinding(
        profileId = "office",
        authority = "192.0.2.4",
        sessionGeneration = generation,
    )
}

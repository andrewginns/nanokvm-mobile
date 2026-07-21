package org.nanokvm.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedCoreDestinationTest {
    @Test
    fun `matches only the exact authenticated destination and generation`() {
        val destination = ApprovedCoreDestination(
            profileId = "office-secret-id",
            authority = "secret-host.example:443",
            sessionGeneration = 7L,
        )

        assertTrue(
            destination.matches(
                NanoKvmSessionBinding("office-secret-id", "secret-host.example:443", 7L),
            ),
        )
        assertFalse(
            destination.matches(
                NanoKvmSessionBinding("other", "secret-host.example:443", 7L),
            ),
        )
        assertFalse(
            destination.matches(
                NanoKvmSessionBinding("office-secret-id", "other.example:443", 7L),
            ),
        )
        assertFalse(
            destination.matches(
                NanoKvmSessionBinding("office-secret-id", "secret-host.example:443", 8L),
            ),
        )
    }

    @Test
    fun `diagnostics redact identity and invalid destinations are rejected`() {
        val destination = ApprovedCoreDestination(
            profileId = "office-secret-id",
            authority = "secret-host.example:443",
            sessionGeneration = 7L,
        )

        assertFalse(destination.toString().contains("office-secret-id"))
        assertFalse(destination.toString().contains("secret-host.example"))
        assertTrue(destination.toString().contains("sessionGeneration=7"))
        assertThrows(IllegalArgumentException::class.java) {
            ApprovedCoreDestination("", "host.example", 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApprovedCoreDestination("profile", "", 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApprovedCoreDestination("profile", "host.example", -1L)
        }
    }

    @Test
    fun `connected and degraded are the only usable session states`() {
        assertTrue(ConnectionState.Connected.isSessionUsable)
        assertTrue(ConnectionState.Degraded.isSessionUsable)
        assertFalse(ConnectionState.Disconnected.isSessionUsable)
        assertFalse(ConnectionState.Connecting.isSessionUsable)
        assertFalse(ConnectionState.Reconnecting.isSessionUsable)
        assertFalse(ConnectionState.Failed.isSessionUsable)
    }
}

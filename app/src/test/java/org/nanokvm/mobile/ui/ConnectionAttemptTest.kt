package org.nanokvm.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.runtime.ConnectRequest
import org.nanokvm.mobile.security.StagedCredential

class ConnectionAttemptTest {
    @Test
    fun certificateContinuationRetainsSecretButInvalidatesEarlierPhase() {
        val password = "temporary-password".toCharArray()
        val initial = ConnectRequest(HostProfile.Default, password)
        val staged = StagedCredential(HostProfile.Default.id, byteArrayOf(1, 2, 3))
        val attempt = ConnectionAttempt(initial, staged)
        val continued = initial.copy(acceptedCertificateSha256 = "AA:BB")

        attempt.continueWith(continued)

        assertFalse(attempt.owns(initial))
        assertTrue(attempt.owns(continued))
        assertSame(staged, attempt.stagedCredential)
        assertSame(password, continued.password)
    }

    @Test
    fun replacingAttemptWipesOldSecretsAndRejectsItsLateOutcome() {
        val oldPassword = "old-password".toCharArray()
        val oldStaged = StagedCredential("old", byteArrayOf(9, 8, 7))
        val oldRequest = ConnectRequest(HostProfile.Default.copy(id = "old"), oldPassword)
        val oldAttempt = ConnectionAttempt(oldRequest, oldStaged)
        val newPassword = "new-password".toCharArray()
        val newStaged = StagedCredential("new", byteArrayOf(3, 2, 1))
        val newRequest = ConnectRequest(HostProfile.Default.copy(id = "new"), newPassword)
        val newAttempt = ConnectionAttempt(newRequest, newStaged)
        val slot = ConnectionAttemptSlot()
        slot.replace(oldAttempt)

        slot.replace(newAttempt)

        assertTrue(oldPassword.all { it == '\u0000' })
        assertTrue(oldStaged.copyPayload().all { it == 0.toByte() })
        assertFalse(slot.finishIfOwned(oldAttempt, oldRequest))
        assertTrue(slot.owns(newAttempt, newRequest))
        assertTrue(newPassword.any { it != '\u0000' })
        assertTrue(newStaged.copyPayload().any { it != 0.toByte() })

        slot.clear()
    }

    @Test
    fun lateOutcomeFromSupersededCertificatePhaseCannotFinishAttempt() {
        val initial = ConnectRequest(HostProfile.Default, "temporary-password".toCharArray())
        val attempt = ConnectionAttempt(initial, stagedCredential = null)
        val slot = ConnectionAttemptSlot().also { it.replace(attempt) }
        val continued = initial.copy(acceptedCertificateSha256 = "CC:DD")
        attempt.continueWith(continued)

        assertFalse(slot.finishIfOwned(attempt, initial))
        assertTrue(slot.owns(attempt, continued))
        assertTrue(slot.finishIfOwned(attempt, continued))
        assertTrue(initial.password.all { it == '\u0000' })
    }

    @Test
    fun terminalClearIsIdempotentAndWipesBothBuffers() {
        val password = "temporary-password".toCharArray()
        val staged = StagedCredential("profile", byteArrayOf(4, 5, 6))
        val attempt = ConnectionAttempt(
            ConnectRequest(HostProfile.Default.copy(id = "profile"), password),
            staged,
        )
        val slot = ConnectionAttemptSlot().also { it.replace(attempt) }

        slot.clear()
        slot.clear()

        assertTrue(password.all { it == '\u0000' })
        assertTrue(staged.copyPayload().all { it == 0.toByte() })
        assertFalse(attempt.owns(attempt.request))
    }

    @Test
    fun continuationCannotSwapInAnotherPasswordBuffer() {
        val attempt = ConnectionAttempt(
            ConnectRequest(HostProfile.Default, "first-password".toCharArray()),
            stagedCredential = null,
        )
        val unrelated = ConnectRequest(HostProfile.Default, "second-password".toCharArray())

        assertThrows(IllegalArgumentException::class.java) {
            attempt.continueWith(unrelated)
        }
        assertTrue(attempt.owns(attempt.request))

        unrelated.clearPassword()
        attempt.clear()
    }
}

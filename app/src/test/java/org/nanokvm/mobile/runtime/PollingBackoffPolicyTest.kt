package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PollingBackoffPolicyTest {
    @Test
    fun `consecutive failures back off exponentially and cap at interactive maximum`() {
        val policy = PollingBackoffPolicy(
            healthyIntervalMillis = 2_000L,
            maximumIntervalMillis = 30_000L,
        )
        var state = policy.initialState

        assertEquals(2_000L, policy.delayMillis(state))
        listOf(4_000L, 8_000L, 16_000L, 30_000L, 30_000L).forEach { expectedDelay ->
            state = policy.afterFailure(state)
            assertEquals(expectedDelay, policy.delayMillis(state))
        }
    }

    @Test
    fun `one success immediately restores healthy cadence`() {
        val policy = PollingBackoffPolicy()
        var state = policy.initialState
        repeat(3) { state = policy.afterFailure(state) }
        assertEquals(16_000L, policy.delayMillis(state))

        state = policy.afterSuccess()

        assertEquals(2_000L, policy.delayMillis(state))
        assertEquals(4_000L, policy.delayMillis(policy.afterFailure(state)))
    }

    @Test
    fun `independent poll streams do not share failure history`() {
        val policy = PollingBackoffPolicy()
        var gpioState = policy.initialState
        var anotherState = policy.initialState

        repeat(2) { gpioState = policy.afterFailure(gpioState) }
        anotherState = policy.afterFailure(anotherState)

        assertEquals(8_000L, policy.delayMillis(gpioState))
        assertEquals(4_000L, policy.delayMillis(anotherState))
        assertEquals(8_000L, policy.delayMillis(gpioState))
    }

    @Test
    fun `invalid schedules are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PollingBackoffPolicy(healthyIntervalMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PollingBackoffPolicy(healthyIntervalMillis = 2_000L, maximumIntervalMillis = 1_999L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PollingBackoffPolicy(multiplier = 1)
        }
    }
}

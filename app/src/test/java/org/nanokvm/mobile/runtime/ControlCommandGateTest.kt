package org.nanokvm.mobile.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCommandGateTest {
    @Test
    fun `duplicate GPIO claim is suppressed until original releases`() {
        val gate = ControlCommandGate()
        val first = gate.claim("gpio")

        assertNotNull(first)
        assertNull(gate.claim("gpio"))

        gate.release(checkNotNull(first))
        assertNotNull(gate.claim("gpio"))
    }

    @Test
    fun `session invalidation drops queued command without invoking it`() = runBlocking {
        val gate = ControlCommandGate()
        val lease = checkNotNull(gate.claim("power"))
        var invoked = false
        gate.invalidate()

        val executed = gate.executeIfCurrent(lease) { invoked = true }

        assertFalse(executed)
        assertFalse(invoked)
    }

    @Test
    fun `different controls execute through one serialized lane`() = runBlocking {
        val gate = ControlCommandGate()
        val firstLease = checkNotNull(gate.claim("video"))
        val secondLease = checkNotNull(gate.claim("gpio"))
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondReady = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch(Dispatchers.Default) {
            gate.executeIfCurrent(firstLease) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch(Dispatchers.Default) {
            secondReady.complete(Unit)
            gate.executeIfCurrent(secondLease) { secondEntered.complete(Unit) }
        }
        secondReady.await()
        yield()
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        secondEntered.await()
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
    }
}

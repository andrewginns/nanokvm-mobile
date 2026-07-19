package org.nanokvm.mobile.runtime

import java.io.IOException
import org.nanokvm.protocol.AuthenticationExpiredException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NanoKvmMjpegFrameDetectionGatewayTest {
    private val binding = NanoKvmSessionBinding("profile", "nanokvm.test", 7L)

    @Test
    fun `toggle dispatches exactly once and reports only acknowledgement`() = runTest {
        val port = FakeFrameDetectionPort()
        val gateway = NanoKvmMjpegFrameDetectionGateway(port, binding) { binding }

        assertSame(MjpegFrameDetectionResult.Acknowledged, gateway.setEnabled(true))

        assertEquals(listOf(true), port.enabledWrites)
        assertEquals(emptyList<Int>(), port.pauseWrites)
    }

    @Test
    fun `startup wake is bounded and never replays a failed write`() = runTest {
        val port = FakeFrameDetectionPort(failure = IOException("offline"))
        val gateway = NanoKvmMjpegFrameDetectionGateway(port, binding) { binding }

        val result = gateway.pauseForMjpegStartup()

        assertEquals(
            MjpegFrameDetectionResult.Rejected(MjpegFrameDetectionFailure.CONNECTION),
            result,
        )
        assertEquals(listOf(10), port.pauseWrites)
    }

    @Test
    fun `stale generation rejects before any appliance write`() = runTest {
        val port = FakeFrameDetectionPort()
        val gateway = NanoKvmMjpegFrameDetectionGateway(port, binding) {
            binding.copy(sessionGeneration = 8L)
        }

        val result = gateway.setEnabled(false)

        assertEquals(
            MjpegFrameDetectionResult.Rejected(MjpegFrameDetectionFailure.SESSION_CHANGED),
            result,
        )
        assertEquals(emptyList<Boolean>(), port.enabledWrites)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `startup wake rejects duration outside the server contract`() = runTest {
        NanoKvmMjpegFrameDetectionGateway(FakeFrameDetectionPort(), binding) { binding }
            .pauseForMjpegStartup(31)
    }

    @Test
    fun `authentication expiry is classified for the backend global boundary`() = runTest {
        val port = FakeFrameDetectionPort(failure = AuthenticationExpiredException())
        val gateway = NanoKvmMjpegFrameDetectionGateway(port, binding) { binding }

        val result = gateway.setEnabled(true)

        assertEquals(
            MjpegFrameDetectionResult.Rejected(
                MjpegFrameDetectionFailure.AUTHENTICATION_EXPIRED,
            ),
            result,
        )
        assertEquals(listOf(true), port.enabledWrites)
    }

    @Test
    fun `startup gate coalesces fallback and streaming callbacks for one activation`() {
        val gate = MjpegFrameDetectionStartupGate()
        gate.installGeneration(7L)

        assertEquals(true, gate.claimPause(7L, enabled = true))
        assertEquals(false, gate.claimPause(7L, enabled = true))
        gate.leaveMjpeg(7L)
        assertEquals(true, gate.claimPause(7L, enabled = true))
    }

    @Test
    fun `disabled or stale startup observation does not consume a current activation`() {
        val gate = MjpegFrameDetectionStartupGate()
        gate.installGeneration(8L)

        assertEquals(false, gate.claimPause(8L, enabled = false))
        assertEquals(false, gate.claimPause(7L, enabled = true))
        assertEquals(true, gate.claimPause(8L, enabled = true))
        gate.clear()
        assertEquals(false, gate.claimPause(8L, enabled = true))
    }
}

private class FakeFrameDetectionPort(
    private val failure: Throwable? = null,
) : NanoKvmMjpegFrameDetectionPort {
    val enabledWrites = mutableListOf<Boolean>()
    val pauseWrites = mutableListOf<Int>()

    override suspend fun setEnabled(enabled: Boolean) {
        enabledWrites += enabled
        failure?.let { throw it }
    }

    override suspend fun pause(durationSeconds: Int) {
        pauseWrites += durationSeconds
        failure?.let { throw it }
    }
}

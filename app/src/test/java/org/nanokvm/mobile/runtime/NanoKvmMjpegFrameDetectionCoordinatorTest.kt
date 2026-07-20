package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.nanokvm.protocol.AuthenticationExpiredException

@OptIn(ExperimentalCoroutinesApi::class)
class NanoKvmMjpegFrameDetectionCoordinatorTest {
    private val binding = NanoKvmSessionBinding("profile", "nanokvm.test", 7L)

    @Test
    fun `settings observation is local and explicit changes each write once in order`() = runTest {
        val fixture = fixture()

        fixture.coordinator.setPreference(true)
        advanceUntilIdle()
        assertEquals(emptyList<Boolean>(), fixture.port.enabledWrites)

        fixture.coordinator.setEnabledByUser(true)
        fixture.coordinator.setEnabledByUser(false)
        advanceUntilIdle()

        assertEquals(listOf(true, false), fixture.port.enabledWrites)
        assertEquals(false, fixture.authenticationExpired)
        assertEquals(emptyList<ConsoleMessage>(), fixture.rejections)
    }

    @Test
    fun `enabled MJPEG activation pauses once and a new video session rearms it`() = runTest {
        val fixture = fixture()
        fixture.coordinator.setPreference(true)

        fixture.coordinator.onMjpegActivation()
        fixture.coordinator.onMjpegActivation()
        advanceUntilIdle()
        assertEquals(listOf(10), fixture.port.pauseWrites)

        fixture.coordinator.onVideoSessionStarting()
        fixture.coordinator.onMjpegActivation()
        advanceUntilIdle()
        assertEquals(listOf(10, 10), fixture.port.pauseWrites)
    }

    @Test
    fun `disabled preference does not consume the current MJPEG activation`() = runTest {
        val fixture = fixture()

        fixture.coordinator.onMjpegActivation()
        fixture.coordinator.setPreference(true)
        fixture.coordinator.onMjpegActivation()
        advanceUntilIdle()

        assertEquals(listOf(10), fixture.port.pauseWrites)
    }

    @Test
    fun `generation replacement cancels queued stale writes`() = runTest {
        var current = binding
        val oldPort = CoordinatorFrameDetectionPort()
        val coordinator = coordinator(currentBinding = { current })
        coordinator.install(binding) { gateway(oldPort, binding) { current } }
        coordinator.setEnabledByUser(true)

        current = binding.copy(sessionGeneration = 8L)
        val newPort = CoordinatorFrameDetectionPort()
        coordinator.install(current) { gateway(newPort, current) { current } }
        coordinator.setEnabledByUser(false)
        advanceUntilIdle()

        assertEquals(emptyList<Boolean>(), oldPort.enabledWrites)
        assertEquals(listOf(false), newPort.enabledWrites)
    }

    @Test
    fun `stale authentication failure cannot expire a replacement session`() = runTest {
        var current = binding
        var authenticationExpired = false
        val port = BlockingAuthenticationFrameDetectionPort()
        val coordinator = coordinator(
            currentBinding = { current },
            onAuthenticationExpired = { authenticationExpired = true },
        )
        coordinator.install(binding) { gateway(port, binding) { current } }
        coordinator.setEnabledByUser(true)
        runCurrent()
        port.entered.await()
        current = binding.copy(sessionGeneration = 8L)
        port.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(false, authenticationExpired)
        assertEquals(listOf(true), port.enabledWrites)
    }

    @Test
    fun `current authentication failure crosses the global expiry callback once`() = runTest {
        val fixture = fixture(failure = AuthenticationExpiredException())

        fixture.coordinator.setEnabledByUser(true)
        advanceUntilIdle()

        assertEquals(true, fixture.authenticationExpired)
        assertEquals(listOf(true), fixture.port.enabledWrites)
        assertEquals(emptyList<ConsoleMessage>(), fixture.rejections)
    }

    @Test
    fun `connection rejection is reported once and never retried`() = runTest {
        val fixture = fixture(failure = IOException("offline"))

        fixture.coordinator.setEnabledByUser(true)
        advanceUntilIdle()

        assertEquals(listOf(true), fixture.port.enabledWrites)
        assertEquals(
            listOf(ConsoleMessage.MjpegFrameDetectionNotAcknowledged),
            fixture.rejections,
        )
        assertEquals(false, fixture.authenticationExpired)
    }

    @Test
    fun `explicit change reports typed unavailable message without an installed gateway`() = runTest {
        val rejections = mutableListOf<ConsoleMessage>()
        val coordinator = coordinator(
            currentBinding = { binding },
            onRejected = rejections::add,
        )

        coordinator.setEnabledByUser(true)

        assertEquals(listOf(ConsoleMessage.MjpegFrameDetectionUnavailable), rejections)
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        failure: Throwable? = null,
    ): CoordinatorFixture {
        val port = CoordinatorFrameDetectionPort(failure)
        var authenticationExpired = false
        val rejections = mutableListOf<ConsoleMessage>()
        val coordinator = coordinator(
            currentBinding = { binding },
            onAuthenticationExpired = { authenticationExpired = true },
            onRejected = rejections::add,
        )
        coordinator.install(binding) { gateway(port, binding) { binding } }
        return CoordinatorFixture(
            coordinator = coordinator,
            port = port,
            authenticationExpiredValue = { authenticationExpired },
            rejections = rejections,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        currentBinding: () -> NanoKvmSessionBinding?,
        onAuthenticationExpired: (NanoKvmSessionBinding) -> Unit = {},
        onRejected: (ConsoleMessage) -> Unit = {},
    ) = NanoKvmMjpegFrameDetectionCoordinator(
        scope = this,
        currentBinding = currentBinding,
        onAuthenticationExpired = onAuthenticationExpired,
        onRejected = onRejected,
    )

    private fun gateway(
        port: NanoKvmMjpegFrameDetectionPort,
        gatewayBinding: NanoKvmSessionBinding,
        currentBinding: () -> NanoKvmSessionBinding?,
    ) = NanoKvmMjpegFrameDetectionGateway(port, gatewayBinding, currentBinding)
}

private class CoordinatorFixture(
    val coordinator: NanoKvmMjpegFrameDetectionCoordinator,
    val port: CoordinatorFrameDetectionPort,
    private val authenticationExpiredValue: () -> Boolean,
    val rejections: List<ConsoleMessage>,
) {
    val authenticationExpired: Boolean
        get() = authenticationExpiredValue()
}

private class CoordinatorFrameDetectionPort(
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

private class BlockingAuthenticationFrameDetectionPort : NanoKvmMjpegFrameDetectionPort {
    val enabledWrites = mutableListOf<Boolean>()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun setEnabled(enabled: Boolean) {
        enabledWrites += enabled
        entered.complete(Unit)
        release.await()
        throw AuthenticationExpiredException()
    }

    override suspend fun pause(durationSeconds: Int) = Unit
}

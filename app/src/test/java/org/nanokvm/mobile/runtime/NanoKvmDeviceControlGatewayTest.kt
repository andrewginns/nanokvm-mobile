package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.NanoKvmHdmiState
import org.nanokvm.protocol.NanoKvmMemoryLimitPreset
import org.nanokvm.protocol.NanoKvmMemoryLimitState
import org.nanokvm.protocol.NanoKvmMouseJigglerMode
import org.nanokvm.protocol.NanoKvmMouseJigglerState
import org.nanokvm.protocol.NanoKvmSwapSizePreset
import org.nanokvm.protocol.NanoKvmSwapState

class NanoKvmDeviceControlGatewayTest {
    @Test
    fun `HDMI setter preflights dispatches once and confirms by readback`() = runTest {
        val binding = binding()
        val port = FakeDeviceControlPort()
        val gateway = gateway(port, binding) { binding }

        val result = gateway.setHdmiEnabled(false)

        assertTrue(result is NanoKvmDeviceControlMutationResult.Applied)
        assertEquals(false, (result as NanoKvmDeviceControlMutationResult.Applied).state.enabled)
        assertEquals(1, port.hdmiWrites)
        assertEquals(2, port.hdmiReads)
    }

    @Test
    fun `already-satisfied setter sends no mutation`() = runTest {
        val binding = binding()
        val port = FakeDeviceControlPort()
        val gateway = gateway(port, binding) { binding }

        val result = gateway.setHdmiEnabled(true)

        assertTrue(result is NanoKvmDeviceControlMutationResult.AlreadySatisfied)
        assertEquals(0, port.hdmiWrites)
        assertEquals(1, port.hdmiReads)
    }

    @Test
    fun `only missing optional endpoint statuses are unsupported`() = runTest {
        val binding = binding()
        val port = FakeDeviceControlPort()
        val gateway = gateway(port, binding) { binding }

        listOf(404, 405, 501).forEach { status ->
            port.hdmiReadFailure = HttpResponseException(status)
            val result = gateway.refreshHdmi() as NanoKvmDeviceControlReadResult.Failure
            assertEquals(NanoKvmDeviceControlError.Kind.UNSUPPORTED, result.error.kind)
        }
        port.hdmiReadFailure = HttpResponseException(500)
        val failure = gateway.refreshHdmi() as NanoKvmDeviceControlReadResult.Failure
        assertEquals(NanoKvmDeviceControlError.Kind.CONNECTION, failure.error.kind)
    }

    @Test
    fun `ambiguous mutation is reconciled by read and never replayed`() = runTest {
        val binding = binding()
        val port = FakeDeviceControlPort().apply { failHdmiAfterApplying = true }
        val gateway = gateway(port, binding) { binding }

        val result = gateway.setHdmiEnabled(false)

        assertTrue(result is NanoKvmDeviceControlMutationResult.Reconciled)
        val reconciled = result as NanoKvmDeviceControlMutationResult.Reconciled
        assertEquals(NanoKvmDeviceControlObservation.DESIRED_STATE, reconciled.observation)
        assertEquals(NanoKvmDeviceControlError.Kind.CONNECTION, reconciled.dispatchError.kind)
        assertEquals(1, port.hdmiWrites)
        assertEquals(2, port.hdmiReads)
    }

    @Test
    fun `stale destination rejects before any read or write`() = runTest {
        val captured = binding(generation = 2)
        val current = binding(generation = 3)
        val port = FakeDeviceControlPort()
        val gateway = gateway(port, captured) { current }

        val result = gateway.setSwapSize(NanoKvmSwapSizePreset.MB_256)

        assertTrue(result is NanoKvmDeviceControlMutationResult.Rejected)
        assertEquals(
            NanoKvmDeviceControlError.Kind.SESSION_CHANGED,
            (result as NanoKvmDeviceControlMutationResult.Rejected).error.kind,
        )
        assertEquals(0, port.swapReads)
        assertEquals(0, port.swapWrites)
    }

    @Test
    fun `unknown future jiggler mode remains read-only`() = runTest {
        val binding = binding()
        val port = FakeDeviceControlPort()
        val gateway = gateway(port, binding) { binding }
        val unknown = unknownJigglerMode("future-mode")

        val result = gateway.setMouseJiggler(enabled = true, mode = unknown)

        assertTrue(result is NanoKvmDeviceControlMutationResult.Rejected)
        assertEquals(0, port.jigglerReads)
        assertEquals(0, port.jigglerWrites)
    }

    @Test
    fun `memory and swap setters use only pinned presets`() = runTest {
        val binding = binding()
        val port = FakeDeviceControlPort()
        val gateway = gateway(port, binding) { binding }

        val memory = gateway.setMemoryLimitEnabled(true)
        val swap = gateway.setSwapSize(NanoKvmSwapSizePreset.MB_128)

        assertTrue(memory is NanoKvmDeviceControlMutationResult.Applied)
        assertEquals(75L, port.memory.limitMegabytes)
        assertEquals(NanoKvmMemoryLimitPreset.TAILSCALE_RECOMMENDED, port.memory.preset)
        assertTrue(swap is NanoKvmDeviceControlMutationResult.Applied)
        assertEquals(128L, port.swap.sizeMegabytes)
        assertEquals(1, port.memoryWrites)
        assertEquals(1, port.swapWrites)
    }

    @Test
    fun `bounded future memory and swap values remain read-only`() = runTest {
        val binding = binding()
        val port = FakeDeviceControlPort().apply {
            memory = NanoKvmMemoryLimitState(
                enabled = true,
                limitMegabytes = 96L,
                preset = null,
            )
            swap = NanoKvmSwapState(sizeMegabytes = 384L, preset = null)
        }
        val gateway = gateway(port, binding) { binding }

        val memory = gateway.setMemoryLimitEnabled(false)
        val swap = gateway.setSwapSize(NanoKvmSwapSizePreset.MB_256)

        assertTrue(memory is NanoKvmDeviceControlMutationResult.Rejected)
        assertTrue(swap is NanoKvmDeviceControlMutationResult.Rejected)
        assertEquals(0, port.memoryWrites)
        assertEquals(0, port.swapWrites)
    }

    @Test
    fun `disruptive commands are one shot and ambiguous failures are not replayed`() = runTest {
        val binding = binding()
        val port = FakeDeviceControlPort().apply { failTls = true }
        val gateway = gateway(port, binding) { binding }

        val reset = gateway.resetHdmi()
        val tls = gateway.enableApplianceTls()

        assertTrue(reset is NanoKvmDeviceControlMutationResult.DisruptiveCommandAccepted)
        assertTrue(tls is NanoKvmDeviceControlMutationResult.Indeterminate)
        assertEquals(1, port.hdmiResetWrites)
        assertEquals(1, port.tlsWrites)
    }

    @Test
    fun `safe diagnostics expose neither destination nor transport message`() {
        val error = NanoKvmDeviceControlError(NanoKvmDeviceControlError.Kind.CONNECTION)

        assertEquals("NanoKvmDeviceControlError(kind=CONNECTION)", error.toString())
        assertEquals(false, error.toString().contains("192.168"))
    }

    private fun binding(generation: Long = 1) = NanoKvmSessionBinding(
        profileId = "office",
        authority = "192.0.2.250",
        sessionGeneration = generation,
    )

    private fun gateway(
        port: NanoKvmDeviceControlPort,
        binding: NanoKvmSessionBinding,
        current: () -> NanoKvmSessionBinding?,
    ) = NanoKvmDeviceControlGateway(port, binding, current)

    /** Simulates the public protocol type returned by a future server without widening its ctor. */
    private fun unknownJigglerMode(value: String): NanoKvmMouseJigglerMode =
        NanoKvmMouseJigglerMode.Other::class.java
            .getDeclaredConstructor(String::class.java)
            .apply { isAccessible = true }
            .newInstance(value)
}

private class FakeDeviceControlPort : NanoKvmDeviceControlPort {
    var hdmi = NanoKvmHdmiState(enabled = true)
    var jiggler = NanoKvmMouseJigglerState(
        enabled = false,
        mode = NanoKvmMouseJigglerMode.Relative,
    )
    var memory = NanoKvmMemoryLimitState(
        enabled = false,
        limitMegabytes = 0,
        preset = null,
    )
    var swap = NanoKvmSwapState(
        sizeMegabytes = 0,
        preset = NanoKvmSwapSizePreset.DISABLED,
    )
    var hdmiReads = 0
    var hdmiWrites = 0
    var hdmiResetWrites = 0
    var jigglerReads = 0
    var jigglerWrites = 0
    var memoryReads = 0
    var memoryWrites = 0
    var swapReads = 0
    var swapWrites = 0
    var tlsWrites = 0
    var failHdmiAfterApplying = false
    var hdmiReadFailure: Throwable? = null
    var failTls = false

    override suspend fun hdmiState(): NanoKvmHdmiState {
        hdmiReads++
        hdmiReadFailure?.let { throw it }
        return hdmi
    }

    override suspend fun setHdmiEnabled(enabled: Boolean) {
        hdmiWrites++
        hdmi = NanoKvmHdmiState(enabled)
        if (failHdmiAfterApplying) throw IOException("ambiguous private endpoint")
    }

    override suspend fun resetHdmi() {
        hdmiResetWrites++
    }

    override suspend fun mouseJigglerState(): NanoKvmMouseJigglerState =
        jiggler.also { jigglerReads++ }

    override suspend fun enableMouseJiggler(mode: NanoKvmMouseJigglerMode) {
        jigglerWrites++
        jiggler = NanoKvmMouseJigglerState(enabled = true, mode = mode)
    }

    override suspend fun disableMouseJiggler() {
        jigglerWrites++
        jiggler = NanoKvmMouseJigglerState(
            enabled = false,
            mode = NanoKvmMouseJigglerMode.Relative,
        )
    }

    override suspend fun memoryLimitState(): NanoKvmMemoryLimitState =
        memory.also { memoryReads++ }

    override suspend fun setMemoryLimit(preset: NanoKvmMemoryLimitPreset) {
        memoryWrites++
        memory = NanoKvmMemoryLimitState(
            enabled = true,
            limitMegabytes = preset.megabytes,
            preset = preset,
        )
    }

    override suspend fun disableMemoryLimit() {
        memoryWrites++
        memory = NanoKvmMemoryLimitState(enabled = false, limitMegabytes = 0, preset = null)
    }

    override suspend fun swapState(): NanoKvmSwapState = swap.also { swapReads++ }

    override suspend fun setSwapSize(preset: NanoKvmSwapSizePreset) {
        swapWrites++
        swap = NanoKvmSwapState(sizeMegabytes = preset.megabytes, preset = preset)
    }

    override suspend fun enableApplianceTls() {
        tlsWrites++
        if (failTls) throw IOException("ambiguous private endpoint")
    }
}

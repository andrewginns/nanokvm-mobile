package org.nanokvm.mobile.runtime

import org.nanokvm.protocol.NanoKvmApi
import org.nanokvm.protocol.NanoKvmHdmiState
import org.nanokvm.protocol.NanoKvmMemoryLimitPreset
import org.nanokvm.protocol.NanoKvmMemoryLimitState
import org.nanokvm.protocol.NanoKvmMouseJigglerMode
import org.nanokvm.protocol.NanoKvmMouseJigglerState
import org.nanokvm.protocol.NanoKvmSwapSizePreset
import org.nanokvm.protocol.NanoKvmSwapState

/**
 * Testable, one-dispatch boundary for low-frequency appliance device controls.
 *
 * The domain gateway owns all preflight and reconciliation reads. No mutating method here retries
 * or silently converts an explicit setter into a toggle.
 */
internal interface NanoKvmDeviceControlPort {
    suspend fun hdmiState(): NanoKvmHdmiState
    suspend fun setHdmiEnabled(enabled: Boolean)
    suspend fun resetHdmi()

    suspend fun mouseJigglerState(): NanoKvmMouseJigglerState
    suspend fun enableMouseJiggler(mode: NanoKvmMouseJigglerMode)
    suspend fun disableMouseJiggler()

    suspend fun memoryLimitState(): NanoKvmMemoryLimitState
    suspend fun setMemoryLimit(preset: NanoKvmMemoryLimitPreset)
    suspend fun disableMemoryLimit()

    suspend fun swapState(): NanoKvmSwapState
    suspend fun setSwapSize(preset: NanoKvmSwapSizePreset)

    /** Enables TLS exactly once. The Android app deliberately exposes no disable operation. */
    suspend fun enableApplianceTls()
}

internal class NanoKvmProtocolDeviceControlPort(
    private val api: NanoKvmApi,
) : NanoKvmDeviceControlPort {
    override suspend fun hdmiState(): NanoKvmHdmiState = api.hdmiState()
    override suspend fun setHdmiEnabled(enabled: Boolean) = api.setHdmiEnabled(enabled)
    override suspend fun resetHdmi() = api.resetHdmi()

    override suspend fun mouseJigglerState(): NanoKvmMouseJigglerState =
        api.mouseJigglerState()

    override suspend fun enableMouseJiggler(mode: NanoKvmMouseJigglerMode) =
        api.enableMouseJiggler(mode)

    override suspend fun disableMouseJiggler() = api.disableMouseJiggler()

    override suspend fun memoryLimitState(): NanoKvmMemoryLimitState = api.memoryLimitState()
    override suspend fun setMemoryLimit(preset: NanoKvmMemoryLimitPreset) =
        api.setMemoryLimit(preset)

    override suspend fun disableMemoryLimit() = api.disableMemoryLimit()

    override suspend fun swapState(): NanoKvmSwapState = api.swapState()
    override suspend fun setSwapSize(preset: NanoKvmSwapSizePreset) = api.setSwapSize(preset)

    override suspend fun enableApplianceTls() = api.enableApplianceTls()
}

internal fun AuthenticatedNanoKvmSession.createDeviceControlGateway(
    sessionGeneration: Long,
    currentBinding: () -> NanoKvmSessionBinding?,
): NanoKvmDeviceControlGateway {
    val captured = NanoKvmSessionBinding(profileId, authority, sessionGeneration)
    return NanoKvmDeviceControlGateway(
        port = NanoKvmProtocolDeviceControlPort(client.api),
        binding = captured,
        currentBinding = currentBinding,
    )
}

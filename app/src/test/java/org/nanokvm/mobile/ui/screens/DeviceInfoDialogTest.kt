package org.nanokvm.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.R
import org.nanokvm.protocol.NanoKvmApplicationVersion
import org.nanokvm.protocol.NanoKvmCapability
import org.nanokvm.protocol.NanoKvmCapabilityEvidence
import org.nanokvm.protocol.NanoKvmCapabilitySupport
import org.nanokvm.protocol.NanoKvmCapabilityUnknownReason

class DeviceInfoDialogTest {
    @Test
    fun `capability rows preserve all three support states and capability identity`() {
        val version = NanoKvmApplicationVersion(2, 4, 3)
        val assessments: MutableMap<NanoKvmCapability, NanoKvmCapabilitySupport> =
            NanoKvmCapability.entries.associateWith {
            NanoKvmCapabilitySupport.Unknown(NanoKvmCapabilityUnknownReason.RUNTIME_PROBE_REQUIRED)
            }.toMutableMap()
        assessments[NanoKvmCapability.VM_INFORMATION] = NanoKvmCapabilitySupport.Supported(
            NanoKvmCapabilityEvidence.Endpoint("/api/vm/info"),
        )
        assessments[NanoKvmCapability.HID_LEADER_KEY] = NanoKvmCapabilitySupport.Unsupported(
            NanoKvmCapabilityEvidence.VersionFloor(version, NanoKvmApplicationVersion(2, 5, 0)),
        )
        val rows = deviceCapabilityDisplayRows(assessments)

        assertEquals(NanoKvmCapability.entries.size, rows.size)
        assertEquals(
            DeviceCapabilityDisplayState.Available,
            rows.single { it.capability == NanoKvmCapability.VM_INFORMATION }.state,
        )
        assertEquals(
            DeviceCapabilityDisplayState.Unavailable,
            rows.single { it.capability == NanoKvmCapability.HID_LEADER_KEY }.state,
        )
        assertEquals(
            DeviceCapabilityDisplayState.RuntimeCheck,
            rows.single { it.capability == NanoKvmCapability.TERMINAL }.state,
        )
        assertEquals(
            R.string.console_device_capability_saved_hid_shortcuts,
            capabilityLabelResource(NanoKvmCapability.SAVED_HID_SHORTCUTS),
        )
        assertEquals(
            R.string.console_device_capability_pcie_hdmi_control,
            capabilityLabelResource(NanoKvmCapability.PCIE_HDMI_CONTROL),
        )
        assertEquals(
            R.string.console_device_capability_direct_h264,
            capabilityLabelResource(NanoKvmCapability.DIRECT_H264),
        )
        assertTrue(rows.first().state.ordinal <= rows.last().state.ordinal)
    }

    @Test
    fun `every capability has an explicit resource backed label`() {
        assertEquals(
            NanoKvmCapability.entries.size,
            NanoKvmCapability.entries.map(::capabilityLabelResource).distinct().size,
        )
    }

    @Test
    fun `absent capability snapshot produces no inferred support`() {
        assertTrue(deviceCapabilityDisplayRows(null).isEmpty())
    }
}

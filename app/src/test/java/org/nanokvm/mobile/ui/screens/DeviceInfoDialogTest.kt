package org.nanokvm.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.NanoKvmApplicationVersion
import org.nanokvm.protocol.NanoKvmCapability
import org.nanokvm.protocol.NanoKvmCapabilityEvidence
import org.nanokvm.protocol.NanoKvmCapabilitySupport
import org.nanokvm.protocol.NanoKvmCapabilityUnknownReason

class DeviceInfoDialogTest {
    @Test
    fun `capability rows preserve all three support states and use readable labels`() {
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
            "Saved HID shortcuts",
            rows.single { it.capability == NanoKvmCapability.SAVED_HID_SHORTCUTS }.label,
        )
        assertEquals(
            "PCIe HDMI control",
            capabilityDisplayLabel(NanoKvmCapability.PCIE_HDMI_CONTROL),
        )
        assertEquals(
            "Direct H.264",
            capabilityDisplayLabel(NanoKvmCapability.DIRECT_H264),
        )
        assertTrue(rows.first().state.ordinal <= rows.last().state.ordinal)
    }

    @Test
    fun `absent capability snapshot produces no inferred support`() {
        assertTrue(deviceCapabilityDisplayRows(null).isEmpty())
    }
}

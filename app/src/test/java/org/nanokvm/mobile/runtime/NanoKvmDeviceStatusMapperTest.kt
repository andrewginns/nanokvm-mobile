package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.GpioStatus
import org.nanokvm.protocol.HardwareInfo
import org.nanokvm.protocol.NetworkInterface
import org.nanokvm.protocol.VmInfo

class NanoKvmDeviceStatusMapperTest {
    @Test
    fun `device metadata is bounded filtered and complete without entering diagnostics`() {
        val interfaces = buildList {
            add(NetworkInterface(" eth0\n", " 192.0.2.250\n", " v4 ", " wired "))
            add(NetworkInterface(" eth0\n", " 192.0.2.250\n", " v4 ", " wired "))
            add(NetworkInterface(name = "empty", addr = " \n "))
            repeat(20) { index ->
                add(NetworkInterface("usb$index", "2001:db8::$index", "v6", "usb"))
            }
        }
        val status = VmInfo(
            ips = interfaces,
            mdns = " nano\u202ekvm.local ",
            image = " image\u2066-2026.07 ",
            application = " 2.4.3\n ",
            deviceKey = "k".repeat(300),
        ).toNanoKvmDeviceStatus(
            hardware = HardwareInfo(" Full\u0000 "),
            gpio = GpioStatus(powerOn = true, hardDriveActive = false),
        )

        assertEquals("2.4.3", status.applicationVersion)
        assertEquals("image-2026.07", status.imageVersion)
        assertEquals("Full", status.hardwareVersion)
        assertEquals("nanokvm.local", status.mdnsName)
        assertEquals(256, status.deviceKey?.length)
        assertTrue(status.networkInterfaces.size <= 16)
        assertEquals(status.networkAddresses.distinct(), status.networkAddresses)
        assertEquals("eth0", status.networkInterfaces.first().name)
        assertEquals("192.0.2.250", status.networkInterfaces.first().address)
        assertEquals(true, status.powerOn)
        assertEquals(false, status.hardDriveActive)
        assertFalse(status.toString().contains("192.0.2.250"))
        assertFalse(status.toString().contains("k".repeat(20)))
    }

    @Test
    fun `blank optional metadata stays absent`() {
        val status = VmInfo(application = "2.3.2").toNanoKvmDeviceStatus(null, null)

        assertEquals("2.3.2", status.applicationVersion)
        assertNull(status.imageVersion)
        assertNull(status.hardwareVersion)
        assertNull(status.deviceKey)
        assertNull(status.mdnsName)
        assertTrue(status.networkInterfaces.isEmpty())
        assertNull(status.powerOn)
    }
}

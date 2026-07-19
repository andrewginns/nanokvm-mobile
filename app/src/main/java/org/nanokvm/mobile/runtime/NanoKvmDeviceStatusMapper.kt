package org.nanokvm.mobile.runtime

import org.nanokvm.protocol.GpioStatus
import org.nanokvm.protocol.HardwareInfo
import org.nanokvm.protocol.VmInfo

private const val MAXIMUM_NETWORK_INTERFACES = 16
private const val MAXIMUM_VERSION_CHARACTERS = 64
private const val MAXIMUM_INTERFACE_FIELD_CHARACTERS = 128
private const val MAXIMUM_DEVICE_KEY_CHARACTERS = 256

/** Converts untrusted appliance metadata into bounded display-only session state. */
internal fun VmInfo.toNanoKvmDeviceStatus(
    hardware: HardwareInfo?,
    gpio: GpioStatus?,
): NanoKvmDeviceStatus {
    val interfaces = ips.asSequence()
        .mapNotNull { reported ->
            val address = reported.addr.safeReportedValue(MAXIMUM_INTERFACE_FIELD_CHARACTERS)
                ?: return@mapNotNull null
            NanoKvmNetworkInterfaceStatus(
                name = reported.name.safeReportedValue(MAXIMUM_INTERFACE_FIELD_CHARACTERS),
                address = address,
                version = reported.version.safeReportedValue(MAXIMUM_VERSION_CHARACTERS),
                type = reported.type.safeReportedValue(MAXIMUM_INTERFACE_FIELD_CHARACTERS),
            )
        }
        .distinctBy { listOf(it.name, it.address, it.version, it.type) }
        .take(MAXIMUM_NETWORK_INTERFACES)
        .toList()
    return NanoKvmDeviceStatus(
        applicationVersion = application.safeReportedValue(MAXIMUM_VERSION_CHARACTERS).orEmpty(),
        imageVersion = image.safeReportedValue(MAXIMUM_VERSION_CHARACTERS),
        hardwareVersion = hardware?.version.safeReportedValue(MAXIMUM_VERSION_CHARACTERS),
        deviceKey = deviceKey.safeReportedValue(MAXIMUM_DEVICE_KEY_CHARACTERS),
        mdnsName = mdns.safeReportedValue(MAXIMUM_INTERFACE_FIELD_CHARACTERS),
        networkAddresses = interfaces.map(NanoKvmNetworkInterfaceStatus::address).distinct(),
        networkInterfaces = interfaces,
        powerOn = gpio?.powerOn,
        hardDriveActive = gpio?.hardDriveActive,
    )
}

private fun String?.safeReportedValue(maximumCharacters: Int): String? = this
    ?.trim()
    ?.filterNot(Char::isUnsafeDeviceMetadataCharacter)
    ?.take(maximumCharacters)
    ?.takeIf(String::isNotBlank)

private fun Char.isUnsafeDeviceMetadataCharacter(): Boolean =
    isISOControl() || this == '\u061c' || this == '\u200e' || this == '\u200f' ||
        this in '\u202a'..'\u202e' || this in '\u2066'..'\u2069'

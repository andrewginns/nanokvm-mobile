package org.nanokvm.mobile.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.NanoKvmNetworkInterfaceStatus
import org.nanokvm.protocol.NanoKvmCapability
import org.nanokvm.protocol.NanoKvmCapabilitySupport

internal enum class DeviceCapabilityDisplayState {
    Available,
    Unavailable,
    RuntimeCheck,
}

internal data class DeviceCapabilityDisplayRow(
    val capability: NanoKvmCapability,
    val state: DeviceCapabilityDisplayState,
)

internal fun deviceCapabilityDisplayRows(
    capabilities: Map<NanoKvmCapability, NanoKvmCapabilitySupport>?,
): List<DeviceCapabilityDisplayRow> = capabilities
    ?.map { (capability, support) ->
        DeviceCapabilityDisplayRow(
            capability = capability,
            state = when (support) {
                is NanoKvmCapabilitySupport.Supported -> DeviceCapabilityDisplayState.Available
                is NanoKvmCapabilitySupport.Unsupported -> DeviceCapabilityDisplayState.Unavailable
                is NanoKvmCapabilitySupport.Unknown -> DeviceCapabilityDisplayState.RuntimeCheck
            },
        )
    }
    ?.sortedWith(
        compareBy<DeviceCapabilityDisplayRow>({ it.state.ordinal }, { it.capability.ordinal }),
    )
    .orEmpty()

@StringRes
internal fun capabilityLabelResource(capability: NanoKvmCapability): Int = when (capability) {
    NanoKvmCapability.VM_INFORMATION -> R.string.console_device_capability_vm_information
    NanoKvmCapability.HARDWARE_INFORMATION ->
        R.string.console_device_capability_hardware_information
    NanoKvmCapability.GPIO_STATUS -> R.string.console_device_capability_gpio_status
    NanoKvmCapability.GPIO_CONTROL -> R.string.console_device_capability_gpio_control
    NanoKvmCapability.STREAM_CONFIGURATION ->
        R.string.console_device_capability_stream_configuration
    NanoKvmCapability.DIRECT_H264 -> R.string.console_device_capability_direct_h264
    NanoKvmCapability.STANDARD_HID_WEBSOCKET ->
        R.string.console_device_capability_standard_hid_websocket
    NanoKvmCapability.SERVER_BATCH_PASTE ->
        R.string.console_device_capability_server_batch_paste
    NanoKvmCapability.SAVED_HID_SHORTCUTS ->
        R.string.console_device_capability_saved_hid_shortcuts
    NanoKvmCapability.HID_LEADER_KEY -> R.string.console_device_capability_hid_leader_key
    NanoKvmCapability.MOUSE_BACK_FORWARD ->
        R.string.console_device_capability_mouse_back_forward
    NanoKvmCapability.MEMORY_LIMIT_CONFIGURATION ->
        R.string.console_device_capability_memory_limit_configuration
    NanoKvmCapability.PCIE_HDMI_RESET -> R.string.console_device_capability_pcie_hdmi_reset
    NanoKvmCapability.PCIE_HDMI_CONTROL ->
        R.string.console_device_capability_pcie_hdmi_control
    NanoKvmCapability.MOUSE_JIGGLER -> R.string.console_device_capability_mouse_jiggler
    NanoKvmCapability.SWAP_CONFIGURATION ->
        R.string.console_device_capability_swap_configuration
    NanoKvmCapability.TLS_ENABLE -> R.string.console_device_capability_tls_enable
    NanoKvmCapability.VIRTUAL_USB_DEVICE_CONFIGURATION ->
        R.string.console_device_capability_virtual_usb_device_configuration
    NanoKvmCapability.OFFLINE_UPDATE -> R.string.console_device_capability_offline_update
    NanoKvmCapability.VIRTUAL_MEDIA_UPLOAD ->
        R.string.console_device_capability_virtual_media_upload
    NanoKvmCapability.VIRTUAL_MEDIA_MOUNT ->
        R.string.console_device_capability_virtual_media_mount
    NanoKvmCapability.WAKE_ON_LAN -> R.string.console_device_capability_wake_on_lan
    NanoKvmCapability.WIFI_CONFIGURATION ->
        R.string.console_device_capability_wifi_configuration
    NanoKvmCapability.TAILSCALE_EXTENSION ->
        R.string.console_device_capability_tailscale_extension
    NanoKvmCapability.OLED_CONFIGURATION ->
        R.string.console_device_capability_oled_configuration
    NanoKvmCapability.TERMINAL -> R.string.console_device_capability_terminal
    NanoKvmCapability.SCRIPT_RUNNER -> R.string.console_device_capability_script_runner
    NanoKvmCapability.AUTOSTART_SCRIPTS ->
        R.string.console_device_capability_autostart_scripts
    NanoKvmCapability.RESOLUTION_640_X_480 ->
        R.string.console_device_capability_resolution_640_x_480
    NanoKvmCapability.PICOCLAW -> R.string.console_device_capability_picoclaw
    NanoKvmCapability.DNS_CONFIGURATION ->
        R.string.console_device_capability_dns_configuration
    NanoKvmCapability.FRENCH_KEYBOARD_MAPPING ->
        R.string.console_device_capability_french_keyboard_mapping
    NanoKvmCapability.CAPTURE_STATUS_REPORTING ->
        R.string.console_device_capability_capture_status_reporting
    NanoKvmCapability.LT6911D_CAPTURE -> R.string.console_device_capability_lt6911d_capture
}

@Composable
internal fun DeviceInfoDialog(
    session: BackendSession,
    onDismiss: () -> Unit,
) {
    val capabilityRows = deviceCapabilityDisplayRows(session.capabilities?.all)
    val availableCount = capabilityRows.count { it.state == DeviceCapabilityDisplayState.Available }
    val unavailableCount = capabilityRows.count { it.state == DeviceCapabilityDisplayState.Unavailable }
    val runtimeCheckCount = capabilityRows.count { it.state == DeviceCapabilityDisplayState.RuntimeCheck }

    AlertDialog(
        modifier = Modifier.testTag("device-info-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.console_device_details)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.console_device_identity),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item {
                    DeviceInfoValue(
                        stringResource(R.string.console_device_application_version),
                        session.deviceStatus.applicationVersion,
                    )
                }
                item {
                    DeviceInfoValue(
                        stringResource(R.string.console_device_image_version),
                        session.deviceStatus.imageVersion,
                    )
                }
                item {
                    DeviceInfoValue(
                        stringResource(R.string.console_device_hardware_revision),
                        session.deviceStatus.hardwareVersion,
                    )
                }
                item {
                    DeviceInfoValue(
                        stringResource(R.string.console_device_key),
                        session.deviceStatus.deviceKey,
                    )
                }
                item {
                    DeviceInfoValue(
                        stringResource(R.string.console_device_mdns_name),
                        session.deviceStatus.mdnsName,
                    )
                }
                item {
                    Text(
                        stringResource(R.string.console_device_network_interfaces),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (session.deviceStatus.networkInterfaces.isNotEmpty()) {
                    itemsIndexed(
                        session.deviceStatus.networkInterfaces,
                        key = { index, network -> "$index:${network.address}" },
                    ) { index, network ->
                        DeviceNetworkInterfaceValue(index, network)
                    }
                } else {
                    item {
                        DeviceInfoValue(
                            label = stringResource(R.string.console_device_network_addresses),
                            value = session.deviceStatus.networkAddresses
                                .takeIf(List<String>::isNotEmpty)
                                ?.joinToString("\n"),
                        )
                    }
                }
                item { HorizontalDivider() }
                item {
                    Text(
                        stringResource(R.string.console_device_capabilities),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (session.capabilities == null) {
                    item {
                        Text(
                            stringResource(R.string.console_device_capabilities_not_probed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    item {
                        Text(
                            stringResource(
                                R.string.console_device_capability_summary,
                                pluralStringResource(
                                    R.plurals.console_device_capability_available_count,
                                    availableCount,
                                    availableCount,
                                ),
                                pluralStringResource(
                                    R.plurals.console_device_capability_unavailable_count,
                                    unavailableCount,
                                    unavailableCount,
                                ),
                                pluralStringResource(
                                    R.plurals.console_device_capability_runtime_check_count,
                                    runtimeCheckCount,
                                    runtimeCheckCount,
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("device-capability-summary"),
                        )
                    }
                    items(capabilityRows, key = { it.capability.name }) { row ->
                        DeviceCapabilityRow(row)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.console_close)) }
        },
    )
}

@Composable
private fun DeviceInfoValue(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                value?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.console_device_not_reported),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DeviceNetworkInterfaceValue(
    index: Int,
    network: NanoKvmNetworkInterfaceStatus,
) {
    val label = network.name ?: stringResource(
        R.string.console_device_network_interface_fallback,
        index + 1,
    )
    val metadata = listOfNotNull(network.type, network.version).joinToString(" · ")
    Column(
        modifier = Modifier.testTag("device-network-interface-$index"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (metadata.isNotBlank()) {
            Text(
                metadata,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
            Text(network.address, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DeviceCapabilityRow(row: DeviceCapabilityDisplayRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device-capability-${row.capability.name.lowercase()}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(capabilityLabelResource(row.capability)),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            when (row.state) {
                DeviceCapabilityDisplayState.Available ->
                    stringResource(R.string.console_device_capability_available)
                DeviceCapabilityDisplayState.Unavailable ->
                    stringResource(R.string.console_device_capability_unavailable)
                DeviceCapabilityDisplayState.RuntimeCheck ->
                    stringResource(R.string.console_device_capability_unknown)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

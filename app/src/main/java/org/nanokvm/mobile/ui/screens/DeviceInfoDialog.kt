package org.nanokvm.mobile.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val label: String,
    val state: DeviceCapabilityDisplayState,
)

internal fun deviceCapabilityDisplayRows(
    capabilities: Map<NanoKvmCapability, NanoKvmCapabilitySupport>?,
): List<DeviceCapabilityDisplayRow> = capabilities
    ?.map { (capability, support) ->
        DeviceCapabilityDisplayRow(
            capability = capability,
            label = capabilityDisplayLabel(capability),
            state = when (support) {
                is NanoKvmCapabilitySupport.Supported -> DeviceCapabilityDisplayState.Available
                is NanoKvmCapabilitySupport.Unsupported -> DeviceCapabilityDisplayState.Unavailable
                is NanoKvmCapabilitySupport.Unknown -> DeviceCapabilityDisplayState.RuntimeCheck
            },
        )
    }
    ?.sortedWith(compareBy<DeviceCapabilityDisplayRow>({ it.state.ordinal }, { it.label }))
    .orEmpty()

internal fun capabilityDisplayLabel(capability: NanoKvmCapability): String = capability.name
    .lowercase()
    .split('_')
    .mapIndexed { index, word ->
        when (word) {
            "dns" -> "DNS"
            "gpio" -> "GPIO"
            "h264" -> "H.264"
            "hdmi" -> "HDMI"
            "hid" -> "HID"
            "lt6911d" -> "LT6911D"
            "oled" -> "OLED"
            "pcie" -> "PCIe"
            "picoclaw" -> "PicoClaw"
            "tls" -> "TLS"
            "usb" -> "USB"
            "wifi" -> "Wi-Fi"
            else -> if (index == 0) word.replaceFirstChar(Char::uppercaseChar) else word
        }
    }
    .joinToString(" ")

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
                                availableCount,
                                unavailableCount,
                                runtimeCheckCount,
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
            row.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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

package org.nanokvm.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.AdministrationDnsMode
import org.nanokvm.mobile.runtime.AdministrationMouseJigglerSelection
import org.nanokvm.mobile.runtime.AdministrationNoticeKind
import org.nanokvm.mobile.runtime.AdministrationOledPreset
import org.nanokvm.mobile.runtime.AdministrationSwapPreset
import org.nanokvm.mobile.runtime.AdministrationTailscaleCommand
import org.nanokvm.mobile.runtime.AdministrationTailscaleSelection
import org.nanokvm.mobile.runtime.AdministrationUiState
import org.nanokvm.mobile.runtime.ApprovedAdministrationDestination
import org.nanokvm.mobile.runtime.ConsoleCommandSink

@Composable
internal fun AdministrationDialog(
    destinationLabel: String,
    destination: ApprovedAdministrationDestination,
    state: AdministrationUiState,
    commands: ConsoleCommandSink,
    passwordChangeInProgress: Boolean = false,
    canProtectPassword: Boolean = false,
    onPasswordChange: (
        destination: ApprovedAdministrationDestination,
        username: String,
        password: CharArray,
        saveProtectedCredential: Boolean,
    ) -> Unit = { _, _, password, _ -> password.fill('\u0000') },
    offlineUpdateAvailable: Boolean = false,
    onOfflineUpdate: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var pendingAction by remember { mutableStateOf<PendingAdministrationAction?>(null) }
    var hostnameInput by remember { mutableStateOf("") }
    var titleInput by remember { mutableStateOf("") }
    var dnsInput by remember { mutableStateOf("") }
    var hostnameEdited by remember { mutableStateOf(false) }
    var titleEdited by remember { mutableStateOf(false) }
    var dnsEdited by remember { mutableStateOf(false) }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var showPasswordChange by remember { mutableStateOf(false) }

    LaunchedEffect(destination) {
        // Never carry a reviewed mutation into another authenticated generation.
        pendingAction?.clear()
        pendingAction = null
        wifiPassword = ""
        showPasswordChange = false
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingAction?.clear()
            wifiPassword = ""
        }
    }

    LaunchedEffect(state.hostname) {
        if (!hostnameEdited) hostnameInput = state.hostname.orEmpty()
    }
    LaunchedEffect(state.webTitle, state.webTitleIsDefault) {
        if (!titleEdited) {
            titleInput = if (state.webTitleIsDefault == false) state.webTitle.orEmpty() else ""
        }
    }
    LaunchedEffect(state.dns) {
        if (!dnsEdited) dnsInput = state.dns?.configuredServers?.joinToString(", ").orEmpty()
    }

    val controlsEnabled = state.available && !state.loading && !state.operationInProgress &&
        !passwordChangeInProgress
    AlertDialog(
        modifier = Modifier.fillMaxWidth(),
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.console_administration_title),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = commands::refreshAdministration,
                    enabled = !state.operationInProgress,
                    modifier = Modifier.testTag("administration-refresh"),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(
                            R.string.console_administration_refresh,
                        ),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("administration-surface"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.loading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.console_admin_loading))
                    }
                }
                state.notice?.let { notice ->
                    AdministrationNoticeCard(notice.kind, notice.message)
                }

                AdministrationSection(stringResource(R.string.console_admin_account)) {
                    val account = state.account
                    if (account == null) {
                        AdministrationUnavailable()
                    } else {
                        Text(stringResource(R.string.console_admin_username, account.username))
                        Text(
                            stringResource(
                                if (account.passwordUpdated) {
                                    R.string.console_admin_password_updated
                                } else {
                                    R.string.console_admin_password_not_updated
                                },
                            ),
                        )
                        OutlinedButton(
                            onClick = { showPasswordChange = true },
                            enabled = controlsEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("administration-password-change"),
                        ) {
                            Text(stringResource(R.string.console_admin_password_change_disabled))
                        }
                        Text(
                            stringResource(R.string.console_admin_password_change_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AdministrationSection(stringResource(R.string.console_admin_software)) {
                    val updates = state.updates
                    if (updates == null) {
                        AdministrationUnavailable()
                    } else {
                        Text(
                            stringResource(
                                R.string.console_admin_version_current,
                                updates.currentVersion,
                            ),
                        )
                        Text(
                            updates.latestVersion?.let {
                                stringResource(R.string.console_admin_version_latest, it)
                            } ?: stringResource(R.string.console_admin_version_unknown),
                        )
                        Text(
                            stringResource(
                                if (updates.previewUpdatesEnabled) {
                                    R.string.console_admin_preview_updates_on
                                } else {
                                    R.string.console_admin_preview_updates_off
                                },
                            ),
                        )
                        OutlinedButton(
                            onClick = {
                                pendingAction = PendingAdministrationAction.PreviewUpdates(
                                    !updates.previewUpdatesEnabled,
                                )
                            },
                            enabled = controlsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (updates.previewUpdatesEnabled) {
                                        R.string.console_admin_disable_preview
                                    } else {
                                        R.string.console_admin_enable_preview
                                    },
                                ),
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                updates.latestVersion?.let {
                                    pendingAction = PendingAdministrationAction.OnlineUpdate(it)
                                }
                            },
                            enabled = controlsEnabled && updates.updateAvailable &&
                                updates.latestVersion != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.console_admin_install_update))
                        }
                    }
                    if (offlineUpdateAvailable) {
                        OutlinedButton(
                            onClick = onOfflineUpdate,
                            enabled = controlsEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("administration-offline-update"),
                        ) {
                            Text(stringResource(R.string.offline_update_open_action))
                        }
                    }
                }

                AdministrationSection(stringResource(R.string.console_admin_system)) {
                    OutlinedButton(
                        onClick = { pendingAction = PendingAdministrationAction.Reboot },
                        enabled = controlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.console_admin_reboot))
                    }
                    Text(
                        stringResource(R.string.console_admin_oled),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val oled = state.oled
                    when {
                        oled == null -> AdministrationUnavailable()
                        !oled.exists -> Text(stringResource(R.string.console_admin_oled_absent))
                        else -> {
                            if (oled.preset == null) {
                                Text(
                                    stringResource(
                                        R.string.console_admin_oled_unknown,
                                        oled.sleepSeconds,
                                    ),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AdministrationOledPreset.entries.forEach { preset ->
                                    FilterChip(
                                        selected = preset == oled.preset,
                                        onClick = {
                                            pendingAction =
                                                PendingAdministrationAction.OledSleep(preset)
                                        },
                                        enabled = controlsEnabled,
                                        label = { Text(preset.displayLabel()) },
                                    )
                                }
                            }
                        }
                    }
                }

                AdministrationSection(
                    stringResource(R.string.console_admin_device_controls),
                ) {
                    Text(
                        stringResource(R.string.console_admin_hdmi_capture),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.hdmiEnabled?.let { enabled ->
                        Text(
                            stringResource(
                                if (enabled) R.string.console_admin_hdmi_on
                                else R.string.console_admin_hdmi_off,
                            ),
                        )
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    pendingAction = PendingAdministrationAction.Hdmi(!enabled)
                                },
                                enabled = controlsEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("administration-hdmi-toggle"),
                            ) {
                                Text(
                                    stringResource(
                                        if (enabled) R.string.console_admin_disable_hdmi
                                        else R.string.console_admin_enable_hdmi,
                                    ),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    pendingAction = PendingAdministrationAction.ResetHdmi
                                },
                                enabled = controlsEnabled,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.console_admin_reset_hdmi))
                            }
                        }
                    } ?: AdministrationUnavailable()

                    Text(
                        stringResource(R.string.console_admin_mouse_jiggler),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.mouseJiggler?.let { jiggler ->
                        Text(
                            if (jiggler.selection == AdministrationMouseJigglerSelection.Other) {
                                stringResource(
                                    R.string.console_admin_unknown_reported_value,
                                    jiggler.reportedMode.orEmpty(),
                                )
                            } else {
                                jiggler.selection.displayLabel()
                            },
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                AdministrationMouseJigglerSelection.Off,
                                AdministrationMouseJigglerSelection.Relative,
                                AdministrationMouseJigglerSelection.Absolute,
                            ).forEach { selection ->
                                FilterChip(
                                    selected = jiggler.selection == selection,
                                    onClick = {
                                        pendingAction =
                                            PendingAdministrationAction.MouseJiggler(selection)
                                    },
                                    enabled = controlsEnabled &&
                                        jiggler.selection !=
                                        AdministrationMouseJigglerSelection.Other,
                                    label = { Text(selection.displayLabel()) },
                                )
                            }
                        }
                    } ?: AdministrationUnavailable()

                    Text(
                        stringResource(R.string.console_admin_memory_limit),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.memoryLimit?.let { memory ->
                        Text(
                            stringResource(
                                if (memory.enabled) R.string.console_admin_memory_on
                                else R.string.console_admin_memory_off,
                                memory.limitMegabytes,
                            ),
                        )
                        if (!memory.writable) {
                            Text(
                                stringResource(R.string.console_admin_unknown_read_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                pendingAction =
                                    PendingAdministrationAction.MemoryLimit(!memory.enabled)
                            },
                            enabled = controlsEnabled && memory.writable,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (memory.enabled) {
                                        R.string.console_admin_disable_memory_limit
                                    } else {
                                        R.string.console_admin_enable_memory_limit
                                    },
                                ),
                            )
                        }
                    } ?: AdministrationUnavailable()

                    Text(
                        stringResource(R.string.console_admin_swap),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.swap?.let { swap ->
                        Text(stringResource(R.string.console_admin_swap_current, swap.sizeMegabytes))
                        if (swap.preset == null) {
                            Text(
                                stringResource(R.string.console_admin_unknown_read_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AdministrationSwapPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = swap.preset == preset,
                                    onClick = {
                                        pendingAction = PendingAdministrationAction.Swap(preset)
                                    },
                                    enabled = controlsEnabled && swap.preset != null,
                                    label = { Text(preset.displayLabel()) },
                                )
                            }
                        }
                    } ?: AdministrationUnavailable()

                    OutlinedButton(
                        onClick = { pendingAction = PendingAdministrationAction.EnableTls },
                        enabled = controlsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("administration-enable-tls"),
                    ) {
                        Text(stringResource(R.string.console_admin_enable_tls))
                    }
                    Text(
                        stringResource(R.string.console_admin_tls_enable_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AdministrationSection(stringResource(R.string.console_admin_access)) {
                    state.sshEnabled?.let { enabled ->
                        Text(
                            stringResource(
                                if (enabled) R.string.console_admin_ssh_on
                                else R.string.console_admin_ssh_off,
                            ),
                        )
                        OutlinedButton(
                            onClick = {
                                pendingAction = PendingAdministrationAction.Ssh(!enabled)
                            },
                            enabled = controlsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (enabled) R.string.console_admin_disable_ssh
                                    else R.string.console_admin_enable_ssh,
                                ),
                            )
                        }
                    } ?: AdministrationUnavailable()
                    state.mdnsEnabled?.let { enabled ->
                        Text(
                            stringResource(
                                if (enabled) R.string.console_admin_mdns_on
                                else R.string.console_admin_mdns_off,
                            ),
                        )
                        OutlinedButton(
                            onClick = {
                                pendingAction = PendingAdministrationAction.Mdns(!enabled)
                            },
                            enabled = controlsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (enabled) R.string.console_admin_disable_mdns
                                    else R.string.console_admin_enable_mdns,
                                ),
                            )
                        }
                    } ?: AdministrationUnavailable()
                }

                AdministrationSection(stringResource(R.string.console_admin_identity)) {
                    OutlinedTextField(
                        value = hostnameInput,
                        onValueChange = {
                            hostnameEdited = true
                            hostnameInput = it
                        },
                        enabled = state.hostname != null && !state.operationInProgress,
                        label = { Text(stringResource(R.string.console_admin_hostname)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            pendingAction = PendingAdministrationAction.Hostname(hostnameInput)
                        },
                        enabled = controlsEnabled && state.hostname != null &&
                            hostnameInput.isNotBlank() && hostnameInput != state.hostname,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.console_admin_apply_hostname))
                    }
                    Text(
                        stringResource(
                            if (state.webTitleIsDefault == true) {
                                R.string.console_admin_web_title_default
                            } else {
                                R.string.console_admin_web_title_custom
                            },
                        ),
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = {
                            titleEdited = true
                            titleInput = it
                        },
                        enabled = state.webTitle != null && !state.operationInProgress,
                        label = { Text(stringResource(R.string.console_admin_web_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                pendingAction = PendingAdministrationAction.CustomTitle(titleInput)
                            },
                            enabled = controlsEnabled && state.webTitle != null &&
                                titleInput.isNotBlank() &&
                                (state.webTitleIsDefault == true || titleInput != state.webTitle),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.console_admin_apply_web_title))
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                pendingAction = PendingAdministrationAction.ResetTitle
                            },
                            enabled = controlsEnabled && state.webTitleIsDefault == false,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.console_admin_reset_web_title))
                        }
                    }
                }

                AdministrationSection(stringResource(R.string.console_admin_network)) {
                    val wifi = state.wifi
                    Text(
                        stringResource(R.string.console_admin_wifi_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    when {
                        wifi == null -> AdministrationUnavailable()
                        !wifi.supported -> Text(
                            stringResource(R.string.console_admin_wifi_unsupported),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        wifi.accessPointMode -> Text(
                            stringResource(R.string.console_admin_wifi_ap_mode),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> {
                            Text(
                                if (wifi.connected) {
                                    stringResource(
                                        R.string.console_admin_wifi_connected,
                                        wifi.ssid.orEmpty(),
                                    )
                                } else {
                                    stringResource(R.string.console_admin_wifi_disconnected)
                                },
                            )
                            OutlinedTextField(
                                value = wifiSsid,
                                onValueChange = { wifiSsid = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("administration-wifi-ssid"),
                                label = { Text(stringResource(R.string.console_admin_wifi_ssid)) },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = wifiPassword,
                                onValueChange = { wifiPassword = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("administration-wifi-password"),
                                label = {
                                    Text(stringResource(R.string.console_admin_wifi_password))
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val ownedPassword = wifiPassword.toCharArray()
                                        wifiPassword = ""
                                        pendingAction?.clear()
                                        pendingAction = PendingAdministrationAction.WifiConnect(
                                            ssid = wifiSsid,
                                            ownedPassword = ownedPassword,
                                        )
                                    },
                                    enabled = controlsEnabled && wifiSsid.isNotEmpty() &&
                                        wifiPassword.isNotEmpty(),
                                    modifier = Modifier.testTag("administration-wifi-connect"),
                                ) {
                                    Text(stringResource(R.string.console_admin_wifi_connect))
                                }
                                OutlinedButton(
                                    onClick = {
                                        pendingAction = PendingAdministrationAction.WifiDisconnect
                                    },
                                    enabled = controlsEnabled && wifi.connected,
                                ) {
                                    Text(stringResource(R.string.console_admin_wifi_disconnect))
                                }
                            }
                            Text(
                                stringResource(R.string.console_admin_wifi_manual_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Text(
                        stringResource(R.string.console_admin_tailscale_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val tailscale = state.tailscale
                    if (tailscale == null) {
                        AdministrationUnavailable()
                    } else {
                        Text(tailscale.selection.displayLabel(tailscale.reportedState))
                        listOfNotNull(tailscale.deviceName, tailscale.ipv4, tailscale.account)
                            .forEach { detail ->
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        val actions = tailscale.selection.availableCommands()
                        if (actions.isEmpty()) {
                            Text(
                                stringResource(R.string.console_admin_tailscale_read_only),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                actions.forEach { command ->
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            pendingAction =
                                                PendingAdministrationAction.Tailscale(command)
                                        },
                                        enabled = controlsEnabled,
                                        label = { Text(command.displayLabel()) },
                                    )
                                }
                            }
                        }
                    }
                }

                AdministrationSection(stringResource(R.string.console_admin_dns)) {
                    val dns = state.dns
                    if (dns == null) {
                        AdministrationUnavailable()
                    } else {
                        Text(
                            stringResource(
                                R.string.console_admin_dns_mode,
                                dns.mode.displayLabel(),
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.console_admin_dns_effective,
                                dns.effectiveServers.joinToString().ifBlank { "-" },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = dnsInput,
                            onValueChange = {
                                dnsEdited = true
                                dnsInput = it
                            },
                            enabled = !state.operationInProgress,
                            label = { Text(stringResource(R.string.console_admin_dns_servers)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val parsedServers = dnsInput.split(Regex("[,\\s]+"))
                            .filter(String::isNotBlank)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    pendingAction =
                                        PendingAdministrationAction.ManualDns(parsedServers)
                                },
                                enabled = controlsEnabled && parsedServers.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.console_admin_apply_manual_dns))
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    pendingAction = PendingAdministrationAction.DhcpDns
                                },
                                enabled = controlsEnabled && dns.mode != AdministrationDnsMode.Dhcp,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.console_admin_use_dhcp_dns))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.console_close))
            }
        },
    )

    pendingAction?.let { action ->
        ConfirmAdministrationActionDialog(
            destinationLabel = destinationLabel,
            destination = destination,
            action = action,
            onDismiss = {
                action.clear()
                pendingAction = null
            },
            onConfirm = {
                action.execute(commands, destination)
                pendingAction = null
            },
        )
    }
    if (showPasswordChange) {
        PasswordChangeDialog(
            destinationLabel = destinationLabel,
            currentUsername = state.account?.username.orEmpty(),
            protectedCredentialAvailable = canProtectPassword,
            onDismiss = { showPasswordChange = false },
            onSubmit = { username, password, saveProtectedCredential ->
                showPasswordChange = false
                onPasswordChange(destination, username, password, saveProtectedCredential)
            },
        )
    }
}

@Composable
private fun AdministrationSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun AdministrationUnavailable() {
    Text(
        stringResource(R.string.console_admin_state_unavailable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AdministrationNoticeCard(kind: AdministrationNoticeKind, message: String) {
    val container = when (kind) {
        AdministrationNoticeKind.Applied -> MaterialTheme.colorScheme.primaryContainer
        AdministrationNoticeKind.Reconciled -> MaterialTheme.colorScheme.tertiaryContainer
        AdministrationNoticeKind.Indeterminate,
        AdministrationNoticeKind.Rejected -> MaterialTheme.colorScheme.errorContainer
        AdministrationNoticeKind.Information -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (kind) {
        AdministrationNoticeKind.Applied -> MaterialTheme.colorScheme.onPrimaryContainer
        AdministrationNoticeKind.Reconciled -> MaterialTheme.colorScheme.onTertiaryContainer
        AdministrationNoticeKind.Indeterminate,
        AdministrationNoticeKind.Rejected -> MaterialTheme.colorScheme.onErrorContainer
        AdministrationNoticeKind.Information -> MaterialTheme.colorScheme.onSurface
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            color = content,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ConfirmAdministrationActionDialog(
    destinationLabel: String,
    destination: ApprovedAdministrationDestination,
    action: PendingAdministrationAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.console_admin_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.console_admin_confirm_destination,
                        destinationLabel,
                        destination.authority,
                    ),
                )
                Text(
                    stringResource(
                        R.string.console_admin_confirm_generation,
                        destination.sessionGeneration,
                    ),
                )
                Text(
                    stringResource(
                        R.string.console_admin_confirm_value,
                        action.valueLabel(),
                    ),
                )
                Text(
                    stringResource(
                        R.string.console_admin_confirm_consequence,
                        action.consequenceLabel(),
                    ),
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.console_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.console_cancel)) }
        },
    )
}

@Composable
private fun AdministrationOledPreset.displayLabel(): String = when (this) {
    AdministrationOledPreset.Never -> stringResource(R.string.console_admin_oled_never)
    AdministrationOledPreset.Seconds15 ->
        stringResource(R.string.console_admin_oled_seconds, 15)
    AdministrationOledPreset.Seconds30 ->
        stringResource(R.string.console_admin_oled_seconds, 30)
    AdministrationOledPreset.Minute1 ->
        stringResource(R.string.console_admin_oled_minutes, 1)
    AdministrationOledPreset.Minutes3 ->
        stringResource(R.string.console_admin_oled_minutes, 3)
    AdministrationOledPreset.Minutes5 ->
        stringResource(R.string.console_admin_oled_minutes, 5)
    AdministrationOledPreset.Minutes10 ->
        stringResource(R.string.console_admin_oled_minutes, 10)
    AdministrationOledPreset.Minutes30 ->
        stringResource(R.string.console_admin_oled_minutes, 30)
    AdministrationOledPreset.Hour1 -> stringResource(R.string.console_admin_oled_hour)
}

@Composable
private fun AdministrationDnsMode.displayLabel(): String = stringResource(
    when (this) {
        AdministrationDnsMode.Dhcp -> R.string.console_admin_dns_dhcp
        AdministrationDnsMode.Manual -> R.string.console_admin_dns_manual
        AdministrationDnsMode.Other -> R.string.console_admin_dns_other
    },
)

@Composable
private fun AdministrationMouseJigglerSelection.displayLabel(): String = stringResource(
    when (this) {
        AdministrationMouseJigglerSelection.Off -> R.string.console_admin_jiggler_off
        AdministrationMouseJigglerSelection.Relative -> R.string.console_admin_jiggler_relative
        AdministrationMouseJigglerSelection.Absolute -> R.string.console_admin_jiggler_absolute
        AdministrationMouseJigglerSelection.Other -> R.string.console_admin_jiggler_other
    },
)

@Composable
private fun AdministrationSwapPreset.displayLabel(): String = when (this) {
    AdministrationSwapPreset.Disabled -> stringResource(R.string.console_admin_swap_disabled)
    else -> stringResource(R.string.console_admin_swap_megabytes, megabytes)
}

private sealed interface PendingAdministrationAction {
    data class PreviewUpdates(val enabled: Boolean) : PendingAdministrationAction
    data class OnlineUpdate(val version: String) : PendingAdministrationAction
    data object Reboot : PendingAdministrationAction
    data class OledSleep(val preset: AdministrationOledPreset) : PendingAdministrationAction
    data class Ssh(val enabled: Boolean) : PendingAdministrationAction
    data class Hostname(val hostname: String) : PendingAdministrationAction
    data class Mdns(val enabled: Boolean) : PendingAdministrationAction
    data class CustomTitle(val title: String) : PendingAdministrationAction
    data object ResetTitle : PendingAdministrationAction
    data class ManualDns(val servers: List<String>) : PendingAdministrationAction
    data object DhcpDns : PendingAdministrationAction
    data class Hdmi(val enabled: Boolean) : PendingAdministrationAction
    data object ResetHdmi : PendingAdministrationAction
    data class MouseJiggler(
        val selection: AdministrationMouseJigglerSelection,
    ) : PendingAdministrationAction
    data class MemoryLimit(val enabled: Boolean) : PendingAdministrationAction
    data class Swap(val preset: AdministrationSwapPreset) : PendingAdministrationAction
    data object EnableTls : PendingAdministrationAction
    class WifiConnect(
        val ssid: String,
        ownedPassword: CharArray,
    ) : PendingAdministrationAction {
        private var password: CharArray? = ownedPassword

        fun takePassword(): CharArray = checkNotNull(password) {
            "Wi-Fi password ownership has already transferred"
        }.also { password = null }

        override fun toString(): String =
            "PendingAdministrationAction.WifiConnect(ssid=<redacted>, password=<redacted>)"

        fun clearPassword() {
            password?.fill('\u0000')
            password = null
        }
    }
    data object WifiDisconnect : PendingAdministrationAction
    data class Tailscale(
        val command: AdministrationTailscaleCommand,
    ) : PendingAdministrationAction
}

private fun PendingAdministrationAction.clear() {
    if (this is PendingAdministrationAction.WifiConnect) clearPassword()
}

@Composable
private fun PendingAdministrationAction.valueLabel(): String = when (this) {
    is PendingAdministrationAction.PreviewUpdates -> booleanLabel(enabled)
    is PendingAdministrationAction.OnlineUpdate ->
        stringResource(R.string.console_admin_value_update, version)
    PendingAdministrationAction.Reboot -> stringResource(R.string.console_admin_value_reboot)
    is PendingAdministrationAction.OledSleep -> preset.displayLabel()
    is PendingAdministrationAction.Ssh -> booleanLabel(enabled)
    is PendingAdministrationAction.Hostname -> hostname
    is PendingAdministrationAction.Mdns -> booleanLabel(enabled)
    is PendingAdministrationAction.CustomTitle -> title
    PendingAdministrationAction.ResetTitle ->
        stringResource(R.string.console_admin_value_default_title)
    is PendingAdministrationAction.ManualDns -> servers.joinToString(", ")
    PendingAdministrationAction.DhcpDns -> stringResource(R.string.console_admin_dns_dhcp)
    is PendingAdministrationAction.Hdmi -> booleanLabel(enabled)
    PendingAdministrationAction.ResetHdmi ->
        stringResource(R.string.console_admin_value_reset_hdmi)
    is PendingAdministrationAction.MouseJiggler -> selection.displayLabel()
    is PendingAdministrationAction.MemoryLimit -> booleanLabel(enabled)
    is PendingAdministrationAction.Swap -> preset.displayLabel()
    PendingAdministrationAction.EnableTls ->
        stringResource(R.string.console_admin_value_enable_tls)
    is PendingAdministrationAction.WifiConnect -> ssid
    PendingAdministrationAction.WifiDisconnect ->
        stringResource(R.string.console_admin_wifi_disconnect)
    is PendingAdministrationAction.Tailscale -> command.displayLabel()
}

@Composable
private fun PendingAdministrationAction.consequenceLabel(): String = stringResource(
    when (this) {
        is PendingAdministrationAction.OnlineUpdate -> R.string.console_admin_consequence_update
        PendingAdministrationAction.Reboot -> R.string.console_admin_consequence_reboot
        is PendingAdministrationAction.Ssh -> R.string.console_admin_consequence_security
        is PendingAdministrationAction.Hostname,
        is PendingAdministrationAction.Mdns,
        is PendingAdministrationAction.ManualDns,
        PendingAdministrationAction.DhcpDns -> R.string.console_admin_consequence_address
        is PendingAdministrationAction.Hdmi,
        PendingAdministrationAction.ResetHdmi -> R.string.console_admin_consequence_hdmi
        PendingAdministrationAction.EnableTls -> R.string.console_admin_consequence_tls
        is PendingAdministrationAction.WifiConnect ->
            R.string.console_admin_consequence_wifi_connect
        PendingAdministrationAction.WifiDisconnect ->
            R.string.console_admin_consequence_wifi_disconnect
        is PendingAdministrationAction.Tailscale -> command.consequenceResource()
        else -> R.string.console_admin_consequence_routine
    },
)

@Composable
private fun booleanLabel(enabled: Boolean): String = stringResource(
    if (enabled) R.string.console_admin_value_on else R.string.console_admin_value_off,
)

private fun PendingAdministrationAction.execute(
    commands: ConsoleCommandSink,
    destination: ApprovedAdministrationDestination,
) {
    when (this) {
        is PendingAdministrationAction.PreviewUpdates ->
            commands.setAdministrationPreviewUpdates(destination, enabled)
        is PendingAdministrationAction.OnlineUpdate ->
            commands.startAdministrationOnlineUpdate(destination)
        PendingAdministrationAction.Reboot ->
            commands.rebootAdministrationAppliance(destination)
        is PendingAdministrationAction.OledSleep ->
            commands.setAdministrationOledSleep(destination, preset)
        is PendingAdministrationAction.Ssh ->
            commands.setAdministrationSshEnabled(destination, enabled)
        is PendingAdministrationAction.Hostname ->
            commands.setAdministrationHostname(destination, hostname)
        is PendingAdministrationAction.Mdns ->
            commands.setAdministrationMdnsEnabled(destination, enabled)
        is PendingAdministrationAction.CustomTitle ->
            commands.setAdministrationWebTitle(destination, title)
        PendingAdministrationAction.ResetTitle ->
            commands.resetAdministrationWebTitle(destination)
        is PendingAdministrationAction.ManualDns ->
            commands.setAdministrationManualDns(destination, servers)
        PendingAdministrationAction.DhcpDns ->
            commands.setAdministrationDhcpDns(destination)
        is PendingAdministrationAction.Hdmi ->
            commands.setAdministrationHdmiEnabled(destination, enabled)
        PendingAdministrationAction.ResetHdmi ->
            commands.resetAdministrationHdmi(destination)
        is PendingAdministrationAction.MouseJiggler ->
            commands.setAdministrationMouseJiggler(destination, selection)
        is PendingAdministrationAction.MemoryLimit ->
            commands.setAdministrationMemoryLimitEnabled(destination, enabled)
        is PendingAdministrationAction.Swap ->
            commands.setAdministrationSwapSize(destination, preset)
        PendingAdministrationAction.EnableTls ->
            commands.enableAdministrationTls(destination)
        is PendingAdministrationAction.WifiConnect ->
            commands.connectAdministrationWifi(destination, ssid, takePassword())
        PendingAdministrationAction.WifiDisconnect ->
            commands.disconnectAdministrationWifi(destination)
        is PendingAdministrationAction.Tailscale ->
            commands.executeAdministrationTailscale(destination, command)
    }
}

@Composable
private fun AdministrationTailscaleSelection.displayLabel(reported: String?): String =
    when (this) {
        AdministrationTailscaleSelection.NotInstalled ->
            stringResource(R.string.console_admin_tailscale_not_installed)
        AdministrationTailscaleSelection.NotRunning ->
            stringResource(R.string.console_admin_tailscale_not_running)
        AdministrationTailscaleSelection.NotLoggedIn ->
            stringResource(R.string.console_admin_tailscale_not_logged_in)
        AdministrationTailscaleSelection.Stopped ->
            stringResource(R.string.console_admin_tailscale_stopped)
        AdministrationTailscaleSelection.Running ->
            stringResource(R.string.console_admin_tailscale_running)
        AdministrationTailscaleSelection.Other ->
            stringResource(R.string.console_admin_tailscale_other, reported.orEmpty())
    }

private fun AdministrationTailscaleSelection.availableCommands():
    List<AdministrationTailscaleCommand> = when (this) {
    AdministrationTailscaleSelection.NotInstalled ->
        listOf(AdministrationTailscaleCommand.Install)
    AdministrationTailscaleSelection.NotRunning -> listOf(
        AdministrationTailscaleCommand.Start,
        AdministrationTailscaleCommand.Uninstall,
    )
    AdministrationTailscaleSelection.NotLoggedIn -> listOf(
        AdministrationTailscaleCommand.Login,
        AdministrationTailscaleCommand.Restart,
        AdministrationTailscaleCommand.Stop,
        AdministrationTailscaleCommand.Uninstall,
    )
    AdministrationTailscaleSelection.Stopped -> listOf(
        AdministrationTailscaleCommand.Up,
        AdministrationTailscaleCommand.Logout,
        AdministrationTailscaleCommand.Restart,
        AdministrationTailscaleCommand.Stop,
        AdministrationTailscaleCommand.Uninstall,
    )
    AdministrationTailscaleSelection.Running -> listOf(
        AdministrationTailscaleCommand.Down,
        AdministrationTailscaleCommand.Logout,
        AdministrationTailscaleCommand.Restart,
        AdministrationTailscaleCommand.Stop,
        AdministrationTailscaleCommand.Uninstall,
    )
    AdministrationTailscaleSelection.Other -> emptyList()
}

@Composable
private fun AdministrationTailscaleCommand.displayLabel(): String = stringResource(
    when (this) {
        AdministrationTailscaleCommand.Install -> R.string.console_admin_tailscale_install
        AdministrationTailscaleCommand.Uninstall -> R.string.console_admin_tailscale_uninstall
        AdministrationTailscaleCommand.Start -> R.string.console_admin_tailscale_start
        AdministrationTailscaleCommand.Stop -> R.string.console_admin_tailscale_stop
        AdministrationTailscaleCommand.Restart -> R.string.console_admin_tailscale_restart
        AdministrationTailscaleCommand.Up -> R.string.console_admin_tailscale_up
        AdministrationTailscaleCommand.Down -> R.string.console_admin_tailscale_down
        AdministrationTailscaleCommand.Login -> R.string.console_admin_tailscale_login
        AdministrationTailscaleCommand.Logout -> R.string.console_admin_tailscale_logout
    },
)

private fun AdministrationTailscaleCommand.consequenceResource(): Int = when (this) {
    AdministrationTailscaleCommand.Install -> R.string.console_admin_consequence_tailscale_install
    AdministrationTailscaleCommand.Uninstall ->
        R.string.console_admin_consequence_tailscale_uninstall
    AdministrationTailscaleCommand.Start -> R.string.console_admin_consequence_tailscale_start
    AdministrationTailscaleCommand.Stop -> R.string.console_admin_consequence_tailscale_stop
    AdministrationTailscaleCommand.Restart ->
        R.string.console_admin_consequence_tailscale_restart
    AdministrationTailscaleCommand.Up -> R.string.console_admin_consequence_tailscale_up
    AdministrationTailscaleCommand.Down -> R.string.console_admin_consequence_tailscale_down
    AdministrationTailscaleCommand.Login -> R.string.console_admin_consequence_tailscale_login
    AdministrationTailscaleCommand.Logout -> R.string.console_admin_consequence_tailscale_logout
}

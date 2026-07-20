package org.nanokvm.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.ApprovedPhase3Destination
import org.nanokvm.mobile.runtime.Phase3FeatureUiState
import org.nanokvm.mobile.runtime.Phase3HidModeSelection
import org.nanokvm.mobile.runtime.Phase3ImageMountMode
import org.nanokvm.mobile.runtime.Phase3MediaImageUiState
import org.nanokvm.mobile.runtime.Phase3Notice
import org.nanokvm.mobile.runtime.Phase3NoticeKind
import org.nanokvm.mobile.runtime.Phase3TransferPhase
import org.nanokvm.mobile.runtime.Phase3WakeOnLanTargetUiState
import org.nanokvm.mobile.ui.displayText
import org.nanokvm.mobile.ui.components.PoliteStatus

@Composable
internal fun VirtualMediaDialog(
    state: Phase3FeatureUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onMount: (Phase3MediaImageUiState, Phase3ImageMountMode) -> Unit,
    onRestore: () -> Unit,
    onDelete: (Phase3MediaImageUiState) -> Unit,
    onSetHidMode: (Phase3HidModeSelection) -> Unit,
    onSetNetworkEnabled: (Boolean) -> Unit,
    onSetDiskEnabled: (Boolean) -> Unit,
    onStartTransfer: (String) -> Unit,
) {
    // Remote URLs commonly carry signed query tokens or embedded credentials; never save them.
    var sourceUrl by remember { mutableStateOf("") }
    val busy = state.loading || state.operationInProgress ||
        state.virtualMedia.transferPhase == Phase3TransferPhase.InProgress
    val media = state.virtualMedia
    AlertDialog(
        onDismissRequest = { if (!state.operationInProgress) onDismiss() },
        modifier = Modifier.testTag("phase3-virtual-media-dialog"),
        title = {
            DialogTitle(
                title = stringResource(R.string.console_virtual_media),
                refreshEnabled = !busy,
                onRefresh = onRefresh,
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .testTag("phase3-virtual-media-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.available) {
                    item(key = "availability") {
                        Text(stringResource(R.string.console_phase3_connect_required))
                    }
                }
                if (state.loading) {
                    item(key = "loading") { Phase3Loading(true) }
                }
                (state.virtualMediaNotice ?: state.notice)?.let { notice ->
                    item(key = "notice") { Phase3NoticeCard(notice) }
                }

                item(key = "hid-mode") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.console_hid_mode),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val hidMode = state.hidMode
                        if (hidMode == null) {
                            Text(stringResource(R.string.console_state_unknown))
                        } else {
                            Text(
                                when (hidMode.selection) {
                                    Phase3HidModeSelection.Normal ->
                                        stringResource(R.string.console_hid_mode_normal)
                                    Phase3HidModeSelection.HidOnly ->
                                        stringResource(R.string.console_hid_mode_only)
                                    Phase3HidModeSelection.Other -> stringResource(
                                        R.string.console_hid_mode_unknown,
                                        hidMode.reportedMode.orEmpty(),
                                    )
                                },
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(
                                    Phase3HidModeSelection.Normal,
                                    Phase3HidModeSelection.HidOnly,
                                ).forEach { selection ->
                                    FilterChip(
                                        selected = hidMode.selection == selection,
                                        onClick = { onSetHidMode(selection) },
                                        enabled = !busy &&
                                            hidMode.selection != Phase3HidModeSelection.Other,
                                        label = {
                                            Text(
                                                stringResource(
                                                    if (selection == Phase3HidModeSelection.Normal) {
                                                        R.string.console_hid_mode_normal
                                                    } else {
                                                        R.string.console_hid_mode_only
                                                    },
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.console_hid_mode_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item(key = "network-device") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.console_virtual_network_device),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(reportedEnabledLabel(media.networkEnabled))
                            Switch(
                                checked = media.networkEnabled == true,
                                enabled = !busy && media.networkEnabled != null,
                                onCheckedChange = onSetNetworkEnabled,
                                modifier = Modifier.testTag("phase3-network-toggle"),
                            )
                        }
                    }
                }

                item(key = "media-device") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.console_virtual_media_device),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(reportedEnabledLabel(media.mediaEnabled))
                        Text(
                            stringResource(R.string.console_virtual_media_read_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item(key = "disk-device") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.console_virtual_disk_device),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(reportedEnabledLabel(media.diskEnabled))
                            Switch(
                                checked = media.diskEnabled == true,
                                enabled = !busy && media.diskEnabled != null,
                                onCheckedChange = onSetDiskEnabled,
                                modifier = Modifier.testTag("phase3-disk-toggle"),
                            )
                        }
                        Text(
                            stringResource(R.string.console_usb_reset_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item(key = "mounted-media") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.console_mounted_media),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(mountedMediaLabel(state))
                        if (media.mountedDisplayName != null || media.hasUnlistedMountedImage) {
                            OutlinedButton(
                                onClick = onRestore,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.console_restore_physical_media))
                            }
                        }
                    }
                }

                item(key = "available-images") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.console_available_images),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (media.loaded && media.images.isEmpty()) {
                            Text(
                                stringResource(R.string.console_no_virtual_images),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(
                    items = media.images,
                    key = Phase3MediaImageUiState::id,
                ) { image ->
                    MediaImageCard(
                        image = image,
                        cdRomEnabled = media.cdRomEnabled,
                        busy = busy,
                        onMount = onMount,
                        onDelete = onDelete,
                    )
                }

                item(key = "remote-transfer") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.console_remote_image_transfer),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TransferStatus(state)
                        Text(
                            stringResource(R.string.console_remote_image_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (media.remoteTransferEnabled == false) {
                            Text(
                                stringResource(R.string.console_remote_image_disabled),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedTextField(
                            value = sourceUrl,
                            onValueChange = { sourceUrl = it.take(MAX_REMOTE_URL_INPUT_CHARS) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && media.remoteTransferEnabled == true,
                            singleLine = true,
                            label = { Text(stringResource(R.string.console_image_url)) },
                            supportingText = { Text(stringResource(R.string.console_image_url_hint)) },
                        )
                        Button(
                            onClick = { onStartTransfer(sourceUrl) },
                            enabled = !busy && sourceUrl.isNotBlank() &&
                                media.remoteTransferEnabled == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.console_download_image))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !state.operationInProgress) {
                Text(stringResource(R.string.console_close))
            }
        },
    )
}

@Composable
private fun reportedEnabledLabel(enabled: Boolean?): String = when (enabled) {
    true -> stringResource(R.string.console_enabled)
    false -> stringResource(R.string.console_disabled)
    null -> stringResource(R.string.console_state_unknown)
}

@Composable
private fun mountedMediaLabel(state: Phase3FeatureUiState): String {
    val media = state.virtualMedia
    return when {
        media.mountedDisplayName != null -> stringResource(
            if (media.cdRomEnabled) {
                R.string.console_mounted_cdrom_value
            } else {
                R.string.console_mounted_disk_value
            },
            media.mountedDisplayName,
        )
        media.hasUnlistedMountedImage -> stringResource(R.string.console_mounted_unlisted)
        media.loaded -> stringResource(R.string.console_physical_media_active)
        else -> stringResource(R.string.console_state_unknown)
    }
}

@Composable
private fun MediaImageCard(
    image: Phase3MediaImageUiState,
    cdRomEnabled: Boolean,
    busy: Boolean,
    onMount: (Phase3MediaImageUiState, Phase3ImageMountMode) -> Unit,
    onDelete: (Phase3MediaImageUiState) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("phase3-media-image"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                image.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (image.mounted) {
                Text(
                    stringResource(R.string.console_currently_mounted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { onMount(image, Phase3ImageMountMode.MassStorage) },
                    enabled = !busy && !(image.mounted && !cdRomEnabled),
                    modifier = Modifier.testTag("phase3-media-mount-${image.id}"),
                ) { Text(stringResource(R.string.console_mount_as_disk)) }
                TextButton(
                    onClick = { onMount(image, Phase3ImageMountMode.CdRom) },
                    enabled = !busy && !(image.mounted && cdRomEnabled),
                ) { Text(stringResource(R.string.console_mount_as_cdrom)) }
                TextButton(
                    onClick = { onDelete(image) },
                    enabled = !busy && !image.mounted,
                ) { Text(stringResource(R.string.console_delete_image)) }
            }
        }
    }
}

@Composable
private fun TransferStatus(state: Phase3FeatureUiState) {
    when (state.virtualMedia.transferPhase) {
        Phase3TransferPhase.InProgress -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                state.virtualMedia.transferPercentage?.let {
                    stringResource(
                        R.string.console_transfer_progress,
                        it.coerceIn(0.0, 100.0).toInt(),
                    )
                } ?: stringResource(R.string.console_transfer_in_progress),
            )
        }
        Phase3TransferPhase.Other -> Text(
            stringResource(R.string.console_transfer_unknown_state),
        )
        else -> Unit
    }
}

@Composable
internal fun WakeOnLanDialog(
    state: Phase3FeatureUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onWake: (String) -> Unit,
    onRename: (Phase3WakeOnLanTargetUiState, String) -> Unit,
    onDelete: (Phase3WakeOnLanTargetUiState) -> Unit,
) {
    var macAddress by rememberSaveable { mutableStateOf("") }
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameName by rememberSaveable { mutableStateOf("") }
    val renameTarget = state.wakeOnLanTargets.firstOrNull { it.id == renameTargetId }
    val busy = state.loading || state.operationInProgress
    AlertDialog(
        onDismissRequest = { if (!state.operationInProgress) onDismiss() },
        modifier = Modifier.testTag("phase3-wol-dialog"),
        title = {
            DialogTitle(
                title = stringResource(R.string.console_wake_on_lan),
                refreshEnabled = !busy,
                onRefresh = onRefresh,
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .testTag("phase3-wol-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading) {
                    item(key = "loading") { Phase3Loading(true) }
                }
                (state.wakeOnLanNotice ?: state.notice)?.let { notice ->
                    item(key = "notice") { Phase3NoticeCard(notice) }
                }
                item(key = "manual-wake") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.console_wol_delivery_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = macAddress,
                            onValueChange = { macAddress = it.take(MAX_MAC_INPUT_CHARS) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            singleLine = true,
                            label = { Text(stringResource(R.string.console_mac_address)) },
                            supportingText = {
                                Text(stringResource(R.string.console_mac_address_hint))
                            },
                        )
                        Button(
                            onClick = { onWake(macAddress) },
                            enabled = !busy && macAddress.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.console_send_wake_packet)) }
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.console_wol_history),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.wakeOnLanLoaded && state.wakeOnLanTargets.isEmpty()) {
                            Text(
                                stringResource(R.string.console_wol_history_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(
                    items = state.wakeOnLanTargets,
                    key = Phase3WakeOnLanTargetUiState::id,
                ) { target ->
                    WakeTargetCard(
                        target = target,
                        busy = busy,
                        onWake = onWake,
                        onRename = {
                            renameTargetId = target.id
                            renameName = target.name.orEmpty()
                        },
                        onDelete = { onDelete(target) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !state.operationInProgress) {
                Text(stringResource(R.string.console_close))
            }
        },
    )

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTargetId = null },
            title = { Text(stringResource(R.string.console_rename_wol_target)) },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it.take(MAX_WOL_NAME_INPUT_CHARS) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.console_name)) },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(target, renameName)
                        renameTargetId = null
                    },
                    enabled = renameName.isNotBlank() && !busy,
                ) { Text(stringResource(R.string.console_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetId = null }) {
                    Text(stringResource(R.string.console_cancel))
                }
            },
        )
    }
}

@Composable
private fun WakeTargetCard(
    target: Phase3WakeOnLanTargetUiState,
    busy: Boolean,
    onWake: (String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("phase3-wol-target"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                target.name?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.console_unnamed_wol_target),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                target.macAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { onWake(target.macAddress) },
                    enabled = !busy,
                    modifier = Modifier.testTag("phase3-wol-wake-${target.id}"),
                ) {
                    Text(stringResource(R.string.console_wake))
                }
                TextButton(onClick = onRename, enabled = !busy) {
                    Text(stringResource(R.string.console_rename))
                }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text(stringResource(R.string.console_delete))
                }
            }
        }
    }
}

@Composable
private fun DialogTitle(title: String, refreshEnabled: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title)
        IconButton(onClick = onRefresh, enabled = refreshEnabled) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.console_refresh_appliance_state),
            )
        }
    }
}

@Composable
private fun Phase3Loading(visible: Boolean) {
    if (!visible) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.console_loading_appliance_state))
    }
}

@Composable
internal fun Phase3NoticeCard(notice: Phase3Notice) {
    Phase3NoticeSurface(message = notice.displayText(), kind = notice.kind)
}

@Composable
private fun Phase3NoticeSurface(message: String, kind: Phase3NoticeKind) {
    val containerColor = when (kind) {
        Phase3NoticeKind.Applied -> MaterialTheme.colorScheme.primaryContainer
        Phase3NoticeKind.Reconciled -> MaterialTheme.colorScheme.tertiaryContainer
        Phase3NoticeKind.Indeterminate,
        Phase3NoticeKind.Rejected -> MaterialTheme.colorScheme.errorContainer
        Phase3NoticeKind.Information -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (kind) {
        Phase3NoticeKind.Applied -> MaterialTheme.colorScheme.onPrimaryContainer
        Phase3NoticeKind.Reconciled -> MaterialTheme.colorScheme.onTertiaryContainer
        Phase3NoticeKind.Indeterminate,
        Phase3NoticeKind.Rejected -> MaterialTheme.colorScheme.onErrorContainer
        Phase3NoticeKind.Information -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    PoliteStatus {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
        ) {
            Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ConfirmPhase3ActionDialog(
    action: PendingPhase3Action,
    destinationLabel: String,
    destination: ApprovedPhase3Destination,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (title, detail) = when (action) {
        is PendingPhase3Action.MountImage -> stringResource(
            if (action.mode == Phase3ImageMountMode.CdRom) {
                R.string.console_confirm_mount_cdrom_title
            } else {
                R.string.console_confirm_mount_disk_title
            },
        ) to stringResource(R.string.console_confirm_mount_detail, action.image.displayName)
        PendingPhase3Action.RestorePhysicalMedia ->
            stringResource(R.string.console_confirm_restore_title) to
                stringResource(R.string.console_confirm_restore_detail)
        is PendingPhase3Action.DeleteImage ->
            stringResource(R.string.console_confirm_delete_image_title) to
                stringResource(R.string.console_confirm_delete_image_detail, action.image.displayName)
        is PendingPhase3Action.SetDiskEnabled -> stringResource(
            if (action.enabled) {
                R.string.console_confirm_enable_disk_title
            } else {
                R.string.console_confirm_disable_disk_title
            },
        ) to stringResource(R.string.console_usb_reset_explanation)
        is PendingPhase3Action.SetNetworkEnabled -> stringResource(
            if (action.enabled) {
                R.string.console_confirm_enable_network_title
            } else {
                R.string.console_confirm_disable_network_title
            },
        ) to stringResource(R.string.console_usb_reset_explanation)
        is PendingPhase3Action.SetHidMode ->
            stringResource(R.string.console_confirm_hid_mode_title) to
                stringResource(
                    R.string.console_confirm_hid_mode_detail,
                    if (action.selection == Phase3HidModeSelection.Normal) {
                        stringResource(R.string.console_hid_mode_normal)
                    } else {
                        stringResource(R.string.console_hid_mode_only)
                    },
                )
        is PendingPhase3Action.StartImageTransfer ->
            stringResource(R.string.console_confirm_transfer_title) to
                stringResource(R.string.console_confirm_transfer_detail, action.sourceUrl)
        is PendingPhase3Action.SendWakeOnLan ->
            stringResource(R.string.console_confirm_wake_title) to
                stringResource(R.string.console_confirm_wake_detail, action.macAddress)
        is PendingPhase3Action.DeleteWakeOnLan ->
            stringResource(R.string.console_confirm_delete_wol_title) to
                stringResource(
                    R.string.console_confirm_delete_wol_detail,
                    action.target.name.orEmpty().ifBlank { action.target.macAddress },
                    action.target.macAddress,
                )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        R.string.console_phase3_confirmation_destination,
                        destinationLabel,
                        destination.authority,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    stringResource(
                        R.string.console_phase3_confirmation_generation,
                        destination.sessionGeneration,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(detail)
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

internal sealed interface PendingPhase3Action {
    data class MountImage(
        val image: Phase3MediaImageUiState,
        val mode: Phase3ImageMountMode,
    ) : PendingPhase3Action

    data object RestorePhysicalMedia : PendingPhase3Action
    data class DeleteImage(val image: Phase3MediaImageUiState) : PendingPhase3Action
    data class SetHidMode(val selection: Phase3HidModeSelection) : PendingPhase3Action
    data class SetNetworkEnabled(val enabled: Boolean) : PendingPhase3Action
    data class SetDiskEnabled(val enabled: Boolean) : PendingPhase3Action
    /** Kept only in Compose memory until the explicit confirmation closes. */
    class StartImageTransfer(val sourceUrl: String) : PendingPhase3Action {
        override fun toString(): String = "StartImageTransfer(sourceUrl=<redacted>)"
    }
    data class SendWakeOnLan(val macAddress: String) : PendingPhase3Action
    data class DeleteWakeOnLan(val target: Phase3WakeOnLanTargetUiState) : PendingPhase3Action
}

private const val MAX_REMOTE_URL_INPUT_CHARS = 4_096
private const val MAX_MAC_INPUT_CHARS = 64
private const val MAX_WOL_NAME_INPUT_CHARS = 128

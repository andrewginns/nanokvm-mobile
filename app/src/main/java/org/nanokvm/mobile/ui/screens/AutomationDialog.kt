package org.nanokvm.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.NanoKvmAutomationAutostartCatalog
import org.nanokvm.mobile.runtime.NanoKvmAutomationAutostartScript
import org.nanokvm.mobile.runtime.NanoKvmAutomationGateway
import org.nanokvm.mobile.runtime.NanoKvmAutomationHidCatalog
import org.nanokvm.mobile.runtime.NanoKvmAutomationHidShortcut
import org.nanokvm.mobile.runtime.NanoKvmAutomationLeaderKey
import org.nanokvm.mobile.runtime.NanoKvmAutostartReviewKind
import org.nanokvm.mobile.runtime.NanoKvmHidShortcutAction
import org.nanokvm.mobile.runtime.NanoKvmPhysicalKeyRecordResult
import org.nanokvm.mobile.runtime.NanoKvmRecordedHidKey
import org.nanokvm.mobile.ui.components.PoliteStatus

/**
 * Feature-contained UI for the isolated automation gateway.
 *
 * [onRequestAutostartImport] may open a document picker and invoke the supplied consumer exactly
 * once with an owned byte array. The consumer clears that array on every success/failure path.
 */
@Composable
internal fun AutomationDialog(
    destinationLabel: String,
    gateway: NanoKvmAutomationGateway,
    onDismiss: () -> Unit,
    onRequestAutostartImport: (((ByteArray) -> Unit) -> Unit)? = null,
    leaderKeyAvailable: Boolean = true,
    retainedController: AutomationDialogController? = null,
) {
    val localController = remember(gateway, leaderKeyAvailable, retainedController) {
        if (retainedController == null) {
            AutomationDialogController(gateway, leaderKeyAvailable)
        } else {
            null
        }
    }
    val controller = retainedController ?: checkNotNull(localController)
    val state by controller.state.collectAsStateWithLifecycle()
    val recorderFocus = remember { FocusRequester() }
    val hidListState = rememberLazyListState()
    val autostartListState = rememberLazyListState()
    DisposableEffect(controller, retainedController) {
        onDispose {
            localController?.close()
        }
    }

    fun dismiss() {
        localController?.close()
        onDismiss()
    }

    AlertDialog(
        modifier = Modifier.fillMaxWidth(),
        onDismissRequest = ::dismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.automation_title))
                    Text(
                        stringResource(R.string.automation_destination, destinationLabel),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(
                    onClick = { controller.refresh() },
                    enabled = state.controlsEnabled,
                    modifier = Modifier.testTag("automation-refresh"),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.automation_refresh),
                    )
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .testTag("automation-surface"),
                state = if (state.tab == AutomationTab.HID) hidListState else autostartListState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "tabs") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.tab == AutomationTab.HID,
                            onClick = { controller.selectTab(AutomationTab.HID) },
                            label = { Text(stringResource(R.string.automation_hid_tab)) },
                        )
                        FilterChip(
                            selected = state.tab == AutomationTab.AUTOSTART,
                            onClick = { controller.selectTab(AutomationTab.AUTOSTART) },
                            label = { Text(stringResource(R.string.automation_autostart_tab)) },
                        )
                    }
                }
                if (state.loading) {
                    item(key = "loading") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.automation_loading))
                        }
                    }
                }
                state.visibleNotice?.let { notice ->
                    item(key = "notice") {
                        PoliteStatus {
                            Text(stringResource(notice.messageResource))
                        }
                    }
                }

                when (state.tab) {
                    AutomationTab.HID -> hidAutomationContent(
                        catalog = state.hidCatalog,
                        leaderKey = state.leaderKey,
                        leaderKeyAvailable = state.leaderKeyAvailable,
                        recordedKeys = state.recordedKeys,
                        enabled = state.controlsEnabled,
                        focusRequester = recorderFocus,
                        onRecord = controller::recordAndroidKeyCode,
                        onClear = controller::clearRecordedKeys,
                        onReviewSave = controller::reviewHidShortcutSave,
                        onReviewAction = controller::reviewHidShortcutAction,
                        onSetLeader = { controller.setLeaderKey(it) },
                    )
                    AutomationTab.AUTOSTART -> autostartAutomationContent(
                        catalog = state.autostartCatalog,
                        editingScript = state.editingScript,
                        fileName = state.autostartName,
                        content = state.autostartText,
                        enabled = state.controlsEnabled,
                        importAvailable = onRequestAutostartImport != null,
                        onFileNameChange = controller::updateAutostartName,
                        onContentChange = controller::updateAutostartText,
                        onImport = {
                            val importer = onRequestAutostartImport
                            if (importer == null) {
                                controller.reportImportUnavailable()
                            } else {
                                importer { bytes ->
                                    controller.importAutostartOwned(bytes)
                                }
                            }
                        },
                        onEdit = { controller.editAutostart(it) },
                        onCancelEdit = controller::cancelAutostartEdit,
                        onReviewWrite = controller::reviewAutostartWrite,
                        onReviewDelete = controller::reviewAutostartDelete,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = ::dismiss) { Text(stringResource(R.string.automation_close)) }
        },
    )

    state.pendingAction?.let { action ->
        AutomationConfirmationDialog(
            action = action,
            operationInProgress = state.operationInProgress,
            onCancel = controller::cancelPendingAction,
            onConfirm = { controller.confirmPendingAction() },
        )
    }
}

private val AutomationNotice.messageResource: Int
    get() = when (this) {
        AutomationNotice.ACTION_COMPLETE -> R.string.automation_action_complete
        AutomationNotice.ACTION_INDETERMINATE -> R.string.automation_action_indeterminate
        AutomationNotice.ACTION_REJECTED -> R.string.automation_action_rejected
        AutomationNotice.READ_FAILED -> R.string.automation_read_failed
        AutomationNotice.IMPORT_UNAVAILABLE -> R.string.automation_import_unavailable
        AutomationNotice.INVALID_EDITOR -> R.string.automation_invalid_editor
    }

private fun LazyListScope.hidAutomationContent(
    catalog: NanoKvmAutomationHidCatalog?,
    leaderKey: NanoKvmAutomationLeaderKey?,
    leaderKeyAvailable: Boolean,
    recordedKeys: List<NanoKvmRecordedHidKey>,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onRecord: (Int, Int) -> NanoKvmPhysicalKeyRecordResult,
    onClear: () -> Unit,
    onReviewSave: () -> Unit,
    onReviewAction: (NanoKvmAutomationHidShortcut, NanoKvmHidShortcutAction) -> Unit,
    onSetLeader: (String?) -> Unit,
) {
    item(key = "hid-shortcuts-title") {
        Text(
            stringResource(R.string.automation_shortcuts),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    val shortcuts = catalog?.shortcuts.orEmpty()
    if (shortcuts.isEmpty()) {
        item(key = "hid-shortcuts-empty") {
            Text(stringResource(R.string.automation_no_shortcuts))
        }
    } else {
        items(
            items = shortcuts,
            key = { shortcut -> hidShortcutItemKey(shortcut.stableId) },
        ) { shortcut ->
            HidShortcutCard(
                shortcut = shortcut,
                enabled = enabled,
                onReviewAction = onReviewAction,
            )
        }
    }

    item(key = "hid-recorder-title") {
        Text(
            stringResource(R.string.automation_recorder),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    item(key = "hid-recorder") {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    onRecord(event.nativeKeyEvent.keyCode, event.nativeKeyEvent.repeatCount) !=
                        NanoKvmPhysicalKeyRecordResult.UNSUPPORTED
                }
                .clickable(enabled = enabled) { focusRequester.requestFocus() }
                .testTag("automation-key-recorder"),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.automation_recorder_hint))
                Text(recordedKeys.joinToString(" + ") { it.displayLabel }.ifBlank { "\u2014" })
            }
        }
    }
    item(key = "hid-recorder-actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClear, enabled = enabled && recordedKeys.isNotEmpty()) {
                Text(stringResource(R.string.automation_clear))
            }
            Button(onClick = onReviewSave, enabled = enabled && recordedKeys.isNotEmpty()) {
                Text(stringResource(R.string.automation_review_save))
            }
        }
    }

    item(key = "hid-leader-title") {
        Text(
            stringResource(R.string.automation_leader_key),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    item(key = "hid-leader-value") {
        Text(
            if (leaderKeyAvailable) {
                leaderKey?.takeIf { it.enabled }?.displayLabel
                    ?: stringResource(R.string.automation_leader_disabled)
            } else {
                stringResource(R.string.automation_leader_unavailable)
            },
        )
    }
    if (leaderKeyAvailable && leaderKey?.writable == false) {
        item(key = "hid-leader-warning") {
            Text(
                stringResource(R.string.automation_unknown_leader),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    item(key = "hid-leader-actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onSetLeader(recordedKeys.firstOrNull()?.wireCode) },
                enabled = enabled && leaderKeyAvailable &&
                    leaderKey?.writable == true && recordedKeys.isNotEmpty(),
            ) { Text(stringResource(R.string.automation_set_first_key)) }
            OutlinedButton(
                onClick = { onSetLeader(null) },
                enabled = enabled && leaderKeyAvailable &&
                    leaderKey?.writable == true && leaderKey.enabled,
            ) { Text(stringResource(R.string.automation_disable)) }
        }
    }
}

@Composable
private fun HidShortcutCard(
    shortcut: NanoKvmAutomationHidShortcut,
    enabled: Boolean,
    onReviewAction: (NanoKvmAutomationHidShortcut, NanoKvmHidShortcutAction) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("automation-hid-shortcut"),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(shortcut.displayLabel)
            if (!shortcut.runnable) {
                Text(
                    stringResource(R.string.automation_unknown_shortcut),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onReviewAction(shortcut, NanoKvmHidShortcutAction.RUN) },
                    enabled = enabled && shortcut.runnable,
                    modifier = Modifier.testTag("automation-hid-run-${shortcut.stableId}"),
                ) { Text(stringResource(R.string.automation_run)) }
                OutlinedButton(
                    onClick = { onReviewAction(shortcut, NanoKvmHidShortcutAction.DELETE) },
                    enabled = enabled,
                ) { Text(stringResource(R.string.automation_delete)) }
            }
        }
    }
}

private fun LazyListScope.autostartAutomationContent(
    catalog: NanoKvmAutomationAutostartCatalog?,
    editingScript: NanoKvmAutomationAutostartScript?,
    fileName: String,
    content: String,
    enabled: Boolean,
    importAvailable: Boolean,
    onFileNameChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onImport: () -> Unit,
    onEdit: (NanoKvmAutomationAutostartScript) -> Unit,
    onCancelEdit: () -> Unit,
    onReviewWrite: () -> Unit,
    onReviewDelete: (NanoKvmAutomationAutostartScript) -> Unit,
) {
    item(key = "autostart-warning") {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                stringResource(R.string.automation_root_warning),
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    val scripts = catalog?.scripts.orEmpty()
    if (scripts.isEmpty()) {
        item(key = "autostart-empty") {
            Text(stringResource(R.string.automation_no_autostart))
        }
    } else {
        items(
            items = scripts,
            key = { script -> autostartScriptItemKey(script.displayName) },
        ) { script ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("automation-autostart-script"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(script.displayName, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onEdit(script) }, enabled = enabled) {
                    Text(stringResource(R.string.automation_edit))
                }
                OutlinedButton(
                    onClick = { onReviewDelete(script) },
                    enabled = enabled,
                    modifier = Modifier.testTag(
                        "automation-autostart-delete-${script.displayName}",
                    ),
                ) {
                    Text(stringResource(R.string.automation_delete))
                }
            }
        }
    }
    item(key = "autostart-editor") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = fileName,
                onValueChange = onFileNameChange,
                enabled = enabled && editingScript == null,
                label = { Text(stringResource(R.string.automation_filename)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("automation-autostart-name"),
                singleLine = true,
            )
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                enabled = enabled,
                label = { Text(stringResource(R.string.automation_content)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("automation-autostart-editor"),
                minLines = 6,
                maxLines = 14,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onImport, enabled = enabled && importAvailable) {
                    Text(stringResource(R.string.automation_import))
                }
                Button(onClick = onReviewWrite, enabled = enabled && content.isNotEmpty()) {
                    Text(
                        stringResource(
                            if (editingScript == null) {
                                R.string.automation_review_create
                            } else {
                                R.string.automation_review_update
                            },
                        ),
                    )
                }
                if (editingScript != null) {
                    OutlinedButton(onClick = onCancelEdit, enabled = enabled) {
                        Text(stringResource(R.string.automation_cancel_edit))
                    }
                }
            }
        }
    }
}

private fun hidShortcutItemKey(stableId: String): String = "hid-shortcut:$stableId"

private fun autostartScriptItemKey(displayName: String): String = "autostart-script:$displayName"

@Composable
private fun AutomationConfirmationDialog(
    action: PendingAutomationAction,
    operationInProgress: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val message = when (action) {
        is PendingAutomationAction.HidAction -> when (action.approval.action) {
            NanoKvmHidShortcutAction.RUN -> stringResource(R.string.automation_confirm_run)
            NanoKvmHidShortcutAction.DELETE ->
                stringResource(R.string.automation_confirm_hid_delete)
        } + "\n" + action.displayLabel
        is PendingAutomationAction.HidSave ->
            stringResource(R.string.automation_confirm_save) + "\n" + action.displayLabel
        is PendingAutomationAction.AutostartWrite -> stringResource(
            R.string.automation_confirm_autostart_write,
            if (action.approval.kind == NanoKvmAutostartReviewKind.CREATE) "Create" else "Replace",
            action.approval.targetDisplayName,
            action.approval.byteCount,
        )
        is PendingAutomationAction.AutostartDelete -> stringResource(
            R.string.automation_confirm_autostart_delete,
            action.approval.targetDisplayName,
        )
    }
    AlertDialog(
        modifier = Modifier.testTag("automation-confirmation"),
        onDismissRequest = { if (!operationInProgress) onCancel() },
        title = { Text(stringResource(R.string.automation_confirm_title)) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !operationInProgress,
                modifier = Modifier.testTag("automation-confirm"),
            ) { Text(stringResource(R.string.automation_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !operationInProgress) {
                Text(stringResource(R.string.automation_cancel))
            }
        },
    )
}

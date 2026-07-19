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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.NanoKvmAutomationAutostartCatalog
import org.nanokvm.mobile.runtime.NanoKvmAutomationAutostartScript
import org.nanokvm.mobile.runtime.NanoKvmAutomationCommandResult
import org.nanokvm.mobile.runtime.NanoKvmAutomationError
import org.nanokvm.mobile.runtime.NanoKvmAutomationGateway
import org.nanokvm.mobile.runtime.NanoKvmAutomationHidCatalog
import org.nanokvm.mobile.runtime.NanoKvmAutomationHidShortcut
import org.nanokvm.mobile.runtime.NanoKvmAutomationLeaderKey
import org.nanokvm.mobile.runtime.NanoKvmAutomationReadResult
import org.nanokvm.mobile.runtime.NanoKvmAutomationReviewResult
import org.nanokvm.mobile.runtime.NanoKvmAutostartDeleteApproval
import org.nanokvm.mobile.runtime.NanoKvmAutostartEditorBuffer
import org.nanokvm.mobile.runtime.NanoKvmAutostartReviewKind
import org.nanokvm.mobile.runtime.NanoKvmAutostartWriteApproval
import org.nanokvm.mobile.runtime.NanoKvmHidShortcutAction
import org.nanokvm.mobile.runtime.NanoKvmHidShortcutActionApproval
import org.nanokvm.mobile.runtime.NanoKvmHidShortcutSaveApproval
import org.nanokvm.mobile.runtime.NanoKvmPhysicalKeyRecordResult
import org.nanokvm.mobile.runtime.NanoKvmPhysicalShortcutRecorder
import org.nanokvm.mobile.runtime.NanoKvmRecordedHidKey
import org.nanokvm.mobile.runtime.isSafeAutostartEditorText

private enum class AutomationTab { HID, AUTOSTART }

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
) {
    val scope = rememberCoroutineScope()
    val recorder = remember(gateway) { NanoKvmPhysicalShortcutRecorder() }
    val recorderFocus = remember { FocusRequester() }
    var tab by remember { mutableStateOf(AutomationTab.HID) }
    var hidCatalog by remember { mutableStateOf<NanoKvmAutomationHidCatalog?>(null) }
    var leaderKey by remember { mutableStateOf<NanoKvmAutomationLeaderKey?>(null) }
    var autostartCatalog by remember {
        mutableStateOf<NanoKvmAutomationAutostartCatalog?>(null)
    }
    var recordedKeys by remember { mutableStateOf<List<NanoKvmRecordedHidKey>>(emptyList()) }
    var autostartName by remember { mutableStateOf("") }
    var autostartText by remember { mutableStateOf("") }
    var editingScript by remember { mutableStateOf<NanoKvmAutomationAutostartScript?>(null) }
    var loading by remember { mutableStateOf(true) }
    var operationInProgress by remember { mutableStateOf(false) }
    var noticeResource by remember { mutableStateOf<Int?>(null) }
    var hidReadNoticeResource by remember { mutableStateOf<Int?>(null) }
    var autostartReadNoticeResource by remember { mutableStateOf<Int?>(null) }
    var canUseLeaderKey by remember(gateway, leaderKeyAvailable) {
        mutableStateOf(leaderKeyAvailable)
    }
    var pending by remember { mutableStateOf<PendingAutomationAction?>(null) }
    val latestPending by rememberUpdatedState(pending)

    suspend fun refreshHid() {
        hidReadNoticeResource = null
        when (val result = gateway.refreshHidShortcuts()) {
            is NanoKvmAutomationReadResult.Success -> hidCatalog = result.value
            is NanoKvmAutomationReadResult.Failure -> {
                hidCatalog = null
                hidReadNoticeResource = R.string.automation_read_failed
            }
        }
        if (canUseLeaderKey) {
            when (val result = gateway.refreshLeaderKey()) {
                is NanoKvmAutomationReadResult.Success -> leaderKey = result.value
                is NanoKvmAutomationReadResult.Failure -> {
                    leaderKey = null
                    if (result.error.kind == NanoKvmAutomationError.Kind.UNSUPPORTED) {
                        canUseLeaderKey = false
                    } else {
                        hidReadNoticeResource = R.string.automation_read_failed
                    }
                }
            }
        } else {
            leaderKey = null
        }
    }

    suspend fun refreshAutostart() {
        autostartReadNoticeResource = null
        when (val result = gateway.refreshAutostartScripts()) {
            is NanoKvmAutomationReadResult.Success -> autostartCatalog = result.value
            is NanoKvmAutomationReadResult.Failure -> {
                autostartCatalog = null
                autostartReadNoticeResource = R.string.automation_read_failed
            }
        }
    }

    suspend fun refreshAll() {
        loading = true
        refreshHid()
        refreshAutostart()
        loading = false
    }

    fun publishMutation(result: NanoKvmAutomationCommandResult<*>, refresh: suspend () -> Unit) {
        noticeResource = when (result) {
            is NanoKvmAutomationCommandResult.Completed -> R.string.automation_action_complete
            is NanoKvmAutomationCommandResult.Indeterminate ->
                R.string.automation_action_indeterminate
            is NanoKvmAutomationCommandResult.Rejected -> R.string.automation_action_rejected
        }
        scope.launch { refresh() }
    }

    DisposableEffect(gateway) {
        gateway.onForeground()
        onDispose {
            gateway.discardAutostartWrite(
                (latestPending as? PendingAutomationAction.AutostartWrite)?.approval,
            )
            recorder.clear()
            gateway.onBackground()
        }
    }
    LaunchedEffect(gateway) { refreshAll() }

    fun dismiss() {
        gateway.discardAutostartWrite(
            (pending as? PendingAutomationAction.AutostartWrite)?.approval,
        )
        pending = null
        gateway.onBackground()
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
                    onClick = { scope.launch { refreshAll() } },
                    enabled = !operationInProgress,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("automation-surface"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == AutomationTab.HID,
                        onClick = { tab = AutomationTab.HID },
                        label = { Text(stringResource(R.string.automation_hid_tab)) },
                    )
                    FilterChip(
                        selected = tab == AutomationTab.AUTOSTART,
                        onClick = { tab = AutomationTab.AUTOSTART },
                        label = { Text(stringResource(R.string.automation_autostart_tab)) },
                    )
                }
                if (loading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.automation_loading))
                    }
                }
                val visibleNotice = noticeResource ?: when (tab) {
                    AutomationTab.HID -> hidReadNoticeResource
                    AutomationTab.AUTOSTART -> autostartReadNoticeResource
                }
                visibleNotice?.let { Text(stringResource(it)) }

                when (tab) {
                    AutomationTab.HID -> HidAutomationContent(
                        catalog = hidCatalog,
                        leaderKey = leaderKey,
                        leaderKeyAvailable = canUseLeaderKey,
                        recordedKeys = recordedKeys,
                        enabled = !loading && !operationInProgress,
                        focusRequester = recorderFocus,
                        onRecord = { keyCode, repeatCount ->
                            val result = recorder.recordAndroidKeyCode(keyCode, repeatCount)
                            recordedKeys = recorder.keys
                            result
                        },
                        onClear = {
                            recorder.clear()
                            recordedKeys = emptyList()
                        },
                        onReviewSave = {
                            val catalog = hidCatalog ?: return@HidAutomationContent
                            when (val review = gateway.reviewHidShortcutSave(catalog, recordedKeys)) {
                                is NanoKvmAutomationReviewResult.Ready -> pending =
                                    PendingAutomationAction.HidSave(
                                        review.approval,
                                        recordedKeys.joinToString(" + ") { it.displayLabel },
                                    )
                                is NanoKvmAutomationReviewResult.Rejected ->
                                    noticeResource = R.string.automation_action_rejected
                            }
                        },
                        onReviewAction = { shortcut, action ->
                            val catalog = hidCatalog ?: return@HidAutomationContent
                            when (val review = gateway.reviewHidShortcutAction(catalog, shortcut, action)) {
                                is NanoKvmAutomationReviewResult.Ready -> pending =
                                    PendingAutomationAction.HidAction(
                                        review.approval,
                                        shortcut.displayLabel,
                                    )
                                is NanoKvmAutomationReviewResult.Rejected ->
                                    noticeResource = R.string.automation_action_rejected
                            }
                        },
                        onSetLeader = { wireCode ->
                            scope.launch {
                                operationInProgress = true
                                publishMutation(gateway.setLeaderKey(wireCode), ::refreshHid)
                                operationInProgress = false
                            }
                        },
                    )
                    AutomationTab.AUTOSTART -> AutostartAutomationContent(
                        catalog = autostartCatalog,
                        editingScript = editingScript,
                        fileName = autostartName,
                        content = autostartText,
                        enabled = !loading && !operationInProgress,
                        importAvailable = onRequestAutostartImport != null,
                        onFileNameChange = { autostartName = it.take(255) },
                        onContentChange = {
                            if (isSafeAutostartEditorText(it)) autostartText = it
                        },
                        onImport = {
                            val importer = onRequestAutostartImport
                            if (importer == null) {
                                noticeResource = R.string.automation_import_unavailable
                            } else {
                                importer { bytes ->
                                    scope.launch {
                                        try {
                                            val imported = NanoKvmAutostartEditorBuffer.importOwned(bytes)
                                            try {
                                                autostartText = imported.copyText()
                                            } finally {
                                                imported.close()
                                            }
                                        } catch (_: IllegalArgumentException) {
                                            bytes.fill(0)
                                            noticeResource = R.string.automation_invalid_editor
                                        }
                                    }
                                }
                            }
                        },
                        onEdit = { script ->
                            val catalog = autostartCatalog ?: return@AutostartAutomationContent
                            scope.launch {
                                operationInProgress = true
                                when (val result = gateway.readAutostartContent(catalog, script)) {
                                    is NanoKvmAutomationReadResult.Success -> {
                                        try {
                                            autostartText = result.value.copyText()
                                            autostartName = script.displayName
                                            editingScript = script
                                        } finally {
                                            result.value.close()
                                        }
                                    }
                                    is NanoKvmAutomationReadResult.Failure ->
                                        noticeResource = R.string.automation_read_failed
                                }
                                operationInProgress = false
                            }
                        },
                        onCancelEdit = {
                            editingScript = null
                            autostartName = ""
                            autostartText = ""
                        },
                        onReviewWrite = {
                            val catalog = autostartCatalog ?: return@AutostartAutomationContent
                            val editor = try {
                                NanoKvmAutostartEditorBuffer.fromText(autostartText)
                            } catch (_: IllegalArgumentException) {
                                noticeResource = R.string.automation_invalid_editor
                                return@AutostartAutomationContent
                            }
                            val review = editingScript?.let {
                                gateway.reviewAutostartUpdate(catalog, it, editor)
                            } ?: gateway.reviewAutostartCreate(catalog, autostartName, editor)
                            when (review) {
                                is NanoKvmAutomationReviewResult.Ready -> {
                                    pending = PendingAutomationAction.AutostartWrite(review.approval)
                                    autostartText = ""
                                    autostartName = ""
                                    editingScript = null
                                }
                                is NanoKvmAutomationReviewResult.Rejected -> {
                                    editor.close()
                                    noticeResource = R.string.automation_invalid_editor
                                }
                            }
                        },
                        onReviewDelete = { script ->
                            val catalog = autostartCatalog ?: return@AutostartAutomationContent
                            when (val review = gateway.reviewAutostartDelete(catalog, script)) {
                                is NanoKvmAutomationReviewResult.Ready -> pending =
                                    PendingAutomationAction.AutostartDelete(review.approval)
                                is NanoKvmAutomationReviewResult.Rejected ->
                                    noticeResource = R.string.automation_action_rejected
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = ::dismiss) { Text(stringResource(R.string.automation_close)) }
        },
    )

    pending?.let { action ->
        AutomationConfirmationDialog(
            action = action,
            operationInProgress = operationInProgress,
            onCancel = {
                gateway.discardAutostartWrite(
                    (action as? PendingAutomationAction.AutostartWrite)?.approval,
                )
                pending = null
            },
            onConfirm = {
                scope.launch {
                    operationInProgress = true
                    val result = when (action) {
                        is PendingAutomationAction.HidAction ->
                            gateway.executeHidShortcutAction(action.approval)
                        is PendingAutomationAction.HidSave ->
                            gateway.executeHidShortcutSave(action.approval)
                        is PendingAutomationAction.AutostartWrite ->
                            gateway.executeAutostartWrite(action.approval)
                        is PendingAutomationAction.AutostartDelete ->
                            gateway.executeAutostartDelete(action.approval)
                    }
                    pending = null
                    val refresh: suspend () -> Unit = when (action) {
                        is PendingAutomationAction.HidAction,
                        is PendingAutomationAction.HidSave -> ::refreshHid
                        is PendingAutomationAction.AutostartWrite,
                        is PendingAutomationAction.AutostartDelete -> ::refreshAutostart
                    }
                    publishMutation(result, refresh)
                    if (action is PendingAutomationAction.HidSave) {
                        recorder.clear()
                        recordedKeys = emptyList()
                    }
                    operationInProgress = false
                }
            },
        )
    }
}

@Composable
private fun HidAutomationContent(
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
    Text(stringResource(R.string.automation_shortcuts), style = MaterialTheme.typography.titleMedium)
    if (catalog?.shortcuts.isNullOrEmpty()) {
        Text(stringResource(R.string.automation_no_shortcuts))
    } else {
        catalog.shortcuts.forEach { shortcut ->
            Card(modifier = Modifier.fillMaxWidth()) {
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
                        ) { Text(stringResource(R.string.automation_run)) }
                        OutlinedButton(
                            onClick = { onReviewAction(shortcut, NanoKvmHidShortcutAction.DELETE) },
                            enabled = enabled,
                        ) { Text(stringResource(R.string.automation_delete)) }
                    }
                }
            }
        }
    }

    Text(stringResource(R.string.automation_recorder), style = MaterialTheme.typography.titleMedium)
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onClear, enabled = enabled && recordedKeys.isNotEmpty()) {
            Text(stringResource(R.string.automation_clear))
        }
        Button(onClick = onReviewSave, enabled = enabled && recordedKeys.isNotEmpty()) {
            Text(stringResource(R.string.automation_review_save))
        }
    }

    Text(stringResource(R.string.automation_leader_key), style = MaterialTheme.typography.titleMedium)
    Text(
        if (leaderKeyAvailable) {
            leaderKey?.takeIf { it.enabled }?.displayLabel
                ?: stringResource(R.string.automation_leader_disabled)
        } else {
            stringResource(R.string.automation_leader_unavailable)
        },
    )
    if (leaderKeyAvailable && leaderKey?.writable == false) {
        Text(
            stringResource(R.string.automation_unknown_leader),
            color = MaterialTheme.colorScheme.error,
        )
    }
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

@Composable
private fun AutostartAutomationContent(
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
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            stringResource(R.string.automation_root_warning),
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    if (catalog?.scripts.isNullOrEmpty()) {
        Text(stringResource(R.string.automation_no_autostart))
    } else {
        catalog.scripts.forEach { script ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(script.displayName, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onEdit(script) }, enabled = enabled) {
                    Text(stringResource(R.string.automation_edit))
                }
                OutlinedButton(onClick = { onReviewDelete(script) }, enabled = enabled) {
                    Text(stringResource(R.string.automation_delete))
                }
            }
        }
    }
    OutlinedTextField(
        value = fileName,
        onValueChange = onFileNameChange,
        enabled = editingScript == null,
        label = { Text(stringResource(R.string.automation_filename)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("automation-autostart-name"),
        singleLine = true,
    )
    OutlinedTextField(
        value = content,
        onValueChange = onContentChange,
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

private sealed interface PendingAutomationAction {
    data class HidAction(
        val approval: NanoKvmHidShortcutActionApproval,
        val displayLabel: String,
    ) : PendingAutomationAction

    data class HidSave(
        val approval: NanoKvmHidShortcutSaveApproval,
        val displayLabel: String,
    ) : PendingAutomationAction

    data class AutostartWrite(
        val approval: NanoKvmAutostartWriteApproval,
    ) : PendingAutomationAction

    data class AutostartDelete(
        val approval: NanoKvmAutostartDeleteApproval,
    ) : PendingAutomationAction
}

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

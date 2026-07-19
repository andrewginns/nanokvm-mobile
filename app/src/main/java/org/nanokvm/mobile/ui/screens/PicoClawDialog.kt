@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.nanokvm.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.ApprovedPicoClawDestination
import org.nanokvm.mobile.runtime.ConsoleCommandSink
import org.nanokvm.mobile.runtime.PicoClawChatUiPhase
import org.nanokvm.mobile.runtime.PicoClawManualInputUiState
import org.nanokvm.mobile.runtime.PicoClawMessageUiState
import org.nanokvm.mobile.runtime.PicoClawModelConfigurationRequest
import org.nanokvm.mobile.runtime.PicoClawProfile
import org.nanokvm.mobile.runtime.PicoClawSupport
import org.nanokvm.mobile.runtime.PicoClawUiState

private enum class PicoClawSection { Runtime, History, Chat }

@Composable
internal fun PicoClawDialog(
    destinationLabel: String,
    destination: ApprovedPicoClawDestination,
    state: PicoClawUiState,
    commands: ConsoleCommandSink,
    onDismiss: () -> Unit,
) {
    var section by remember { mutableStateOf(PicoClawSection.Runtime) }
    var model by remember { mutableStateOf("") }
    var apiBase by remember { mutableStateOf("") }
    // Deliberately not saveable: this plaintext must not survive recreation or enter saved state.
    var apiKey by remember { mutableStateOf("") }
    var chatMessage by remember { mutableStateOf("") }
    var confirmUninstall by remember { mutableStateOf(false) }
    var historyToDelete by remember { mutableStateOf<Long?>(null) }
    var closeBlockedNotice by remember { mutableStateOf(false) }

    val dismiss = {
        apiKey = ""
        if (state.manualInputBlockedOrUncertain) {
            closeBlockedNotice = true
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        modifier = Modifier.testTag("picoclaw-dialog"),
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.picoclaw_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.support == PicoClawSupport.Unsupported -> Text(
                        stringResource(R.string.picoclaw_unsupported),
                        color = MaterialTheme.colorScheme.error,
                    )
                    !state.entered -> PicoClawConsent(
                        enabled = !state.operationInProgress,
                        onEnter = { commands.enterPicoClaw(destination) },
                    )
                    else -> {
                        PicoClawManualInputWarning(state)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            PicoClawSection.entries.forEach { candidate ->
                                FilterChip(
                                    selected = section == candidate,
                                    onClick = { section = candidate },
                                    label = {
                                        Text(
                                            stringResource(
                                                when (candidate) {
                                                    PicoClawSection.Runtime -> R.string.picoclaw_runtime_tab
                                                    PicoClawSection.History -> R.string.picoclaw_history_tab
                                                    PicoClawSection.Chat -> R.string.picoclaw_chat_tab
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                        state.notice?.let { notice ->
                            Card(colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            )) {
                                Text(notice.message, Modifier.padding(12.dp))
                            }
                        }
                        when (section) {
                            PicoClawSection.Runtime -> PicoClawRuntimeSection(
                                state = state,
                                model = model,
                                onModelChange = { model = it.take(256) },
                                apiBase = apiBase,
                                onApiBaseChange = { apiBase = it.take(2_048) },
                                apiKey = apiKey,
                                onApiKeyChange = { apiKey = it.take(4_096) },
                                onRefresh = { commands.refreshPicoClaw(destination) },
                                onInstall = { commands.installPicoClawRuntime(destination) },
                                onStart = { commands.startPicoClawRuntime(destination) },
                                onStop = { commands.stopPicoClawRuntime(destination) },
                                onUninstall = { confirmUninstall = true },
                                onProfile = { commands.setPicoClawProfile(destination, it) },
                                onConfigure = {
                                    val ownedKey = apiKey.toCharArray()
                                    apiKey = ""
                                    commands.configurePicoClawModel(
                                        destination,
                                        PicoClawModelConfigurationRequest(model, apiBase, ownedKey),
                                    )
                                },
                            )
                            PicoClawSection.History -> PicoClawHistorySection(
                                state = state,
                                onRefresh = { commands.refreshPicoClawHistories(destination) },
                                onOpen = { commands.loadPicoClawHistory(destination, it) },
                                onDelete = { historyToDelete = it },
                            )
                            PicoClawSection.Chat -> PicoClawChatSection(
                                state = state,
                                message = chatMessage,
                                onMessageChange = { chatMessage = it.take(32 * 1_024) },
                                onOpen = { commands.openPicoClawChat(destination) },
                                onSend = {
                                    val content = chatMessage
                                    chatMessage = ""
                                    commands.sendPicoClawChatMessage(destination, content)
                                },
                                onCancel = { commands.cancelPicoClawChat(destination) },
                                onRelease = { commands.closeAndReleasePicoClaw(destination) },
                            )
                        }
                    }
                }
                if (closeBlockedNotice) {
                    Text(
                        stringResource(R.string.picoclaw_close_first),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.picoclaw_close)) }
        },
    )

    if (confirmUninstall) {
        ConfirmPicoClawAction(
            title = stringResource(R.string.picoclaw_uninstall_title),
            body = stringResource(R.string.picoclaw_uninstall_body, destinationLabel),
            onDismiss = { confirmUninstall = false },
            onConfirm = {
                confirmUninstall = false
                commands.uninstallPicoClawRuntime(destination)
            },
        )
    }
    historyToDelete?.let { historyId ->
        ConfirmPicoClawAction(
            title = stringResource(R.string.picoclaw_delete_history_title),
            body = stringResource(R.string.picoclaw_delete_history_body, destinationLabel),
            onDismiss = { historyToDelete = null },
            onConfirm = {
                historyToDelete = null
                commands.deletePicoClawHistory(destination, historyId)
            },
        )
    }
}

@Composable
private fun PicoClawConsent(enabled: Boolean, onEnter: () -> Unit) {
    Card(
        modifier = Modifier.testTag("picoclaw-risk-warning"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.picoclaw_risk_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.picoclaw_risk_body))
            Text(
                stringResource(R.string.picoclaw_not_probed),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onEnter,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("picoclaw-consent-enter"),
            ) { Text(stringResource(R.string.picoclaw_consent_enter)) }
        }
    }
}

@Composable
private fun PicoClawManualInputWarning(state: PicoClawUiState) {
    if (!state.manualInputBlockedOrUncertain) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("picoclaw-manual-hid-lock"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.picoclaw_lock_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    if (state.manualInput == PicoClawManualInputUiState.Uncertain) {
                        R.string.picoclaw_lock_uncertain
                    } else {
                        R.string.picoclaw_lock_body
                    },
                ),
            )
        }
    }
}

@Composable
private fun PicoClawRuntimeSection(
    state: PicoClawUiState,
    model: String,
    onModelChange: (String) -> Unit,
    apiBase: String,
    onApiBaseChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUninstall: () -> Unit,
    onProfile: (PicoClawProfile) -> Unit,
    onConfigure: () -> Unit,
) {
    val enabled = !state.operationInProgress
    Text(
        stringResource(
            R.string.picoclaw_status,
            stringResource(if (state.installed) R.string.picoclaw_yes else R.string.picoclaw_no),
            stringResource(if (state.ready) R.string.picoclaw_yes else R.string.picoclaw_no),
            state.runtimePhase.name,
        ),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh, enabled = enabled) { Text(stringResource(R.string.picoclaw_refresh)) }
        OutlinedButton(onClick = onInstall, enabled = enabled && !state.installed) { Text(stringResource(R.string.picoclaw_install)) }
        OutlinedButton(onClick = onStart, enabled = enabled && state.installed && !state.ready) { Text(stringResource(R.string.picoclaw_start)) }
        OutlinedButton(onClick = onStop, enabled = enabled && state.ready) { Text(stringResource(R.string.picoclaw_stop)) }
        OutlinedButton(onClick = onUninstall, enabled = enabled && state.installed) { Text(stringResource(R.string.picoclaw_uninstall)) }
    }
    Text(stringResource(R.string.picoclaw_profile), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PicoClawProfile.entries.forEach { profile ->
            FilterChip(
                selected = state.profile == profile,
                enabled = enabled,
                onClick = { onProfile(profile) },
                label = { Text(stringResource(
                    if (profile == PicoClawProfile.Default) R.string.picoclaw_profile_default
                    else R.string.picoclaw_profile_kvm,
                )) },
            )
        }
    }
    OutlinedTextField(model, onModelChange, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.picoclaw_model)) }, singleLine = true)
    OutlinedTextField(apiBase, onApiBaseChange, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.picoclaw_api_base)) }, singleLine = true)
    OutlinedTextField(
        apiKey,
        onApiKeyChange,
        Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.picoclaw_api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    Text(stringResource(R.string.picoclaw_key_ephemeral), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = onConfigure,
        enabled = enabled && model.isNotBlank() && apiBase.isNotBlank() && apiKey.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.picoclaw_configure_model)) }
}

@Composable
private fun PicoClawHistorySection(
    state: PicoClawUiState,
    onRefresh: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    OutlinedButton(onClick = onRefresh, enabled = !state.operationInProgress) {
        Text(stringResource(R.string.picoclaw_refresh))
    }
    if (state.histories.isEmpty()) Text(stringResource(R.string.picoclaw_history_empty))
    state.histories.forEach { history ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(history.title, style = MaterialTheme.typography.titleSmall)
                Text(history.preview, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onOpen(history.id) }) { Text(stringResource(R.string.picoclaw_open_history)) }
                    TextButton(onClick = { onDelete(history.id) }) { Text(stringResource(R.string.picoclaw_delete_history)) }
                }
            }
        }
    }
    state.selectedHistoryTitle?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
    PicoClawMessages(state.selectedHistoryMessages)
}

@Composable
private fun PicoClawChatSection(
    state: PicoClawUiState,
    message: String,
    onMessageChange: (String) -> Unit,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onRelease: () -> Unit,
) {
    val open = state.chatPhase == PicoClawChatUiPhase.Open
    if (!open && !state.manualInputBlockedOrUncertain) {
        Button(onClick = onOpen, enabled = !state.operationInProgress, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.picoclaw_open_chat))
        }
    }
    PicoClawMessages(state.chatMessages)
    OutlinedTextField(
        message,
        onMessageChange,
        Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.picoclaw_chat_message)) },
        enabled = open,
        maxLines = 4,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSend, enabled = open && message.isNotBlank()) { Text(stringResource(R.string.picoclaw_send)) }
        OutlinedButton(onClick = onCancel, enabled = open) { Text(stringResource(R.string.picoclaw_cancel_run)) }
    }
    if (state.manualInputBlockedOrUncertain) {
        Button(onClick = onRelease, enabled = !state.operationInProgress, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.picoclaw_release))
        }
    }
}

@Composable
private fun PicoClawMessages(messages: List<PicoClawMessageUiState>) {
    messages.takeLast(24).forEach { message ->
        Text(
            stringResource(R.string.picoclaw_message_line, message.role.name, message.content),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ConfirmPicoClawAction(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.picoclaw_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.picoclaw_cancel)) } },
    )
}

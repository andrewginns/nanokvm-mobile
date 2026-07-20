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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.ApprovedOperatorDestination
import org.nanokvm.mobile.runtime.OperatorControls
import org.nanokvm.mobile.runtime.OperatorEphemeralOutput
import org.nanokvm.mobile.runtime.OperatorNotice
import org.nanokvm.mobile.runtime.OperatorNoticeKind
import org.nanokvm.mobile.runtime.OperatorScriptRunMode
import org.nanokvm.mobile.runtime.OperatorScriptUiState
import org.nanokvm.mobile.runtime.OperatorScriptUploadRequest
import org.nanokvm.mobile.runtime.OperatorSerialBaud
import org.nanokvm.mobile.runtime.OperatorSerialConfiguration
import org.nanokvm.mobile.runtime.OperatorSerialDataBits
import org.nanokvm.mobile.runtime.OperatorSerialFlowControl
import org.nanokvm.mobile.runtime.OperatorSerialParity
import org.nanokvm.mobile.runtime.OperatorSerialPort
import org.nanokvm.mobile.runtime.OperatorSerialStopBits
import org.nanokvm.mobile.runtime.OperatorTerminalUiPhase
import org.nanokvm.mobile.runtime.OperatorUiState
import org.nanokvm.mobile.runtime.utf8SizeAtMost
import org.nanokvm.mobile.ui.components.PoliteStatus
import org.nanokvm.mobile.ui.displayText

@Composable
internal fun OperatorDialog(
    destinationLabel: String,
    destination: ApprovedOperatorDestination,
    state: OperatorUiState,
    output: SharedFlow<OperatorEphemeralOutput>,
    controls: OperatorControls,
    onDismiss: () -> Unit,
    retainedMemory: OperatorDialogMemory? = null,
) {
    var pendingAction by remember { mutableStateOf<PendingOperatorAction?>(null) }
    val localMemory = remember { OperatorDialogMemory() }
    val memory = retainedMemory ?: localMemory
    // Terminal input may contain passwords or replayable commands; never retain or save it.
    var terminalInput by remember { mutableStateOf("") }
    var scriptName by rememberSaveable(
        destination.profileId,
        destination.authority,
        destination.sessionGeneration,
    ) { mutableStateOf("") }
    var serialConfiguration by rememberSaveable(
        destination.profileId,
        destination.authority,
        destination.sessionGeneration,
        stateSaver = operatorSerialConfigurationSaver,
    ) { mutableStateOf(OperatorSerialConfiguration()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(output, lifecycleOwner, memory) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                val dirtyOutput = Channel<Unit>(Channel.CONFLATED)
                launch {
                    for (ignored in dirtyOutput) {
                        withFrameNanos { }
                        memory.publishOutputSnapshot()
                    }
                }
                // Flush output retained just before a recreation cancelled the previous frame.
                dirtyOutput.trySend(Unit)
                try {
                    output.collect { event ->
                        memory.appendOutput(event)
                        dirtyOutput.trySend(Unit)
                    }
                } finally {
                    dirtyOutput.close()
                }
            }
        }
    }
    DisposableEffect(memory, retainedMemory) {
        onDispose {
            if (retainedMemory == null) memory.clear()
        }
    }
    DisposableEffect(pendingAction) {
        val ownedAction = pendingAction
        onDispose { ownedAction?.clear() }
    }

    val controlsEnabled = state.available && !state.operationInProgress
    AlertDialog(
        modifier = Modifier.fillMaxWidth(),
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.console_operator_title),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = controls::refreshOperatorScripts,
                    enabled = controlsEnabled,
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(
                            R.string.console_operator_refresh_scripts,
                        ),
                    )
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .testTag("operator-surface"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "root-warning") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            stringResource(R.string.console_operator_root_warning),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                state.notice?.let { notice ->
                    item(key = "notice") {
                        OperatorNoticeCard(notice)
                    }
                }

                item(key = "terminal") {
                    OperatorSection(stringResource(R.string.console_operator_terminal)) {
                        Text(state.terminalPhase.displayLabel())
                        when (state.terminalPhase) {
                            OperatorTerminalUiPhase.Inactive,
                            OperatorTerminalUiPhase.Failed -> OutlinedButton(
                                onClick = { pendingAction = PendingOperatorAction.OpenTerminal },
                                enabled = controlsEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("operator-open-terminal"),
                            ) {
                                Text(stringResource(R.string.console_operator_open_terminal))
                            }
                            OperatorTerminalUiPhase.Connecting,
                            OperatorTerminalUiPhase.Connected,
                            OperatorTerminalUiPhase.Closing -> OutlinedButton(
                                onClick = { controls.closeOperatorTerminal(destination) },
                                enabled = controlsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.console_operator_close_terminal))
                            }
                        }
                        if (state.terminalPhase == OperatorTerminalUiPhase.Connected) {
                            OutlinedTextField(
                                value = terminalInput,
                                onValueChange = {
                                    if (it.utf8SizeAtMost(MAX_TERMINAL_INPUT_BYTES) != null) {
                                        terminalInput = it
                                    }
                                },
                                label = {
                                    Text(stringResource(R.string.console_operator_terminal_input))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        controls.sendOperatorTerminalInput(
                                            destination,
                                            terminalInput + '\r',
                                        )
                                        terminalInput = ""
                                    },
                                    enabled = terminalInput.isNotEmpty(),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.console_operator_send_input))
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        controls.resizeOperatorTerminal(destination, 24, 80)
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.console_operator_resize_80x24))
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    controls.resizeOperatorTerminal(destination, 40, 120)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.console_operator_resize_120x40))
                            }
                        }
                    }
                }

                item(key = "serial") {
                    OperatorSection(stringResource(R.string.console_operator_serial)) {
                        SerialChoiceRow(
                            label = stringResource(R.string.console_operator_serial_port),
                            values = OperatorSerialPort.entries,
                            selected = serialConfiguration.port,
                            display = { it.devicePath },
                            onSelect = {
                                serialConfiguration = serialConfiguration.copy(port = it)
                            },
                        )
                        SerialChoiceRow(
                            label = stringResource(R.string.console_operator_serial_baud),
                            values = OperatorSerialBaud.entries,
                            selected = serialConfiguration.baud,
                            display = { it.bitsPerSecond.toString() },
                            onSelect = {
                                serialConfiguration = serialConfiguration.copy(baud = it)
                            },
                        )
                        SerialChoiceRow(
                            label = stringResource(R.string.console_operator_serial_parity),
                            values = OperatorSerialParity.entries,
                            selected = serialConfiguration.parity,
                            display = { it.displayLabel() },
                            onSelect = {
                                serialConfiguration = serialConfiguration.copy(parity = it)
                            },
                        )
                        SerialChoiceRow(
                            label = stringResource(R.string.console_operator_serial_flow),
                            values = OperatorSerialFlowControl.entries,
                            selected = serialConfiguration.flowControl,
                            display = { it.displayLabel() },
                            onSelect = {
                                serialConfiguration = serialConfiguration.copy(flowControl = it)
                            },
                        )
                        SerialChoiceRow(
                            label = stringResource(R.string.console_operator_serial_data_bits),
                            values = OperatorSerialDataBits.entries,
                            selected = serialConfiguration.dataBits,
                            display = { it.count.toString() },
                            onSelect = {
                                serialConfiguration = serialConfiguration.copy(dataBits = it)
                            },
                        )
                        SerialChoiceRow(
                            label = stringResource(R.string.console_operator_serial_stop_bits),
                            values = OperatorSerialStopBits.entries,
                            selected = serialConfiguration.stopBits,
                            display = { it.count.toString() },
                            onSelect = {
                                serialConfiguration = serialConfiguration.copy(stopBits = it)
                            },
                        )
                        if (state.serialActive) {
                            OutlinedButton(
                                onClick = { controls.exitOperatorSerial(destination) },
                                enabled = controlsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.console_operator_exit_serial))
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    pendingAction = PendingOperatorAction.StartSerial(
                                        serialConfiguration,
                                    )
                                },
                                enabled = controlsEnabled &&
                                    state.terminalPhase == OperatorTerminalUiPhase.Connected,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.console_operator_start_serial))
                            }
                        }
                    }
                }

                item(key = "scripts-header") {
                    OperatorSection(stringResource(R.string.console_operator_scripts)) {
                        if (state.loadingScripts) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.console_operator_loading_scripts))
                            }
                        }
                        if (state.scriptsLoaded && state.scripts.isEmpty()) {
                            Text(stringResource(R.string.console_operator_no_scripts))
                        }
                    }
                }
                items(
                    items = state.scripts,
                    key = OperatorScriptUiState::id,
                ) { script ->
                    ScriptRow(
                        script = script,
                        enabled = controlsEnabled,
                        onRunForeground = {
                            pendingAction = PendingOperatorAction.RunScript(
                                script,
                                OperatorScriptRunMode.Foreground,
                            )
                        },
                        onRunBackground = {
                            pendingAction = PendingOperatorAction.RunScript(
                                script,
                                OperatorScriptRunMode.Background,
                            )
                        },
                        onDelete = {
                            pendingAction = PendingOperatorAction.DeleteScript(script)
                        },
                    )
                }
                item(key = "script-upload") {
                    OperatorCard {
                        Text(
                            stringResource(R.string.console_operator_script_upload),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        OutlinedTextField(
                            value = scriptName,
                            onValueChange = { if (it.length <= 255) scriptName = it },
                            label = {
                                Text(stringResource(R.string.console_operator_script_name))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = memory.scriptContent,
                            onValueChange = memory::updateScriptContent,
                            label = {
                                Text(stringResource(R.string.console_operator_script_content))
                            },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                pendingAction = PendingOperatorAction.UploadScript(
                                    fileName = scriptName,
                                    content = memory.takeScriptContent(),
                                )
                            },
                            enabled = controlsEnabled && isSafeScriptName(scriptName) &&
                                memory.scriptUtf8ByteCount in 1..MAX_SCRIPT_UPLOAD_BYTES,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.console_operator_upload))
                        }
                        Text(
                            stringResource(R.string.console_operator_foreground_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.console_operator_background_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item(key = "output") {
                    OperatorSection(stringResource(R.string.console_operator_output)) {
                        if (memory.outputText.isEmpty()) {
                            Text(stringResource(R.string.console_operator_output_empty))
                        } else {
                            SelectionContainer {
                                Text(
                                    memory.outputText,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("operator-output"),
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    memory.clearOutput()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.console_operator_clear_output))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.console_close)) }
        },
    )

    pendingAction?.let { action ->
        ConfirmOperatorActionDialog(
            destinationLabel = destinationLabel,
            destination = destination,
            action = action,
            onDismiss = {
                action.clear()
                pendingAction = null
            },
            onConfirm = {
                action.execute(controls, destination)
                pendingAction = null
            },
        )
    }
}

@Composable
private fun OperatorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    OperatorCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun OperatorCard(
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
            content()
        }
    }
}

@Composable
private fun <Value> SerialChoiceRow(
    label: String,
    values: List<Value>,
    selected: Value,
    display: @Composable (Value) -> String,
    onSelect: (Value) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(display(value)) },
            )
        }
    }
}

@Composable
private fun ScriptRow(
    script: OperatorScriptUiState,
    enabled: Boolean,
    onRunForeground: () -> Unit,
    onRunBackground: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("operator-script-row"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(script.displayName, style = MaterialTheme.typography.titleSmall)
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onRunForeground,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("operator-script-run-foreground-${script.id}"),
                ) {
                    Text(stringResource(R.string.console_operator_run_foreground))
                }
                TextButton(
                    onClick = onRunBackground,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.console_operator_run_background))
                }
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.console_operator_delete_script))
                }
            }
        }
    }
}

@Composable
internal fun OperatorNoticeCard(notice: OperatorNotice) {
    OperatorNoticeSurface(kind = notice.kind, message = notice.displayText())
}

@Composable
private fun OperatorNoticeSurface(kind: OperatorNoticeKind, message: String) {
    val container = when (kind) {
        OperatorNoticeKind.Applied -> MaterialTheme.colorScheme.primaryContainer
        OperatorNoticeKind.Reconciled -> MaterialTheme.colorScheme.tertiaryContainer
        OperatorNoticeKind.Indeterminate,
        OperatorNoticeKind.Rejected -> MaterialTheme.colorScheme.errorContainer
        OperatorNoticeKind.Information -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    PoliteStatus {
        Card(colors = CardDefaults.cardColors(containerColor = container)) {
            Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfirmOperatorActionDialog(
    destinationLabel: String,
    destination: ApprovedOperatorDestination,
    action: PendingOperatorAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.console_operator_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.console_operator_confirm_destination,
                        destinationLabel,
                        destination.authority,
                    ),
                )
                Text(
                    stringResource(
                        R.string.console_operator_confirm_generation,
                        destination.sessionGeneration,
                    ),
                )
                Text(
                    stringResource(
                        R.string.console_operator_confirm_action,
                        action.actionLabel(),
                    ),
                )
                Text(
                    stringResource(
                        R.string.console_operator_confirm_consequence,
                        action.riskLabel(),
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
private fun OperatorTerminalUiPhase.displayLabel(): String = stringResource(
    when (this) {
        OperatorTerminalUiPhase.Inactive -> R.string.console_operator_terminal_inactive
        OperatorTerminalUiPhase.Connecting -> R.string.console_operator_terminal_connecting
        OperatorTerminalUiPhase.Connected -> R.string.console_operator_terminal_connected
        OperatorTerminalUiPhase.Closing -> R.string.console_operator_terminal_closing
        OperatorTerminalUiPhase.Failed -> R.string.console_operator_terminal_failed
    },
)

@Composable
private fun OperatorSerialParity.displayLabel(): String = stringResource(
    when (this) {
        OperatorSerialParity.None -> R.string.console_operator_serial_none
        OperatorSerialParity.Even -> R.string.console_operator_serial_even
        OperatorSerialParity.Odd -> R.string.console_operator_serial_odd
    },
)

@Composable
private fun OperatorSerialFlowControl.displayLabel(): String = stringResource(
    when (this) {
        OperatorSerialFlowControl.None -> R.string.console_operator_serial_none
        OperatorSerialFlowControl.Software -> R.string.console_operator_serial_software
        OperatorSerialFlowControl.Hardware -> R.string.console_operator_serial_hardware
    },
)

private sealed interface PendingOperatorAction {
    data object OpenTerminal : PendingOperatorAction
    data class StartSerial(val configuration: OperatorSerialConfiguration) : PendingOperatorAction
    class UploadScript(val fileName: String, content: ByteArray) : PendingOperatorAction {
        private var ownedContent = content
        val byteCount: Int get() = ownedContent.size
        fun takeContent(): ByteArray = ownedContent.also { ownedContent = ByteArray(0) }
        fun clearOwnedContent() = ownedContent.fill(0)
        override fun toString(): String =
            "UploadScript(fileName=<redacted>, byteCount=$byteCount, content=<redacted>)"
    }
    data class RunScript(
        val script: OperatorScriptUiState,
        val mode: OperatorScriptRunMode,
    ) : PendingOperatorAction
    data class DeleteScript(val script: OperatorScriptUiState) : PendingOperatorAction
}

private fun PendingOperatorAction.clear() {
    if (this is PendingOperatorAction.UploadScript) clearOwnedContent()
}

@Composable
private fun PendingOperatorAction.actionLabel(): String = when (this) {
    PendingOperatorAction.OpenTerminal ->
        stringResource(R.string.console_operator_action_open_terminal)
    is PendingOperatorAction.StartSerial -> {
        val dataBits = configuration.dataBits.count
        val stopBits = configuration.stopBits.count
        stringResource(
            R.string.console_operator_action_start_serial,
            configuration.port.devicePath,
            configuration.baud.bitsPerSecond,
            configuration.parity.displayLabel(),
            configuration.flowControl.displayLabel(),
            pluralStringResource(
                R.plurals.console_operator_serial_data_bit_count,
                dataBits,
                dataBits,
            ),
            pluralStringResource(
                R.plurals.console_operator_serial_stop_bit_count,
                stopBits,
                stopBits,
            ),
        )
    }
    is PendingOperatorAction.UploadScript -> stringResource(
        R.string.console_operator_action_upload,
        fileName,
        byteCount,
    )
    is PendingOperatorAction.RunScript -> stringResource(
        if (mode == OperatorScriptRunMode.Foreground) {
            R.string.console_operator_action_run_foreground
        } else {
            R.string.console_operator_action_run_background
        },
        script.displayName,
    )
    is PendingOperatorAction.DeleteScript ->
        stringResource(R.string.console_operator_action_delete, script.displayName)
}

@Composable
private fun PendingOperatorAction.riskLabel(): String = stringResource(
    when (this) {
        PendingOperatorAction.OpenTerminal -> R.string.console_operator_risk_terminal
        is PendingOperatorAction.StartSerial -> R.string.console_operator_risk_serial
        is PendingOperatorAction.UploadScript -> R.string.console_operator_risk_upload
        is PendingOperatorAction.RunScript -> if (mode == OperatorScriptRunMode.Foreground) {
            R.string.console_operator_risk_foreground
        } else {
            R.string.console_operator_risk_background
        }
        is PendingOperatorAction.DeleteScript -> R.string.console_operator_risk_delete
    },
)

private fun PendingOperatorAction.execute(
    controls: OperatorControls,
    destination: ApprovedOperatorDestination,
) {
    when (this) {
        PendingOperatorAction.OpenTerminal -> controls.enterOperatorTerminal(destination)
        is PendingOperatorAction.StartSerial ->
            controls.startOperatorSerial(destination, configuration)
        is PendingOperatorAction.UploadScript -> controls.uploadOperatorScript(
            destination,
            OperatorScriptUploadRequest(fileName, takeContent()),
        )
        is PendingOperatorAction.RunScript ->
            controls.runOperatorScript(destination, script.id, mode)
        is PendingOperatorAction.DeleteScript ->
            controls.deleteOperatorScript(destination, script.id)
    }
}

private fun isSafeScriptName(value: String): Boolean =
    value.length <= 255 && SAFE_SCRIPT_NAME.matches(value) && ".." !in value

private val SAFE_SCRIPT_NAME = Regex(
    "[A-Za-z0-9][A-Za-z0-9._-]{0,250}\\.(?:sh|py)",
    RegexOption.IGNORE_CASE,
)

private val operatorSerialConfigurationSaver = Saver<OperatorSerialConfiguration, List<Int>>(
    save = { configuration ->
        listOf(
            configuration.port.ordinal,
            configuration.baud.ordinal,
            configuration.parity.ordinal,
            configuration.flowControl.ordinal,
            configuration.dataBits.ordinal,
            configuration.stopBits.ordinal,
        )
    },
    restore = { values ->
        runCatching {
            OperatorSerialConfiguration(
                port = OperatorSerialPort.entries[values[0]],
                baud = OperatorSerialBaud.entries[values[1]],
                parity = OperatorSerialParity.entries[values[2]],
                flowControl = OperatorSerialFlowControl.entries[values[3]],
                dataBits = OperatorSerialDataBits.entries[values[4]],
                stopBits = OperatorSerialStopBits.entries[values[5]],
            )
        }.getOrDefault(OperatorSerialConfiguration())
    },
)

private const val MAX_SCRIPT_UPLOAD_BYTES = MAX_RETAINED_OPERATOR_SCRIPT_BYTES
private const val MAX_TERMINAL_INPUT_BYTES = 64 * 1024

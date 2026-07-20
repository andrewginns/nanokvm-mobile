package org.nanokvm.mobile.ui.screens

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import org.nanokvm.mobile.runtime.NanoKvmAutostartWriteApproval
import org.nanokvm.mobile.runtime.NanoKvmHidShortcutAction
import org.nanokvm.mobile.runtime.NanoKvmHidShortcutActionApproval
import org.nanokvm.mobile.runtime.NanoKvmHidShortcutSaveApproval
import org.nanokvm.mobile.runtime.NanoKvmPhysicalKeyRecordResult
import org.nanokvm.mobile.runtime.NanoKvmPhysicalShortcutRecorder
import org.nanokvm.mobile.runtime.NanoKvmRecordedHidKey
import org.nanokvm.mobile.runtime.isSafeAutostartEditorText

internal enum class AutomationTab { HID, AUTOSTART }

internal enum class AutomationNotice {
    ACTION_COMPLETE,
    ACTION_INDETERMINATE,
    ACTION_REJECTED,
    READ_FAILED,
    IMPORT_UNAVAILABLE,
    INVALID_EDITOR,
}

internal sealed interface PendingAutomationAction {
    data class HidAction(
        val approval: NanoKvmHidShortcutActionApproval,
        val displayLabel: String,
    ) : PendingAutomationAction {
        override fun toString(): String =
            "PendingAutomationAction.HidAction(action=${approval.action}, target=<redacted>)"
    }

    data class HidSave(
        val approval: NanoKvmHidShortcutSaveApproval,
        val displayLabel: String,
    ) : PendingAutomationAction {
        override fun toString(): String =
            "PendingAutomationAction.HidSave(target=<redacted>)"
    }

    data class AutostartWrite(
        val approval: NanoKvmAutostartWriteApproval,
    ) : PendingAutomationAction {
        override fun toString(): String =
            "PendingAutomationAction.AutostartWrite(target=<redacted>)"
    }

    data class AutostartDelete(
        val approval: NanoKvmAutostartDeleteApproval,
    ) : PendingAutomationAction {
        override fun toString(): String =
            "PendingAutomationAction.AutostartDelete(target=<redacted>)"
    }
}

internal data class AutomationDialogUiState(
    val tab: AutomationTab = AutomationTab.HID,
    val hidCatalog: NanoKvmAutomationHidCatalog? = null,
    val leaderKey: NanoKvmAutomationLeaderKey? = null,
    val autostartCatalog: NanoKvmAutomationAutostartCatalog? = null,
    val recordedKeys: List<NanoKvmRecordedHidKey> = emptyList(),
    val autostartName: String = "",
    val autostartText: String = "",
    val editingScript: NanoKvmAutomationAutostartScript? = null,
    val loading: Boolean = true,
    val operationInProgress: Boolean = false,
    val notice: AutomationNotice? = null,
    val hidReadNotice: AutomationNotice? = null,
    val autostartReadNotice: AutomationNotice? = null,
    val leaderKeyAvailable: Boolean,
    val pendingAction: PendingAutomationAction? = null,
) {
    val controlsEnabled: Boolean
        get() = !loading && !operationInProgress && pendingAction == null

    val visibleNotice: AutomationNotice?
        get() = when (tab) {
            AutomationTab.HID -> hidReadNotice
            AutomationTab.AUTOSTART -> autostartReadNotice
        } ?: notice

    override fun toString(): String =
        "AutomationDialogUiState(tab=$tab, hidShortcuts=${hidCatalog?.shortcuts?.size}, " +
            "autostartScripts=${autostartCatalog?.scripts?.size}, recordedKeys=${recordedKeys.size}, " +
            "editor=<redacted>, loading=$loading, operationInProgress=$operationInProgress, " +
            "notice=$notice, leaderKeyAvailable=$leaderKeyAvailable, pending=$pendingAction)"
}

/**
 * Session-scoped state owner for the automation dialog.
 *
 * The controller owns its coroutine scope, foreground lease, recorder and one-use approvals. The
 * composable is deliberately limited to rendering, focus and document-picker launch mechanics.
 */
internal class AutomationDialogController(
    private val gateway: NanoKvmAutomationGateway,
    leaderKeyAvailable: Boolean,
    coroutineContext: CoroutineContext = Dispatchers.Main.immediate,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val controllerJob = SupervisorJob(coroutineContext[Job])
    private val scope = CoroutineScope(
        coroutineContext.minusKey(Job) + controllerJob + CoroutineName("AutomationDialog"),
    )
    private val recorder = NanoKvmPhysicalShortcutRecorder()
    private var executingConfirmation: PendingAutomationAction? = null
    private val mutableState = MutableStateFlow(
        AutomationDialogUiState(leaderKeyAvailable = leaderKeyAvailable),
    )

    val state: StateFlow<AutomationDialogUiState> = mutableState.asStateFlow()

    init {
        gateway.onForeground()
        scope.launch {
            try {
                refreshAllInternal()
            } finally {
                finishBusy()
            }
        }
    }

    fun selectTab(tab: AutomationTab) {
        updateState { it.copy(tab = tab) }
    }

    fun refresh(): Boolean {
        if (!claimBusy(loading = true)) return false
        scope.launch {
            try {
                refreshAllInternal()
            } finally {
                finishBusy()
            }
        }
        return true
    }

    fun recordAndroidKeyCode(
        androidKeyCode: Int,
        repeatCount: Int,
    ): NanoKvmPhysicalKeyRecordResult {
        if (!canInteract()) return NanoKvmPhysicalKeyRecordResult.REPEAT_IGNORED
        val result = recorder.recordAndroidKeyCode(androidKeyCode, repeatCount)
        updateState { it.copy(recordedKeys = recorder.keys) }
        return result
    }

    fun clearRecordedKeys() {
        if (!canInteract()) return
        recorder.clear()
        updateState { it.copy(recordedKeys = emptyList()) }
    }

    fun reviewHidShortcutSave() {
        val snapshot = interactiveSnapshot() ?: return
        val catalog = snapshot.hidCatalog ?: return
        when (val review = gateway.reviewHidShortcutSave(catalog, snapshot.recordedKeys)) {
            is NanoKvmAutomationReviewResult.Ready -> setPending(
                PendingAutomationAction.HidSave(
                    review.approval,
                    snapshot.recordedKeys.joinToString(" + ") { it.displayLabel },
                ),
            )
            is NanoKvmAutomationReviewResult.Rejected -> rejectReview()
        }
    }

    fun reviewHidShortcutAction(
        shortcut: NanoKvmAutomationHidShortcut,
        action: NanoKvmHidShortcutAction,
    ) {
        val catalog = interactiveSnapshot()?.hidCatalog ?: return
        when (val review = gateway.reviewHidShortcutAction(catalog, shortcut, action)) {
            is NanoKvmAutomationReviewResult.Ready -> setPending(
                PendingAutomationAction.HidAction(review.approval, shortcut.displayLabel),
            )
            is NanoKvmAutomationReviewResult.Rejected -> rejectReview()
        }
    }

    fun setLeaderKey(wireCode: String?): Boolean = launchOperation {
        publishMutation(gateway.setLeaderKey(wireCode))
        refreshHidInternal()
    }

    fun updateAutostartName(value: String) {
        updateState { current ->
            if (current.editingScript != null) current else current.copy(autostartName = value.take(255))
        }
    }

    fun updateAutostartText(value: String) {
        if (!isSafeAutostartEditorText(value)) return
        updateState { it.copy(autostartText = value) }
    }

    /** Takes ownership of [bytes] and guarantees that the caller's array is cleared. */
    fun importAutostartOwned(bytes: ByteArray): Boolean {
        if (!canInteract()) {
            bytes.fill(0)
            return false
        }
        val imported = try {
            NanoKvmAutostartEditorBuffer.importOwned(bytes)
        } catch (_: IllegalArgumentException) {
            bytes.fill(0)
            updateState { it.copy(notice = AutomationNotice.INVALID_EDITOR) }
            return false
        }
        val text = try {
            imported.copyText()
        } finally {
            imported.close()
        }
        return updateState { it.copy(autostartText = text) }
    }

    fun reportImportUnavailable() {
        updateState { it.copy(notice = AutomationNotice.IMPORT_UNAVAILABLE) }
    }

    fun editAutostart(script: NanoKvmAutomationAutostartScript): Boolean = launchOperation {
        val catalog = state.value.autostartCatalog
        if (catalog == null) {
            updateState { it.copy(notice = AutomationNotice.READ_FAILED) }
            return@launchOperation
        }
        when (val result = gateway.readAutostartContent(catalog, script)) {
            is NanoKvmAutomationReadResult.Success -> {
                val text = try {
                    result.value.copyText()
                } finally {
                    result.value.close()
                }
                updateState {
                    it.copy(
                        autostartText = text,
                        autostartName = script.displayName,
                        editingScript = script,
                    )
                }
            }
            is NanoKvmAutomationReadResult.Failure ->
                updateState { it.copy(notice = AutomationNotice.READ_FAILED) }
        }
    }

    fun cancelAutostartEdit() {
        updateState {
            it.copy(autostartName = "", autostartText = "", editingScript = null)
        }
    }

    fun reviewAutostartWrite() {
        val snapshot = interactiveSnapshot() ?: return
        val catalog = snapshot.autostartCatalog ?: return
        val editor = try {
            NanoKvmAutostartEditorBuffer.fromText(snapshot.autostartText)
        } catch (_: IllegalArgumentException) {
            updateState { it.copy(notice = AutomationNotice.INVALID_EDITOR) }
            return
        }
        val review = snapshot.editingScript?.let {
            gateway.reviewAutostartUpdate(catalog, it, editor)
        } ?: gateway.reviewAutostartCreate(catalog, snapshot.autostartName, editor)
        when (review) {
            is NanoKvmAutomationReviewResult.Ready -> {
                val accepted = setPending(
                    PendingAutomationAction.AutostartWrite(review.approval),
                    clearEditor = true,
                )
                if (!accepted) gateway.discardAutostartWrite(review.approval)
            }
            is NanoKvmAutomationReviewResult.Rejected -> {
                editor.close()
                updateState { it.copy(notice = AutomationNotice.INVALID_EDITOR) }
            }
        }
    }

    fun reviewAutostartDelete(script: NanoKvmAutomationAutostartScript) {
        val catalog = interactiveSnapshot()?.autostartCatalog ?: return
        when (val review = gateway.reviewAutostartDelete(catalog, script)) {
            is NanoKvmAutomationReviewResult.Ready ->
                setPending(PendingAutomationAction.AutostartDelete(review.approval))
            is NanoKvmAutomationReviewResult.Rejected -> rejectReview()
        }
    }

    fun cancelPendingAction() {
        val action = synchronized(lifecycleLock) {
            val current = mutableState.value
            if (closed.get() || current.operationInProgress) return
            current.pendingAction.also { mutableState.value = current.copy(pendingAction = null) }
        }
        gateway.discardAutostartWrite(
            (action as? PendingAutomationAction.AutostartWrite)?.approval,
        )
    }

    fun confirmPendingAction(): Boolean {
        synchronized(lifecycleLock) {
            val current = mutableState.value
            val action = current.pendingAction ?: return false
            if (closed.get() || current.loading || current.operationInProgress) return false
            mutableState.value = current.copy(operationInProgress = true, notice = null)
            executingConfirmation = action
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
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
                    updateState { it.copy(pendingAction = null) }
                    publishMutation(result)
                    when (action) {
                        is PendingAutomationAction.HidAction,
                        is PendingAutomationAction.HidSave -> refreshHidInternal()
                        is PendingAutomationAction.AutostartWrite,
                        is PendingAutomationAction.AutostartDelete -> refreshAutostartInternal()
                    }
                    if (action is PendingAutomationAction.HidSave) {
                        recorder.clear()
                        updateState { it.copy(recordedKeys = emptyList()) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    updateState {
                        it.copy(
                            pendingAction = null,
                            notice = AutomationNotice.ACTION_REJECTED,
                        )
                    }
                } finally {
                    synchronized(lifecycleLock) { executingConfirmation = null }
                    finishBusy()
                }
            }
        }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val abandonedWrite = synchronized(lifecycleLock) {
            val current = mutableState.value
            val abandoned = if (executingConfirmation == null) {
                (current.pendingAction as? PendingAutomationAction.AutostartWrite)?.approval
            } else {
                null
            }
            mutableState.value = current.copy(
                recordedKeys = emptyList(),
                autostartName = "",
                autostartText = "",
                editingScript = null,
                pendingAction = null,
                loading = false,
                operationInProgress = false,
            )
            abandoned
        }
        controllerJob.cancel()
        gateway.discardAutostartWrite(abandonedWrite)
        recorder.clear()
        gateway.onBackground()
        scope.cancel()
    }

    private suspend fun refreshAllInternal() {
        refreshHidInternal()
        refreshAutostartInternal()
    }

    private suspend fun refreshHidInternal() {
        updateState { it.copy(hidReadNotice = null) }
        when (val result = gateway.refreshHidShortcuts()) {
            is NanoKvmAutomationReadResult.Success ->
                updateState { it.copy(hidCatalog = result.value) }
            is NanoKvmAutomationReadResult.Failure -> updateState {
                it.copy(hidCatalog = null, hidReadNotice = AutomationNotice.READ_FAILED)
            }
        }
        if (state.value.leaderKeyAvailable) {
            when (val result = gateway.refreshLeaderKey()) {
                is NanoKvmAutomationReadResult.Success ->
                    updateState { it.copy(leaderKey = result.value) }
                is NanoKvmAutomationReadResult.Failure -> {
                    if (result.error.kind == NanoKvmAutomationError.Kind.UNSUPPORTED) {
                        updateState { it.copy(leaderKey = null, leaderKeyAvailable = false) }
                    } else {
                        updateState {
                            it.copy(
                                leaderKey = null,
                                hidReadNotice = AutomationNotice.READ_FAILED,
                            )
                        }
                    }
                }
            }
        } else {
            updateState { it.copy(leaderKey = null) }
        }
    }

    private suspend fun refreshAutostartInternal() {
        updateState { it.copy(autostartReadNotice = null) }
        when (val result = gateway.refreshAutostartScripts()) {
            is NanoKvmAutomationReadResult.Success ->
                updateState { it.copy(autostartCatalog = result.value) }
            is NanoKvmAutomationReadResult.Failure -> updateState {
                it.copy(
                    autostartCatalog = null,
                    autostartReadNotice = AutomationNotice.READ_FAILED,
                )
            }
        }
    }

    private fun publishMutation(result: NanoKvmAutomationCommandResult<*>) {
        val notice = when (result) {
            is NanoKvmAutomationCommandResult.Completed -> AutomationNotice.ACTION_COMPLETE
            is NanoKvmAutomationCommandResult.Indeterminate ->
                AutomationNotice.ACTION_INDETERMINATE
            is NanoKvmAutomationCommandResult.Rejected -> AutomationNotice.ACTION_REJECTED
        }
        updateState { it.copy(notice = notice) }
    }

    private fun launchOperation(block: suspend () -> Unit): Boolean {
        if (!claimBusy(loading = false)) return false
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateState { it.copy(notice = AutomationNotice.ACTION_REJECTED) }
            } finally {
                finishBusy()
            }
        }
        return true
    }

    private fun claimBusy(loading: Boolean): Boolean = synchronized(lifecycleLock) {
        val current = mutableState.value
        if (closed.get() || current.loading || current.operationInProgress || current.pendingAction != null) {
            return@synchronized false
        }
        mutableState.value = current.copy(
            loading = loading,
            operationInProgress = !loading,
            notice = null,
        )
        true
    }

    private fun finishBusy() {
        updateState { it.copy(loading = false, operationInProgress = false) }
    }

    private fun interactiveSnapshot(): AutomationDialogUiState? = synchronized(lifecycleLock) {
        mutableState.value.takeIf { !closed.get() && it.controlsEnabled }
    }

    private fun canInteract(): Boolean = interactiveSnapshot() != null

    private fun rejectReview() {
        updateState { it.copy(notice = AutomationNotice.ACTION_REJECTED) }
    }

    private fun setPending(
        action: PendingAutomationAction,
        clearEditor: Boolean = false,
    ): Boolean = synchronized(lifecycleLock) {
        val current = mutableState.value
        if (closed.get() || !current.controlsEnabled) return@synchronized false
        mutableState.value = current.copy(
            pendingAction = action,
            autostartName = if (clearEditor) "" else current.autostartName,
            autostartText = if (clearEditor) "" else current.autostartText,
            editingScript = if (clearEditor) null else current.editingScript,
        )
        true
    }

    private fun updateState(
        transform: (AutomationDialogUiState) -> AutomationDialogUiState,
    ): Boolean = synchronized(lifecycleLock) {
        if (closed.get()) return@synchronized false
        mutableState.value = transform(mutableState.value)
        true
    }
}

package org.nanokvm.mobile.runtime

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nanokvm.protocol.ApiResponseException
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.InvalidApiResponseException
import org.nanokvm.protocol.NanoKvmAutostartFailure
import org.nanokvm.protocol.NanoKvmAutostartOperationException
import org.nanokvm.protocol.NanoKvmHidKeyCode

internal enum class NanoKvmAutomationOperation {
    HID_LIST,
    HID_SAVE,
    HID_RUN,
    HID_DELETE,
    LEADER_READ,
    LEADER_SET,
    AUTOSTART_LIST,
    AUTOSTART_READ,
    AUTOSTART_CREATE,
    AUTOSTART_UPDATE,
    AUTOSTART_DELETE,
}

/** Safe UI error metadata; it retains no peer message, identifier, or executable content. */
internal data class NanoKvmAutomationError(
    val operation: NanoKvmAutomationOperation,
    val kind: Kind,
) {
    enum class Kind {
        SESSION_CHANGED,
        NOT_FOREGROUND,
        FOREIGN_OR_STALE_STATE,
        CONFIRMATION_REQUIRED,
        INVALID_REQUEST,
        NOT_CONNECTED,
        UNKNOWN_READ_ONLY,
        UNSUPPORTED,
        AUTHENTICATION_EXPIRED,
        CONNECTION,
        SERVER_REJECTED,
        INVALID_RESPONSE,
        UNEXPECTED,
    }
}

internal sealed interface NanoKvmAutomationReadResult<out Value> {
    data class Success<Value>(val value: Value) : NanoKvmAutomationReadResult<Value>
    data class Failure(val error: NanoKvmAutomationError) : NanoKvmAutomationReadResult<Nothing>
}

/** Every invocation dispatches at most once. Indeterminate outcomes are never replayed. */
internal sealed interface NanoKvmAutomationCommandResult<out Value> {
    data class Completed<Value>(val value: Value) : NanoKvmAutomationCommandResult<Value>
    data class Indeterminate(val error: NanoKvmAutomationError) :
        NanoKvmAutomationCommandResult<Nothing>
    data class Rejected(val error: NanoKvmAutomationError) :
        NanoKvmAutomationCommandResult<Nothing>
}

internal sealed interface NanoKvmAutomationReviewResult<out Approval> {
    data class Ready<Approval>(val approval: Approval) : NanoKvmAutomationReviewResult<Approval>
    data class Rejected(val error: NanoKvmAutomationError) :
        NanoKvmAutomationReviewResult<Nothing>
}

internal data class NanoKvmAutomationHidKey(
    val code: String,
    val displayLabel: String,
    val known: Boolean,
)

/** UI handle bound to one exact gateway/catalog generation. */
internal class NanoKvmAutomationHidShortcut internal constructor(
    val stableId: String,
    keys: List<NanoKvmAutomationHidKey>,
    val runnable: Boolean,
    internal val binding: NanoKvmSessionBinding,
    internal val portShortcut: NanoKvmAutomationPortHidShortcut,
) {
    val keys: List<NanoKvmAutomationHidKey> = keys.toList()

    val displayLabel: String
        get() = keys.joinToString(" + ") { it.displayLabel.ifBlank { it.code } }

    override fun toString(): String =
        "NanoKvmAutomationHidShortcut(keys=${keys.size}, values=<redacted>)"
}

internal class NanoKvmAutomationHidCatalog internal constructor(
    shortcuts: List<NanoKvmAutomationHidShortcut>,
    internal val binding: NanoKvmSessionBinding,
    internal val lifecycleGeneration: Long,
    internal val portCatalog: NanoKvmAutomationPortHidCatalog,
) {
    val shortcuts: List<NanoKvmAutomationHidShortcut> = shortcuts.toList()

    override fun toString(): String =
        "NanoKvmAutomationHidCatalog(shortcuts=${shortcuts.size}, destination=<redacted>)"
}

internal data class NanoKvmAutomationLeaderKey(
    val code: String,
    val displayLabel: String,
    val enabled: Boolean,
    val writable: Boolean,
)

internal enum class NanoKvmHidShortcutAction { RUN, DELETE }

internal class NanoKvmHidShortcutActionApproval internal constructor(
    private val owner: Any,
    internal val binding: NanoKvmSessionBinding,
    internal val lifecycleGeneration: Long,
    internal val catalog: NanoKvmAutomationHidCatalog,
    internal val shortcut: NanoKvmAutomationHidShortcut,
    internal val action: NanoKvmHidShortcutAction,
) {
    private var consumed = false

    internal fun consume(expectedOwner: Any): Boolean = synchronized(this) {
        if (consumed || owner !== expectedOwner) return@synchronized false
        consumed = true
        true
    }

    override fun toString(): String =
        "NanoKvmHidShortcutActionApproval(action=$action, target=<redacted>, consumed=$consumed)"
}

internal class NanoKvmHidShortcutSaveApproval internal constructor(
    private val owner: Any,
    internal val binding: NanoKvmSessionBinding,
    internal val lifecycleGeneration: Long,
    internal val catalog: NanoKvmAutomationHidCatalog,
    wireCodes: List<String>,
) {
    internal val wireCodes: List<String> = wireCodes.toList()
    private var consumed = false

    internal fun consume(expectedOwner: Any): Boolean = synchronized(this) {
        if (consumed || owner !== expectedOwner) return@synchronized false
        consumed = true
        true
    }

    override fun toString(): String =
        "NanoKvmHidShortcutSaveApproval(keys=${wireCodes.size}, values=<redacted>, consumed=$consumed)"
}

internal data class NanoKvmAutomationHidRunReceipt(val reportsSent: Int)
internal data object NanoKvmAutomationMutationCompleted

internal class NanoKvmAutomationAutostartScript internal constructor(
    val displayName: String,
    internal val binding: NanoKvmSessionBinding,
    internal val portScript: NanoKvmAutomationPortAutostartScript,
) {
    override fun toString(): String =
        "NanoKvmAutomationAutostartScript(displayName=<redacted>)"
}

internal class NanoKvmAutomationAutostartCatalog internal constructor(
    scripts: List<NanoKvmAutomationAutostartScript>,
    internal val binding: NanoKvmSessionBinding,
    internal val lifecycleGeneration: Long,
    internal val portCatalog: NanoKvmAutomationPortAutostartCatalog,
) {
    val scripts: List<NanoKvmAutomationAutostartScript> = scripts.toList()

    override fun toString(): String =
        "NanoKvmAutomationAutostartCatalog(scripts=${scripts.size}, destination=<redacted>)"
}

internal enum class NanoKvmAutostartReviewKind { CREATE, UPDATE }

/** One-use exact write confirmation. Closing it clears its root-equivalent content. */
internal class NanoKvmAutostartWriteApproval internal constructor(
    private val owner: Any,
    internal val binding: NanoKvmSessionBinding,
    internal val lifecycleGeneration: Long,
    internal val catalog: NanoKvmAutomationAutostartCatalog,
    internal val script: NanoKvmAutomationAutostartScript?,
    internal val write: NanoKvmAutomationPortAutostartWrite,
    val kind: NanoKvmAutostartReviewKind,
    val targetDisplayName: String,
) : Closeable {
    private var consumed = false

    val byteCount: Int = write.byteCount

    internal fun consume(expectedOwner: Any): Boolean = synchronized(this) {
        if (consumed || owner !== expectedOwner) return@synchronized false
        consumed = true
        true
    }

    override fun close() = write.close()

    override fun toString(): String =
        "NanoKvmAutostartWriteApproval(kind=$kind, target=<redacted>, " +
            "byteCount=$byteCount, content=<redacted>, consumed=$consumed)"
}

internal class NanoKvmAutostartDeleteApproval internal constructor(
    private val owner: Any,
    internal val binding: NanoKvmSessionBinding,
    internal val lifecycleGeneration: Long,
    internal val catalog: NanoKvmAutomationAutostartCatalog,
    internal val script: NanoKvmAutomationAutostartScript,
) {
    private var consumed = false
    val targetDisplayName: String = script.displayName

    internal fun consume(expectedOwner: Any): Boolean = synchronized(this) {
        if (consumed || owner !== expectedOwner) return@synchronized false
        consumed = true
        true
    }

    override fun toString(): String =
        "NanoKvmAutostartDeleteApproval(target=<redacted>, consumed=$consumed)"
}

internal data class NanoKvmAutomationAutostartReceipt(
    val displayName: String,
    val byteCount: Int,
    val kind: NanoKvmAutostartReviewKind,
) {
    override fun toString(): String =
        "NanoKvmAutomationAutostartReceipt(displayName=<redacted>, byteCount=$byteCount, kind=$kind)"
}

/**
 * Foreground-only, session-generation-bound automation gateway.
 *
 * All network/input work is serialized. Every catalog-consuming mutation invalidates authority
 * before its sole dispatch. Backgrounding invalidates catalogs and approvals through the lifecycle
 * generation; late results are discarded as indeterminate.
 */
internal class NanoKvmAutomationGateway internal constructor(
    private val port: NanoKvmAutomationPort,
    val binding: NanoKvmSessionBinding,
    private val onAuthenticationExpired: () -> Unit = {},
    private val currentBinding: () -> NanoKvmSessionBinding?,
) : AutoCloseable {
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val approvalOwner = Any()
    private var foreground = false
    private var closed = false
    private var lifecycleGeneration = 0L
    private var latestHidCatalog: NanoKvmAutomationHidCatalog? = null
    private var latestLeaderKey: NanoKvmAutomationLeaderKey? = null
    private var latestAutostartCatalog: NanoKvmAutomationAutostartCatalog? = null
    private val outstandingAutostartWrites = mutableSetOf<NanoKvmAutostartWriteApproval>()

    fun onForeground() = synchronized(stateLock) {
        if (closed || foreground) return@synchronized
        foreground = true
        lifecycleGeneration++
    }

    fun onBackground() = synchronized(stateLock) {
        if (!foreground && latestHidCatalog == null && latestAutostartCatalog == null) {
            return@synchronized
        }
        foreground = false
        lifecycleGeneration++
        invalidateAllLocked()
    }

    suspend fun refreshHidShortcuts(): NanoKvmAutomationReadResult<NanoKvmAutomationHidCatalog> =
        operationMutex.withLock {
            val lifecycle = requireActive(NanoKvmAutomationOperation.HID_LIST)?.let {
                return@withLock NanoKvmAutomationReadResult.Failure(it)
            } ?: currentLifecycle()
            synchronized(stateLock) { latestHidCatalog = null }
            val portCatalog = try {
                port.listHidShortcuts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return@withLock failure(NanoKvmAutomationOperation.HID_LIST, error)
            }
            requireSameActive(lifecycle, NanoKvmAutomationOperation.HID_LIST)?.let {
                return@withLock NanoKvmAutomationReadResult.Failure(it)
            }
            val catalog = NanoKvmAutomationHidCatalog(
                shortcuts = portCatalog.shortcuts.map { shortcut ->
                    NanoKvmAutomationHidShortcut(
                        stableId = shortcut.stableId,
                        keys = shortcut.keys.map { key ->
                            NanoKvmAutomationHidKey(key.code, key.label, key.known)
                        },
                        runnable = shortcut.runnable,
                        binding = binding,
                        portShortcut = shortcut,
                    )
                },
                binding = binding,
                lifecycleGeneration = lifecycle,
                portCatalog = portCatalog,
            )
            synchronized(stateLock) { latestHidCatalog = catalog }
            NanoKvmAutomationReadResult.Success(catalog)
        }

    suspend fun refreshLeaderKey(): NanoKvmAutomationReadResult<NanoKvmAutomationLeaderKey> =
        operationMutex.withLock {
            val lifecycle = requireActive(NanoKvmAutomationOperation.LEADER_READ)?.let {
                return@withLock NanoKvmAutomationReadResult.Failure(it)
            } ?: currentLifecycle()
            synchronized(stateLock) { latestLeaderKey = null }
            val state = try {
                port.leaderKey()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return@withLock failure(NanoKvmAutomationOperation.LEADER_READ, error)
            }
            requireSameActive(lifecycle, NanoKvmAutomationOperation.LEADER_READ)?.let {
                return@withLock NanoKvmAutomationReadResult.Failure(it)
            }
            val value = NanoKvmAutomationLeaderKey(
                code = state.code,
                displayLabel = state.displayLabel,
                enabled = state.enabled,
                writable = state.known,
            )
            synchronized(stateLock) { latestLeaderKey = value }
            NanoKvmAutomationReadResult.Success(value)
        }

    suspend fun setLeaderKey(
        wireCode: String?,
    ): NanoKvmAutomationCommandResult<NanoKvmAutomationMutationCompleted> =
        operationMutex.withLock {
            requireActive(NanoKvmAutomationOperation.LEADER_SET)?.let {
                return@withLock NanoKvmAutomationCommandResult.Rejected(it)
            }
            val observed = synchronized(stateLock) { latestLeaderKey }
                ?: return@withLock rejected(
                    NanoKvmAutomationOperation.LEADER_SET,
                    NanoKvmAutomationError.Kind.FOREIGN_OR_STALE_STATE,
                )
            if (!observed.writable) {
                return@withLock rejected(
                    NanoKvmAutomationOperation.LEADER_SET,
                    NanoKvmAutomationError.Kind.UNKNOWN_READ_ONLY,
                )
            }
            if (wireCode != null && runCatching { NanoKvmHidKeyCode.known(wireCode) }.isFailure) {
                return@withLock rejected(
                    NanoKvmAutomationOperation.LEADER_SET,
                    NanoKvmAutomationError.Kind.INVALID_REQUEST,
                )
            }
            synchronized(stateLock) { latestLeaderKey = null }
            dispatch(NanoKvmAutomationOperation.LEADER_SET) {
                if (wireCode == null) port.disableLeaderKey() else port.setLeaderKey(wireCode)
                NanoKvmAutomationMutationCompleted
            }
        }

    fun reviewHidShortcutAction(
        catalog: NanoKvmAutomationHidCatalog,
        shortcut: NanoKvmAutomationHidShortcut,
        action: NanoKvmHidShortcutAction,
    ): NanoKvmAutomationReviewResult<NanoKvmHidShortcutActionApproval> {
        validateHidHandle(catalog, shortcut, action.operation)?.let {
            return NanoKvmAutomationReviewResult.Rejected(it)
        }
        if (action == NanoKvmHidShortcutAction.RUN && !shortcut.runnable) {
            return NanoKvmAutomationReviewResult.Rejected(
                error(action.operation, NanoKvmAutomationError.Kind.UNKNOWN_READ_ONLY),
            )
        }
        return NanoKvmAutomationReviewResult.Ready(
            NanoKvmHidShortcutActionApproval(
                owner = approvalOwner,
                binding = binding,
                lifecycleGeneration = currentLifecycle(),
                catalog = catalog,
                shortcut = shortcut,
                action = action,
            ),
        )
    }

    fun reviewHidShortcutSave(
        catalog: NanoKvmAutomationHidCatalog,
        keys: List<NanoKvmRecordedHidKey>,
    ): NanoKvmAutomationReviewResult<NanoKvmHidShortcutSaveApproval> {
        validateHidCatalog(catalog, NanoKvmAutomationOperation.HID_SAVE)?.let {
            return NanoKvmAutomationReviewResult.Rejected(it)
        }
        val wireCodes = keys.map(NanoKvmRecordedHidKey::wireCode)
        if (
            wireCodes.size !in 1..6 ||
            wireCodes.distinct().size != wireCodes.size ||
            wireCodes.any { runCatching { NanoKvmHidKeyCode.known(it) }.isFailure }
        ) {
            return NanoKvmAutomationReviewResult.Rejected(
                error(NanoKvmAutomationOperation.HID_SAVE, NanoKvmAutomationError.Kind.INVALID_REQUEST),
            )
        }
        return NanoKvmAutomationReviewResult.Ready(
            NanoKvmHidShortcutSaveApproval(
                owner = approvalOwner,
                binding = binding,
                lifecycleGeneration = currentLifecycle(),
                catalog = catalog,
                wireCodes = wireCodes,
            ),
        )
    }

    suspend fun executeHidShortcutAction(
        approval: NanoKvmHidShortcutActionApproval?,
    ): NanoKvmAutomationCommandResult<*> = operationMutex.withLock {
        if (approval?.consume(approvalOwner) != true) {
            return@withLock rejected(
                approval?.action?.operation ?: NanoKvmAutomationOperation.HID_RUN,
                NanoKvmAutomationError.Kind.CONFIRMATION_REQUIRED,
            )
        }
        validateApprovalLifecycle(
            approval.binding,
            approval.lifecycleGeneration,
            approval.action.operation,
        )?.let { return@withLock NanoKvmAutomationCommandResult.Rejected(it) }
        validateHidHandle(approval.catalog, approval.shortcut, approval.action.operation)?.let {
            return@withLock NanoKvmAutomationCommandResult.Rejected(it)
        }
        invalidateHidCatalog()
        when (approval.action) {
            NanoKvmHidShortcutAction.DELETE -> dispatch(approval.action.operation) {
                port.deleteHidShortcut(approval.catalog.portCatalog, approval.shortcut.portShortcut)
                NanoKvmAutomationMutationCompleted
            }
            NanoKvmHidShortcutAction.RUN -> runHidShortcutOnce(approval)
        }
    }

    suspend fun executeHidShortcutSave(
        approval: NanoKvmHidShortcutSaveApproval?,
    ): NanoKvmAutomationCommandResult<NanoKvmAutomationMutationCompleted> =
        operationMutex.withLock {
            if (approval?.consume(approvalOwner) != true) {
                return@withLock rejected(
                    NanoKvmAutomationOperation.HID_SAVE,
                    NanoKvmAutomationError.Kind.CONFIRMATION_REQUIRED,
                )
            }
            validateApprovalLifecycle(
                approval.binding,
                approval.lifecycleGeneration,
                NanoKvmAutomationOperation.HID_SAVE,
            )?.let { return@withLock NanoKvmAutomationCommandResult.Rejected(it) }
            validateHidCatalog(approval.catalog, NanoKvmAutomationOperation.HID_SAVE)?.let {
                return@withLock NanoKvmAutomationCommandResult.Rejected(it)
            }
            invalidateHidCatalog()
            dispatch(NanoKvmAutomationOperation.HID_SAVE) {
                port.saveHidShortcut(approval.wireCodes)
                NanoKvmAutomationMutationCompleted
            }
        }

    suspend fun refreshAutostartScripts():
        NanoKvmAutomationReadResult<NanoKvmAutomationAutostartCatalog> =
        operationMutex.withLock {
            val lifecycle = requireActive(NanoKvmAutomationOperation.AUTOSTART_LIST)?.let {
                return@withLock NanoKvmAutomationReadResult.Failure(it)
            } ?: currentLifecycle()
            synchronized(stateLock) { latestAutostartCatalog = null }
            val portCatalog = try {
                port.listAutostartScripts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return@withLock failure(NanoKvmAutomationOperation.AUTOSTART_LIST, error)
            }
            requireSameActive(lifecycle, NanoKvmAutomationOperation.AUTOSTART_LIST)?.let {
                return@withLock NanoKvmAutomationReadResult.Failure(it)
            }
            val catalog = NanoKvmAutomationAutostartCatalog(
                scripts = portCatalog.scripts.map { script ->
                    NanoKvmAutomationAutostartScript(
                        displayName = script.displayName,
                        binding = binding,
                        portScript = script,
                    )
                },
                binding = binding,
                lifecycleGeneration = lifecycle,
                portCatalog = portCatalog,
            )
            synchronized(stateLock) { latestAutostartCatalog = catalog }
            NanoKvmAutomationReadResult.Success(catalog)
        }

    suspend fun readAutostartContent(
        catalog: NanoKvmAutomationAutostartCatalog,
        script: NanoKvmAutomationAutostartScript,
    ): NanoKvmAutomationReadResult<NanoKvmAutostartEditorBuffer> = operationMutex.withLock {
        validateAutostartHandle(catalog, script, NanoKvmAutomationOperation.AUTOSTART_READ)?.let {
            return@withLock NanoKvmAutomationReadResult.Failure(it)
        }
        val lifecycle = currentLifecycle()
        val content = try {
            port.readAutostartContent(catalog.portCatalog, script.portScript)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withLock failure(NanoKvmAutomationOperation.AUTOSTART_READ, error)
        }
        try {
            requireSameActive(lifecycle, NanoKvmAutomationOperation.AUTOSTART_READ)?.let {
                return@withLock NanoKvmAutomationReadResult.Failure(it)
            }
            val bytes = content.copyBytes()
            return@withLock try {
                NanoKvmAutomationReadResult.Success(NanoKvmAutostartEditorBuffer.importOwned(bytes))
            } catch (failureCause: Throwable) {
                bytes.fill(0)
                NanoKvmAutomationReadResult.Failure(
                    error(
                        NanoKvmAutomationOperation.AUTOSTART_READ,
                        NanoKvmAutomationError.Kind.INVALID_RESPONSE,
                    ),
                )
            }
        } finally {
            content.close()
        }
    }

    fun reviewAutostartCreate(
        catalog: NanoKvmAutomationAutostartCatalog,
        fileName: String,
        editor: NanoKvmAutostartEditorBuffer,
    ): NanoKvmAutomationReviewResult<NanoKvmAutostartWriteApproval> {
        validateAutostartCatalog(catalog, NanoKvmAutomationOperation.AUTOSTART_CREATE)?.let {
            return NanoKvmAutomationReviewResult.Rejected(it)
        }
        if (!isSafeAutostartBasename(fileName) || catalog.scripts.any { it.displayName == fileName }) {
            return NanoKvmAutomationReviewResult.Rejected(
                error(
                    NanoKvmAutomationOperation.AUTOSTART_CREATE,
                    NanoKvmAutomationError.Kind.INVALID_REQUEST,
                ),
            )
        }
        return createWriteApproval(catalog, null, fileName, editor, NanoKvmAutostartReviewKind.CREATE)
    }

    fun reviewAutostartUpdate(
        catalog: NanoKvmAutomationAutostartCatalog,
        script: NanoKvmAutomationAutostartScript,
        editor: NanoKvmAutostartEditorBuffer,
    ): NanoKvmAutomationReviewResult<NanoKvmAutostartWriteApproval> {
        validateAutostartHandle(catalog, script, NanoKvmAutomationOperation.AUTOSTART_UPDATE)?.let {
            return NanoKvmAutomationReviewResult.Rejected(it)
        }
        return createWriteApproval(
            catalog,
            script,
            script.displayName,
            editor,
            NanoKvmAutostartReviewKind.UPDATE,
        )
    }

    fun reviewAutostartDelete(
        catalog: NanoKvmAutomationAutostartCatalog,
        script: NanoKvmAutomationAutostartScript,
    ): NanoKvmAutomationReviewResult<NanoKvmAutostartDeleteApproval> {
        validateAutostartHandle(catalog, script, NanoKvmAutomationOperation.AUTOSTART_DELETE)?.let {
            return NanoKvmAutomationReviewResult.Rejected(it)
        }
        return NanoKvmAutomationReviewResult.Ready(
            NanoKvmAutostartDeleteApproval(
                owner = approvalOwner,
                binding = binding,
                lifecycleGeneration = currentLifecycle(),
                catalog = catalog,
                script = script,
            ),
        )
    }

    /** Clears a reviewed root-equivalent write which the user cancelled or abandoned. */
    fun discardAutostartWrite(approval: NanoKvmAutostartWriteApproval?) {
        if (approval == null) return
        synchronized(stateLock) { outstandingAutostartWrites.remove(approval) }
        approval.close()
    }

    suspend fun executeAutostartWrite(
        approval: NanoKvmAutostartWriteApproval?,
    ): NanoKvmAutomationCommandResult<NanoKvmAutomationAutostartReceipt> =
        operationMutex.withLock {
            val operation = when (approval?.kind) {
                NanoKvmAutostartReviewKind.CREATE -> NanoKvmAutomationOperation.AUTOSTART_CREATE
                NanoKvmAutostartReviewKind.UPDATE -> NanoKvmAutomationOperation.AUTOSTART_UPDATE
                null -> NanoKvmAutomationOperation.AUTOSTART_CREATE
            }
            if (approval?.consume(approvalOwner) != true) {
                approval?.close()
                return@withLock rejected(operation, NanoKvmAutomationError.Kind.CONFIRMATION_REQUIRED)
            }
            try {
                validateApprovalLifecycle(
                    approval.binding,
                    approval.lifecycleGeneration,
                    operation,
                )?.let { return@withLock NanoKvmAutomationCommandResult.Rejected(it) }
                val handleError = if (approval.kind == NanoKvmAutostartReviewKind.CREATE) {
                    validateAutostartCatalog(approval.catalog, operation)
                } else {
                    validateAutostartHandle(
                        approval.catalog,
                        requireNotNull(approval.script),
                        operation,
                    )
                }
                handleError?.let { return@withLock NanoKvmAutomationCommandResult.Rejected(it) }
                invalidateAutostartCatalog()
                val receipt = try {
                    when (approval.kind) {
                        NanoKvmAutostartReviewKind.CREATE -> port.createAutostartScript(
                            approval.catalog.portCatalog,
                            approval.targetDisplayName,
                            approval.write,
                        )
                        NanoKvmAutostartReviewKind.UPDATE -> port.updateAutostartScript(
                            approval.catalog.portCatalog,
                            requireNotNull(approval.script).portScript,
                            approval.write,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withLock dispatchedFailure(operation, error)
                }
                requireActive(operation)?.let {
                    return@withLock NanoKvmAutomationCommandResult.Indeterminate(it)
                }
                val expectedKind = when (approval.kind) {
                    NanoKvmAutostartReviewKind.CREATE ->
                        NanoKvmAutomationPortAutostartWriteKind.CREATE
                    NanoKvmAutostartReviewKind.UPDATE ->
                        NanoKvmAutomationPortAutostartWriteKind.UPDATE
                }
                if (
                    receipt.displayName != approval.targetDisplayName ||
                    receipt.byteCount != approval.byteCount ||
                    receipt.kind != expectedKind
                ) {
                    return@withLock NanoKvmAutomationCommandResult.Indeterminate(
                        error(operation, NanoKvmAutomationError.Kind.INVALID_RESPONSE),
                    )
                }
                NanoKvmAutomationCommandResult.Completed(
                    NanoKvmAutomationAutostartReceipt(
                        displayName = receipt.displayName,
                        byteCount = receipt.byteCount,
                        kind = approval.kind,
                    ),
                )
            } finally {
                discardAutostartWrite(approval)
            }
        }

    suspend fun executeAutostartDelete(
        approval: NanoKvmAutostartDeleteApproval?,
    ): NanoKvmAutomationCommandResult<NanoKvmAutomationMutationCompleted> =
        operationMutex.withLock {
            val operation = NanoKvmAutomationOperation.AUTOSTART_DELETE
            if (approval?.consume(approvalOwner) != true) {
                return@withLock rejected(operation, NanoKvmAutomationError.Kind.CONFIRMATION_REQUIRED)
            }
            validateApprovalLifecycle(
                approval.binding,
                approval.lifecycleGeneration,
                operation,
            )?.let { return@withLock NanoKvmAutomationCommandResult.Rejected(it) }
            validateAutostartHandle(approval.catalog, approval.script, operation)?.let {
                return@withLock NanoKvmAutomationCommandResult.Rejected(it)
            }
            invalidateAutostartCatalog()
            dispatch(operation) {
                port.deleteAutostartScript(approval.catalog.portCatalog, approval.script.portScript)
                NanoKvmAutomationMutationCompleted
            }
        }

    override fun close() = synchronized(stateLock) {
        closed = true
        foreground = false
        lifecycleGeneration++
        invalidateAllLocked()
    }

    private suspend fun runHidShortcutOnce(
        approval: NanoKvmHidShortcutActionApproval,
    ): NanoKvmAutomationCommandResult<NanoKvmAutomationHidRunReceipt> {
        val operation = NanoKvmAutomationOperation.HID_RUN
        val initialRelease = try {
            port.releaseAllInput()
        } catch (error: Throwable) {
            return dispatchedFailure(operation, error)
        }
        if (!initialRelease) {
            return NanoKvmAutomationCommandResult.Rejected(
                error(operation, NanoKvmAutomationError.Kind.NOT_CONNECTED),
            )
        }

        var finalRelease = false
        val result = try {
            port.runHidShortcut(approval.catalog.portCatalog, approval.shortcut.portShortcut)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return dispatchedFailure(operation, error)
        } finally {
            finalRelease = runCatching { port.releaseAllInput() }.getOrDefault(false)
        }
        requireActive(operation)?.let { return NanoKvmAutomationCommandResult.Indeterminate(it) }
        return when (result) {
            is NanoKvmAutomationPortHidRunResult.Completed -> if (finalRelease) {
                NanoKvmAutomationCommandResult.Completed(
                    NanoKvmAutomationHidRunReceipt(result.reportsSent),
                )
            } else {
                NanoKvmAutomationCommandResult.Indeterminate(
                    error(operation, NanoKvmAutomationError.Kind.CONNECTION),
                )
            }
            is NanoKvmAutomationPortHidRunResult.ConnectionLost,
            NanoKvmAutomationPortHidRunResult.Rejected ->
                NanoKvmAutomationCommandResult.Indeterminate(
                    error(operation, NanoKvmAutomationError.Kind.CONNECTION),
                )
        }
    }

    private fun createWriteApproval(
        catalog: NanoKvmAutomationAutostartCatalog,
        script: NanoKvmAutomationAutostartScript?,
        fileName: String,
        editor: NanoKvmAutostartEditorBuffer,
        kind: NanoKvmAutostartReviewKind,
    ): NanoKvmAutomationReviewResult<NanoKvmAutostartWriteApproval> {
        val operation = if (kind == NanoKvmAutostartReviewKind.CREATE) {
            NanoKvmAutomationOperation.AUTOSTART_CREATE
        } else {
            NanoKvmAutomationOperation.AUTOSTART_UPDATE
        }
        val write = try {
            editor.consumeForWrite()
        } catch (failureCause: Throwable) {
            return NanoKvmAutomationReviewResult.Rejected(
                error(operation, NanoKvmAutomationError.Kind.INVALID_REQUEST),
            )
        }
        val approval = NanoKvmAutostartWriteApproval(
                owner = approvalOwner,
                binding = binding,
                lifecycleGeneration = currentLifecycle(),
                catalog = catalog,
                script = script,
                write = write,
                kind = kind,
                targetDisplayName = fileName,
            )
        synchronized(stateLock) {
            if (closed || !foreground || lifecycleGeneration != approval.lifecycleGeneration) {
                approval.close()
                return NanoKvmAutomationReviewResult.Rejected(
                    error(operation, NanoKvmAutomationError.Kind.NOT_FOREGROUND),
                )
            }
            outstandingAutostartWrites += approval
        }
        return NanoKvmAutomationReviewResult.Ready(approval)
    }

    private fun validateHidCatalog(
        catalog: NanoKvmAutomationHidCatalog,
        operation: NanoKvmAutomationOperation,
    ): NanoKvmAutomationError? {
        requireActive(operation)?.let { return it }
        if (
            catalog.binding != binding ||
            catalog.lifecycleGeneration != currentLifecycle() ||
            synchronized(stateLock) { latestHidCatalog !== catalog }
        ) {
            return error(operation, NanoKvmAutomationError.Kind.FOREIGN_OR_STALE_STATE)
        }
        return null
    }

    private fun validateHidHandle(
        catalog: NanoKvmAutomationHidCatalog,
        shortcut: NanoKvmAutomationHidShortcut,
        operation: NanoKvmAutomationOperation,
    ): NanoKvmAutomationError? {
        validateHidCatalog(catalog, operation)?.let { return it }
        if (
            shortcut.binding != binding ||
            catalog.shortcuts.none { it === shortcut } ||
            catalog.portCatalog.shortcuts.none { it === shortcut.portShortcut }
        ) {
            return error(operation, NanoKvmAutomationError.Kind.FOREIGN_OR_STALE_STATE)
        }
        return null
    }

    private fun validateAutostartCatalog(
        catalog: NanoKvmAutomationAutostartCatalog,
        operation: NanoKvmAutomationOperation,
    ): NanoKvmAutomationError? {
        requireActive(operation)?.let { return it }
        if (
            catalog.binding != binding ||
            catalog.lifecycleGeneration != currentLifecycle() ||
            synchronized(stateLock) { latestAutostartCatalog !== catalog }
        ) {
            return error(operation, NanoKvmAutomationError.Kind.FOREIGN_OR_STALE_STATE)
        }
        return null
    }

    private fun validateAutostartHandle(
        catalog: NanoKvmAutomationAutostartCatalog,
        script: NanoKvmAutomationAutostartScript,
        operation: NanoKvmAutomationOperation,
    ): NanoKvmAutomationError? {
        validateAutostartCatalog(catalog, operation)?.let { return it }
        if (
            script.binding != binding ||
            catalog.scripts.none { it === script } ||
            catalog.portCatalog.scripts.none { it === script.portScript }
        ) {
            return error(operation, NanoKvmAutomationError.Kind.FOREIGN_OR_STALE_STATE)
        }
        return null
    }

    private fun validateApprovalLifecycle(
        approvedBinding: NanoKvmSessionBinding,
        approvedLifecycle: Long,
        operation: NanoKvmAutomationOperation,
    ): NanoKvmAutomationError? {
        requireActive(operation)?.let { return it }
        return if (approvedBinding == binding && approvedLifecycle == currentLifecycle()) {
            null
        } else {
            error(operation, NanoKvmAutomationError.Kind.CONFIRMATION_REQUIRED)
        }
    }

    private fun requireSameActive(
        lifecycle: Long,
        operation: NanoKvmAutomationOperation,
    ): NanoKvmAutomationError? {
        requireActive(operation)?.let { return it }
        return if (lifecycle == currentLifecycle()) null else {
            error(operation, NanoKvmAutomationError.Kind.SESSION_CHANGED)
        }
    }

    private fun requireActive(operation: NanoKvmAutomationOperation): NanoKvmAutomationError? {
        val state = synchronized(stateLock) { Triple(closed, foreground, lifecycleGeneration) }
        if (state.first || currentBinding() != binding) {
            synchronized(stateLock) { invalidateAllLocked() }
            return error(operation, NanoKvmAutomationError.Kind.SESSION_CHANGED)
        }
        if (!state.second) return error(operation, NanoKvmAutomationError.Kind.NOT_FOREGROUND)
        return null
    }

    private fun currentLifecycle(): Long = synchronized(stateLock) { lifecycleGeneration }

    private fun invalidateHidCatalog() = synchronized(stateLock) { latestHidCatalog = null }
    private fun invalidateAutostartCatalog() = synchronized(stateLock) {
        latestAutostartCatalog = null
    }

    private fun invalidateAllLocked() {
        latestHidCatalog = null
        latestLeaderKey = null
        latestAutostartCatalog = null
        outstandingAutostartWrites.forEach(NanoKvmAutostartWriteApproval::close)
        outstandingAutostartWrites.clear()
    }

    private suspend fun <Value> dispatch(
        operation: NanoKvmAutomationOperation,
        block: suspend () -> Value,
    ): NanoKvmAutomationCommandResult<Value> = try {
        val value = block()
        requireActive(operation)?.let {
            NanoKvmAutomationCommandResult.Indeterminate(it)
        } ?: NanoKvmAutomationCommandResult.Completed(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        dispatchedFailure(operation, error)
    }

    private fun dispatchedFailure(
        operation: NanoKvmAutomationOperation,
        throwable: Throwable,
    ): NanoKvmAutomationCommandResult.Indeterminate =
        NanoKvmAutomationCommandResult.Indeterminate(classifyError(operation, throwable))

    private fun failure(
        operation: NanoKvmAutomationOperation,
        throwable: Throwable,
    ): NanoKvmAutomationReadResult.Failure =
        NanoKvmAutomationReadResult.Failure(classifyError(operation, throwable))

    private fun classifyError(
        operation: NanoKvmAutomationOperation,
        throwable: Throwable,
    ): NanoKvmAutomationError = throwable.toAutomationError(operation).also { safeError ->
        if (
            safeError.kind == NanoKvmAutomationError.Kind.AUTHENTICATION_EXPIRED &&
            currentBinding() == binding
        ) {
            onAuthenticationExpired()
        }
    }

    private fun <Value> rejected(
        operation: NanoKvmAutomationOperation,
        kind: NanoKvmAutomationError.Kind,
    ): NanoKvmAutomationCommandResult<Value> =
        NanoKvmAutomationCommandResult.Rejected(error(operation, kind))
}

private val NanoKvmHidShortcutAction.operation: NanoKvmAutomationOperation
    get() = when (this) {
        NanoKvmHidShortcutAction.RUN -> NanoKvmAutomationOperation.HID_RUN
        NanoKvmHidShortcutAction.DELETE -> NanoKvmAutomationOperation.HID_DELETE
    }

private fun error(
    operation: NanoKvmAutomationOperation,
    kind: NanoKvmAutomationError.Kind,
): NanoKvmAutomationError = NanoKvmAutomationError(operation, kind)

private fun Throwable.toAutomationError(
    operation: NanoKvmAutomationOperation,
): NanoKvmAutomationError = error(
    operation,
    when (this) {
        is IllegalArgumentException -> NanoKvmAutomationError.Kind.INVALID_REQUEST
        is AuthenticationExpiredException -> NanoKvmAutomationError.Kind.AUTHENTICATION_EXPIRED
        is InvalidApiResponseException -> NanoKvmAutomationError.Kind.INVALID_RESPONSE
        is NanoKvmAutostartOperationException -> when (failure) {
            NanoKvmAutostartFailure.InvalidResponse -> NanoKvmAutomationError.Kind.INVALID_RESPONSE
            NanoKvmAutostartFailure.Transport -> NanoKvmAutomationError.Kind.CONNECTION
            is NanoKvmAutostartFailure.Api,
            is NanoKvmAutostartFailure.Http -> NanoKvmAutomationError.Kind.SERVER_REJECTED
        }
        is ApiResponseException -> NanoKvmAutomationError.Kind.SERVER_REJECTED
        is HttpResponseException -> if (statusCode.isUnsupportedOptionalEndpoint()) {
            NanoKvmAutomationError.Kind.UNSUPPORTED
        } else {
            NanoKvmAutomationError.Kind.CONNECTION
        }
        is IOException -> NanoKvmAutomationError.Kind.CONNECTION
        else -> NanoKvmAutomationError.Kind.UNEXPECTED
    },
)

private fun Int.isUnsupportedOptionalEndpoint(): Boolean = this == 404 || this == 405 || this == 501

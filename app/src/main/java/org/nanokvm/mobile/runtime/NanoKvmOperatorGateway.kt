package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nanokvm.protocol.ApiResponseException
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.InvalidApiResponseException
import org.nanokvm.protocol.NanoKvmScriptOperationException
import org.nanokvm.protocol.NanoKvmScriptRunMode
import org.nanokvm.protocol.NanoKvmSerialConfiguration
import org.nanokvm.protocol.NanoKvmTerminalConnectionState
import org.nanokvm.protocol.NanoKvmTerminalEvent
import org.nanokvm.protocol.NanoKvmTerminalSize

/** Operation label safe to retain in diagnostics. */
internal enum class NanoKvmOperatorOperation {
    TERMINAL_ENTRY,
    TERMINAL_SESSION,
    TERMINAL_INPUT,
    TERMINAL_RESIZE,
    SERIAL_START,
    SERIAL_EXIT,
    SCRIPT_LIST,
    SCRIPT_UPLOAD,
    SCRIPT_RUN,
    SCRIPT_DELETE,
}

/** Error information which never retains commands, terminal output, paths, or server messages. */
internal data class NanoKvmOperatorError(
    val operation: NanoKvmOperatorOperation,
    val kind: Kind,
) {
    enum class Kind {
        SESSION_CHANGED,
        NOT_FOREGROUND,
        ELEVATED_APPROVAL_REQUIRED,
        ALREADY_ACTIVE,
        NOT_CONNECTED,
        FOREIGN_OR_STALE_STATE,
        INVALID_REQUEST,
        AUTHENTICATION_EXPIRED,
        CONNECTION,
        SERVER_REJECTED,
        INVALID_RESPONSE,
        UNEXPECTED,
    }
}

internal sealed interface NanoKvmOperatorActionResult {
    data object Dispatched : NanoKvmOperatorActionResult
    data class Rejected(val error: NanoKvmOperatorError) : NanoKvmOperatorActionResult
}

/**
 * One-use evidence that the caller presented and confirmed the elevated root-shell warning.
 * It is bound to an owner, destination, and foreground lifecycle generation.
 */
internal class NanoKvmElevatedTerminalEntryApproval internal constructor(
    private val ownerIdentity: Any,
    private val binding: NanoKvmSessionBinding,
    private val lifecycleGeneration: Long,
) {
    private var consumed = false

    internal fun consume(
        expectedOwner: Any,
        expectedBinding: NanoKvmSessionBinding,
        expectedLifecycleGeneration: Long,
    ): Boolean = synchronized(this) {
        if (
            consumed ||
            ownerIdentity !== expectedOwner ||
            binding != expectedBinding ||
            lifecycleGeneration != expectedLifecycleGeneration
        ) {
            return@synchronized false
        }
        consumed = true
        true
    }

    override fun toString(): String =
        "NanoKvmElevatedTerminalEntryApproval(destination=<redacted>, consumed=$consumed)"
}

/** UI-safe terminal state. It contains neither protocol causes nor peer-supplied close reasons. */
internal sealed interface NanoKvmOperatorTerminalState {
    data object Inactive : NanoKvmOperatorTerminalState
    data object Connecting : NanoKvmOperatorTerminalState
    data class Connected(val serialActive: Boolean) : NanoKvmOperatorTerminalState
    data object Closing : NanoKvmOperatorTerminalState
    data class Failed(val error: NanoKvmOperatorError) : NanoKvmOperatorTerminalState
}

/** Ephemeral terminal output. The owner exposes it through a replay-free flow, never saved state. */
internal class NanoKvmOperatorTerminalOutput internal constructor(bytes: ByteArray) {
    private val retainedBytes = bytes.copyOf()

    init {
        require(retainedBytes.size <= 64 * 1024) { "Terminal output chunk exceeds the app limit" }
    }

    val size: Int
        get() = retainedBytes.size

    fun copyBytes(): ByteArray = retainedBytes.copyOf()

    override fun toString(): String = "NanoKvmOperatorTerminalOutput(size=$size, data=<redacted>)"
}

/**
 * Foreground-only owner of one explicitly approved root/serial terminal connection.
 *
 * There is no reconnect loop, input queue, command history, replay, or restorable state. Going to
 * background closes the socket and invalidates every unused approval. Returning to foreground
 * requires a new explicit approval and a new [enter] call.
 */
internal class NanoKvmOperatorTerminalOwner internal constructor(
    private val terminalFactory: () -> NanoKvmOperatorTerminalPort,
    val binding: NanoKvmSessionBinding,
    private val currentBinding: () -> NanoKvmSessionBinding?,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val lock = Any()
    private val ownerIdentity = Any()
    private val mutableState = MutableStateFlow<NanoKvmOperatorTerminalState>(
        NanoKvmOperatorTerminalState.Inactive,
    )
    private val mutableOutput = MutableSharedFlow<NanoKvmOperatorTerminalOutput>(
        replay = 0,
        extraBufferCapacity = MAX_RETAINED_OUTPUT_CHUNKS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var foreground = false
    private var lifecycleGeneration = 0L
    private var disposed = false
    private var active: ActiveTerminal? = null

    val state: StateFlow<NanoKvmOperatorTerminalState> = mutableState.asStateFlow()
    val output: SharedFlow<NanoKvmOperatorTerminalOutput> = mutableOutput.asSharedFlow()

    fun onForeground() {
        synchronized(lock) {
            if (disposed || foreground) return
            foreground = true
            lifecycleGeneration++
        }
    }

    fun onBackground() {
        synchronized(lock) {
            if (!foreground && active == null) return
            foreground = false
            lifecycleGeneration++
        }
        terminateActive(finalState = NanoKvmOperatorTerminalState.Inactive)
    }

    /**
     * Call only after the user explicitly confirms that this opens an unrestricted root shell.
     * The approval expires on background, session replacement, first use, or owner disposal.
     */
    fun recordExplicitElevatedEntryApproval(): NanoKvmElevatedTerminalEntryApproval? {
        if (!hasCurrentBinding()) return null
        return synchronized(lock) {
            if (disposed || !foreground || active != null) return@synchronized null
            NanoKvmElevatedTerminalEntryApproval(
                ownerIdentity = ownerIdentity,
                binding = binding,
                lifecycleGeneration = lifecycleGeneration,
            )
        }
    }

    /** Starts one asynchronous handshake. The approval is consumed even if the handshake fails. */
    fun enter(approval: NanoKvmElevatedTerminalEntryApproval?): NanoKvmOperatorActionResult {
        if (!hasCurrentBinding()) {
            return rejected(NanoKvmOperatorOperation.TERMINAL_ENTRY, NanoKvmOperatorError.Kind.SESSION_CHANGED)
        }

        val lifecycle = synchronized(lock) {
            when {
                disposed || !foreground -> return rejected(
                    NanoKvmOperatorOperation.TERMINAL_ENTRY,
                    NanoKvmOperatorError.Kind.NOT_FOREGROUND,
                )
                active != null -> return rejected(
                    NanoKvmOperatorOperation.TERMINAL_ENTRY,
                    NanoKvmOperatorError.Kind.ALREADY_ACTIVE,
                )
                else -> lifecycleGeneration
            }
        }
        if (approval?.consume(ownerIdentity, binding, lifecycle) != true) {
            return rejected(
                NanoKvmOperatorOperation.TERMINAL_ENTRY,
                NanoKvmOperatorError.Kind.ELEVATED_APPROVAL_REQUIRED,
            )
        }

        val terminal = try {
            terminalFactory()
        } catch (error: Throwable) {
            return NanoKvmOperatorActionResult.Rejected(
                error.toOperatorError(NanoKvmOperatorOperation.TERMINAL_ENTRY),
            )
        }
        if (!hasCurrentBinding()) {
            runCatching { terminal.close() }
            return rejected(NanoKvmOperatorOperation.TERMINAL_ENTRY, NanoKvmOperatorError.Kind.SESSION_CHANGED)
        }

        val connection = ActiveTerminal(terminal)
        val installed = synchronized(lock) {
            if (disposed || !foreground || lifecycleGeneration != lifecycle || active != null) {
                false
            } else {
                active = connection
                mutableState.value = NanoKvmOperatorTerminalState.Connecting
                true
            }
        }
        if (!installed) {
            runCatching { terminal.close() }
            return rejected(NanoKvmOperatorOperation.TERMINAL_ENTRY, NanoKvmOperatorError.Kind.NOT_FOREGROUND)
        }

        val stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            terminal.state.collect { handleState(connection, it) }
        }
        val eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            terminal.events.collect { handleEvent(connection, it) }
        }
        synchronized(lock) {
            if (active === connection) {
                connection.stateJob = stateJob
                connection.eventJob = eventJob
                connection.handshakeStarted = true
            } else {
                stateJob.cancel()
                eventJob.cancel()
            }
        }
        if (!isActive(connection) || !hasCurrentBinding()) {
            terminateActive(
                expected = connection,
                finalState = NanoKvmOperatorTerminalState.Failed(
                    operatorError(
                        NanoKvmOperatorOperation.TERMINAL_ENTRY,
                        NanoKvmOperatorError.Kind.SESSION_CHANGED,
                    ),
                ),
            )
            return rejected(NanoKvmOperatorOperation.TERMINAL_ENTRY, NanoKvmOperatorError.Kind.SESSION_CHANGED)
        }

        val accepted = try {
            terminal.connect()
        } catch (error: Throwable) {
            terminateActive(
                expected = connection,
                finalState = NanoKvmOperatorTerminalState.Failed(
                    error.toOperatorError(NanoKvmOperatorOperation.TERMINAL_ENTRY),
                ),
            )
            return NanoKvmOperatorActionResult.Rejected(
                error.toOperatorError(NanoKvmOperatorOperation.TERMINAL_ENTRY),
            )
        }
        if (!accepted) {
            terminateActive(
                expected = connection,
                finalState = NanoKvmOperatorTerminalState.Failed(
                    operatorError(
                        NanoKvmOperatorOperation.TERMINAL_ENTRY,
                        NanoKvmOperatorError.Kind.CONNECTION,
                    ),
                ),
            )
            return rejected(NanoKvmOperatorOperation.TERMINAL_ENTRY, NanoKvmOperatorError.Kind.CONNECTION)
        }
        return NanoKvmOperatorActionResult.Dispatched
    }

    /** Sends exactly one text frame. The owner does not retain or replay [text]. */
    fun sendInput(text: String): NanoKvmOperatorActionResult = dispatchConnected(
        operation = NanoKvmOperatorOperation.TERMINAL_INPUT,
    ) { it.sendInput(text) }

    /** Sends exactly one binary resize frame. */
    fun resize(size: NanoKvmTerminalSize): NanoKvmOperatorActionResult = dispatchConnected(
        operation = NanoKvmOperatorOperation.TERMINAL_RESIZE,
    ) { it.resize(size) }

    /** Starts serial mode from typed, allowlisted protocol fields; no shell string is accepted. */
    fun startSerial(configuration: NanoKvmSerialConfiguration): NanoKvmOperatorActionResult {
        if (!hasCurrentBinding()) {
            terminateForSessionChange(NanoKvmOperatorOperation.SERIAL_START)
            return rejected(NanoKvmOperatorOperation.SERIAL_START, NanoKvmOperatorError.Kind.SESSION_CHANGED)
        }
        val connection = synchronized(lock) {
            val candidate = active
            if (
                disposed ||
                !foreground ||
                candidate == null ||
                mutableState.value !is NanoKvmOperatorTerminalState.Connected
            ) {
                return rejected(NanoKvmOperatorOperation.SERIAL_START, NanoKvmOperatorError.Kind.NOT_CONNECTED)
            }
            if (candidate.serialActive) {
                return rejected(NanoKvmOperatorOperation.SERIAL_START, NanoKvmOperatorError.Kind.ALREADY_ACTIVE)
            }
            candidate
        }
        val accepted = try {
            connection.port.startSerial(configuration)
        } catch (error: Throwable) {
            return NanoKvmOperatorActionResult.Rejected(
                error.toOperatorError(NanoKvmOperatorOperation.SERIAL_START),
            )
        }
        if (!accepted) {
            return rejected(NanoKvmOperatorOperation.SERIAL_START, NanoKvmOperatorError.Kind.NOT_CONNECTED)
        }
        synchronized(lock) {
            if (active === connection) {
                connection.serialActive = true
                mutableState.value = NanoKvmOperatorTerminalState.Connected(serialActive = true)
            }
        }
        return NanoKvmOperatorActionResult.Dispatched
    }

    /**
     * Sends picocom's exit sequence once through the typed protocol primitive, then disconnects.
     * Cancellation closes this owner; a second exit is never dispatched.
     */
    suspend fun exitSerial(): NanoKvmOperatorActionResult {
        if (!hasCurrentBinding()) {
            terminateForSessionChange(NanoKvmOperatorOperation.SERIAL_EXIT)
            return rejected(NanoKvmOperatorOperation.SERIAL_EXIT, NanoKvmOperatorError.Kind.SESSION_CHANGED)
        }
        val connection = synchronized(lock) {
            val candidate = active
            if (
                disposed ||
                !foreground ||
                candidate == null ||
                !candidate.serialActive ||
                mutableState.value !is NanoKvmOperatorTerminalState.Connected
            ) {
                return rejected(NanoKvmOperatorOperation.SERIAL_EXIT, NanoKvmOperatorError.Kind.NOT_CONNECTED)
            }
            if (candidate.serialExitRequested) {
                return rejected(NanoKvmOperatorOperation.SERIAL_EXIT, NanoKvmOperatorError.Kind.ALREADY_ACTIVE)
            }
            candidate.serialExitRequested = true
            mutableState.value = NanoKvmOperatorTerminalState.Closing
            candidate
        }

        val accepted = try {
            connection.port.exitSerialAndDisconnect()
        } catch (error: CancellationException) {
            terminateActive(expected = connection, finalState = NanoKvmOperatorTerminalState.Inactive)
            throw error
        } catch (error: Throwable) {
            val safeError = error.toOperatorError(NanoKvmOperatorOperation.SERIAL_EXIT)
            terminateActive(
                expected = connection,
                finalState = NanoKvmOperatorTerminalState.Failed(safeError),
            )
            return NanoKvmOperatorActionResult.Rejected(safeError)
        }
        terminateActive(expected = connection, finalState = NanoKvmOperatorTerminalState.Inactive)
        return if (accepted) {
            NanoKvmOperatorActionResult.Dispatched
        } else {
            rejected(NanoKvmOperatorOperation.SERIAL_EXIT, NanoKvmOperatorError.Kind.NOT_CONNECTED)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            foreground = false
            lifecycleGeneration++
        }
        terminateActive(finalState = NanoKvmOperatorTerminalState.Inactive)
    }

    private fun dispatchConnected(
        operation: NanoKvmOperatorOperation,
        dispatch: (NanoKvmOperatorTerminalPort) -> Boolean,
    ): NanoKvmOperatorActionResult {
        if (!hasCurrentBinding()) {
            terminateForSessionChange(operation)
            return rejected(operation, NanoKvmOperatorError.Kind.SESSION_CHANGED)
        }
        val connection = synchronized(lock) {
            val candidate = active
            if (
                disposed ||
                !foreground ||
                candidate == null ||
                mutableState.value !is NanoKvmOperatorTerminalState.Connected
            ) {
                return rejected(operation, NanoKvmOperatorError.Kind.NOT_CONNECTED)
            }
            candidate
        }
        val accepted = try {
            dispatch(connection.port)
        } catch (error: Throwable) {
            return NanoKvmOperatorActionResult.Rejected(error.toOperatorError(operation))
        }
        return if (accepted) {
            NanoKvmOperatorActionResult.Dispatched
        } else {
            rejected(operation, NanoKvmOperatorError.Kind.NOT_CONNECTED)
        }
    }

    private fun handleState(
        connection: ActiveTerminal,
        state: NanoKvmTerminalConnectionState,
    ) {
        if (!isActive(connection)) return
        if (!hasCurrentBinding()) {
            terminateForSessionChange(NanoKvmOperatorOperation.TERMINAL_SESSION, connection)
            return
        }
        when (state) {
            NanoKvmTerminalConnectionState.Disconnected -> {
                val started = synchronized(lock) {
                    active === connection && connection.handshakeStarted
                }
                if (started) {
                    terminateActive(
                        expected = connection,
                        finalState = NanoKvmOperatorTerminalState.Inactive,
                    )
                }
            }
            is NanoKvmTerminalConnectionState.Connecting -> {
                if (acceptGeneration(connection, state.generation)) {
                    mutableState.value = NanoKvmOperatorTerminalState.Connecting
                }
            }
            is NanoKvmTerminalConnectionState.Connected -> {
                if (acceptGeneration(connection, state.generation)) {
                    val serial = synchronized(lock) {
                        active === connection && connection.serialActive
                    }
                    mutableState.value = NanoKvmOperatorTerminalState.Connected(serial)
                }
            }
            is NanoKvmTerminalConnectionState.Closing -> {
                if (acceptGeneration(connection, state.generation)) {
                    mutableState.value = NanoKvmOperatorTerminalState.Closing
                }
            }
            is NanoKvmTerminalConnectionState.Failed -> {
                if (acceptGeneration(connection, state.generation)) {
                    terminateActive(
                        expected = connection,
                        finalState = NanoKvmOperatorTerminalState.Failed(
                            state.cause.toOperatorError(NanoKvmOperatorOperation.TERMINAL_SESSION),
                        ),
                    )
                }
            }
        }
    }

    private fun handleEvent(connection: ActiveTerminal, event: NanoKvmTerminalEvent) {
        if (!isAcceptedEvent(connection, event.generation)) return
        if (!hasCurrentBinding()) {
            terminateForSessionChange(NanoKvmOperatorOperation.TERMINAL_SESSION, connection)
            return
        }
        when (event) {
            is NanoKvmTerminalEvent.Output -> {
                if (event.size > MAX_TERMINAL_OUTPUT_CHUNK_BYTES) {
                    terminateActive(
                        expected = connection,
                        finalState = NanoKvmOperatorTerminalState.Failed(
                            operatorError(
                                NanoKvmOperatorOperation.TERMINAL_SESSION,
                                NanoKvmOperatorError.Kind.INVALID_RESPONSE,
                            ),
                        ),
                    )
                } else {
                    mutableOutput.tryEmit(NanoKvmOperatorTerminalOutput(event.copyBytes()))
                }
            }
            is NanoKvmTerminalEvent.ProtocolViolation -> terminateActive(
                expected = connection,
                finalState = NanoKvmOperatorTerminalState.Failed(
                    operatorError(
                        NanoKvmOperatorOperation.TERMINAL_SESSION,
                        NanoKvmOperatorError.Kind.INVALID_RESPONSE,
                    ),
                ),
            )
            is NanoKvmTerminalEvent.PeerClosing ->
                mutableState.value = NanoKvmOperatorTerminalState.Closing
            is NanoKvmTerminalEvent.Closed -> terminateActive(
                expected = connection,
                finalState = NanoKvmOperatorTerminalState.Inactive,
            )
            is NanoKvmTerminalEvent.Failure -> terminateActive(
                expected = connection,
                finalState = NanoKvmOperatorTerminalState.Failed(
                    event.cause.toOperatorError(NanoKvmOperatorOperation.TERMINAL_SESSION),
                ),
            )
        }
    }

    private fun acceptGeneration(connection: ActiveTerminal, generation: Long): Boolean {
        val accepted = synchronized(lock) {
            if (active !== connection) return@synchronized false
            val existing = connection.protocolGeneration
            if (existing == null) {
                connection.protocolGeneration = generation
                true
            } else {
                existing == generation
            }
        }
        if (!accepted && isActive(connection)) {
            terminateActive(
                expected = connection,
                finalState = NanoKvmOperatorTerminalState.Failed(
                    operatorError(
                        NanoKvmOperatorOperation.TERMINAL_SESSION,
                        NanoKvmOperatorError.Kind.INVALID_RESPONSE,
                    ),
                ),
            )
        }
        return accepted
    }

    private fun isAcceptedEvent(connection: ActiveTerminal, generation: Long): Boolean =
        synchronized(lock) {
            active === connection && connection.protocolGeneration == generation
        }

    private fun isActive(connection: ActiveTerminal): Boolean = synchronized(lock) {
        active === connection && !disposed && foreground
    }

    private fun hasCurrentBinding(): Boolean = currentBinding() == binding

    private fun terminateForSessionChange(
        operation: NanoKvmOperatorOperation,
        expected: ActiveTerminal? = null,
    ) {
        terminateActive(
            expected = expected,
            finalState = NanoKvmOperatorTerminalState.Failed(
                operatorError(operation, NanoKvmOperatorError.Kind.SESSION_CHANGED),
            ),
        )
    }

    private fun terminateActive(
        expected: ActiveTerminal? = null,
        finalState: NanoKvmOperatorTerminalState,
    ) {
        val ended = synchronized(lock) {
            val candidate = active
            if (expected != null && candidate !== expected) return@synchronized null
            active = null
            mutableState.value = finalState
            candidate
        }
        ended?.stateJob?.cancel()
        ended?.eventJob?.cancel()
        ended?.let { runCatching { it.port.close() } }
    }

    private class ActiveTerminal(val port: NanoKvmOperatorTerminalPort) {
        var stateJob: Job? = null
        var eventJob: Job? = null
        var handshakeStarted = false
        var protocolGeneration: Long? = null
        var serialActive = false
        var serialExitRequested = false
    }

    private companion object {
        const val MAX_RETAINED_OUTPUT_CHUNKS = 8
        const val MAX_TERMINAL_OUTPUT_CHUNK_BYTES = 64 * 1024
    }
}

internal enum class NanoKvmScriptRunWarning {
    /** NanoKVM 2.4.3 does not terminate the process when the foreground HTTP request is cancelled. */
    FOREGROUND_REQUEST_CANCELLATION_DOES_NOT_STOP_PROCESS,

    /** NanoKVM 2.4.3 returns no PID, status, output stream, or cancellation handle. */
    BACKGROUND_HAS_NO_STATUS_OR_CANCELLATION,
}

internal fun NanoKvmScriptRunMode.operatorWarnings(): Set<NanoKvmScriptRunWarning> = when (this) {
    NanoKvmScriptRunMode.FOREGROUND -> setOf(
        NanoKvmScriptRunWarning.FOREGROUND_REQUEST_CANCELLATION_DOES_NOT_STOP_PROCESS,
    )
    NanoKvmScriptRunMode.BACKGROUND -> setOf(
        NanoKvmScriptRunWarning.BACKGROUND_HAS_NO_STATUS_OR_CANCELLATION,
    )
}

/** UI-safe handle bound to one exact gateway and catalog snapshot. */
internal class NanoKvmOperatorScript internal constructor(
    val displayName: String,
    internal val binding: NanoKvmSessionBinding,
    internal val portScript: NanoKvmOperatorPortScript,
) {
    override fun toString(): String = "NanoKvmOperatorScript(displayName=<redacted>)"
}

internal class NanoKvmOperatorScriptCatalog internal constructor(
    scripts: List<NanoKvmOperatorScript>,
    internal val binding: NanoKvmSessionBinding,
    internal val portCatalog: NanoKvmOperatorPortScriptCatalog,
) {
    val scripts: List<NanoKvmOperatorScript> = scripts.toList()

    init {
        require(scripts.all { it.binding == binding }) {
            "Every script must match the catalog destination"
        }
    }

    override fun toString(): String = "NanoKvmOperatorScriptCatalog(scripts=${scripts.size})"
}

internal sealed interface NanoKvmOperatorScriptReadResult {
    data class Success(val catalog: NanoKvmOperatorScriptCatalog) : NanoKvmOperatorScriptReadResult
    data class Failure(val error: NanoKvmOperatorError) : NanoKvmOperatorScriptReadResult
}

internal enum class NanoKvmScriptDeleteObservation {
    ABSENT,
    PRESENT,
}

/**
 * One invocation produces one terminal result. There is intentionally no retry affordance.
 * [Reconciled] performs a read after an ambiguous delete; it never repeats the mutation.
 */
internal sealed interface NanoKvmOperatorScriptCommandResult<out Value> {
    val warnings: Set<NanoKvmScriptRunWarning>

    data class Completed<Value>(
        val value: Value,
        override val warnings: Set<NanoKvmScriptRunWarning> = emptySet(),
    ) : NanoKvmOperatorScriptCommandResult<Value>

    data class Reconciled(
        val catalog: NanoKvmOperatorScriptCatalog,
        val observation: NanoKvmScriptDeleteObservation,
        val dispatchError: NanoKvmOperatorError,
        override val warnings: Set<NanoKvmScriptRunWarning> = emptySet(),
    ) : NanoKvmOperatorScriptCommandResult<Nothing>

    data class Indeterminate(
        val dispatchError: NanoKvmOperatorError,
        val refreshedCatalog: NanoKvmOperatorScriptCatalog? = null,
        val refreshError: NanoKvmOperatorError? = null,
        override val warnings: Set<NanoKvmScriptRunWarning> = emptySet(),
    ) : NanoKvmOperatorScriptCommandResult<Nothing>

    data class Rejected(
        val error: NanoKvmOperatorError,
        override val warnings: Set<NanoKvmScriptRunWarning> = emptySet(),
    ) : NanoKvmOperatorScriptCommandResult<Nothing>
}

internal class NanoKvmOperatorScriptUploadReceipt internal constructor(
    val displayName: String,
    val byteCount: Int,
) {
    override fun toString(): String =
        "NanoKvmOperatorScriptUploadReceipt(displayName=<redacted>, byteCount=$byteCount)"
}

internal class NanoKvmOperatorScriptOutput internal constructor(content: String) {
    val content: String = content
    val utf8ByteCount: Int = content.encodeToByteArray().size

    init {
        require(content.hasBoundedUtf8Length(256 * 1024)) {
            "Script output exceeds the app limit"
        }
    }

    override fun toString(): String =
        "NanoKvmOperatorScriptOutput(utf8ByteCount=$utf8ByteCount, content=<redacted>)"
}

internal data class NanoKvmOperatorScriptExecution(
    val mode: NanoKvmScriptRunMode,
    val output: NanoKvmOperatorScriptOutput,
    val warnings: Set<NanoKvmScriptRunWarning>,
)

internal data object NanoKvmOperatorScriptDeleted

/**
 * Session-bound root-terminal and script domain gateway.
 *
 * Script work is serialized. Upload, run, and delete invalidate the latest UI catalog before
 * their single dispatch, preventing double-tap/retry reuse of the same opaque handle.
 */
internal class NanoKvmOperatorGateway internal constructor(
    private val port: NanoKvmOperatorPort,
    val binding: NanoKvmSessionBinding,
    private val currentBinding: () -> NanoKvmSessionBinding?,
    scope: CoroutineScope,
) : AutoCloseable {
    private val operationMutex = Mutex()
    private val scriptStateLock = Any()
    private var latestScriptCatalog: NanoKvmOperatorScriptCatalog? = null
    private var closed = false

    val terminal = NanoKvmOperatorTerminalOwner(
        terminalFactory = port::newTerminal,
        binding = binding,
        currentBinding = currentBinding,
        scope = scope,
    )

    suspend fun refreshScripts(): NanoKvmOperatorScriptReadResult = operationMutex.withLock {
        refreshScriptsLocked()
    }

    suspend fun uploadScript(
        fileName: String,
        content: ByteArray,
    ): NanoKvmOperatorScriptCommandResult<NanoKvmOperatorScriptUploadReceipt> =
        operationMutex.withLock {
            if (!isSafeScriptBasename(fileName) || content.isEmpty() || content.size > MAX_SCRIPT_UPLOAD_BYTES) {
                return@withLock rejected(
                    NanoKvmOperatorOperation.SCRIPT_UPLOAD,
                    NanoKvmOperatorError.Kind.INVALID_REQUEST,
                )
            }
            requireCurrentBinding(NanoKvmOperatorOperation.SCRIPT_UPLOAD)?.let {
                return@withLock NanoKvmOperatorScriptCommandResult.Rejected(it)
            }

            // Invalidate before dispatch: timeout/cancellation cannot preserve a pre-mutation view.
            invalidateLatestScriptCatalog()
            val retainedContent = content.copyOf()
            try {
                val receipt = port.uploadScript(fileName, retainedContent)
                requireCurrentBinding(NanoKvmOperatorOperation.SCRIPT_UPLOAD)?.let {
                    return@withLock NanoKvmOperatorScriptCommandResult.Indeterminate(
                        dispatchError = it,
                    )
                }
                if (receipt.displayName != fileName || receipt.byteCount != retainedContent.size) {
                    return@withLock NanoKvmOperatorScriptCommandResult.Indeterminate(
                        dispatchError = operatorError(
                            NanoKvmOperatorOperation.SCRIPT_UPLOAD,
                            NanoKvmOperatorError.Kind.INVALID_RESPONSE,
                        ),
                    )
                }
                NanoKvmOperatorScriptCommandResult.Completed(
                    NanoKvmOperatorScriptUploadReceipt(receipt.displayName, receipt.byteCount),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                requireCurrentBinding(NanoKvmOperatorOperation.SCRIPT_UPLOAD)?.let {
                    return@withLock NanoKvmOperatorScriptCommandResult.Indeterminate(
                        dispatchError = it,
                    )
                }
                if (error.isDefiniteOperatorRejection()) {
                    NanoKvmOperatorScriptCommandResult.Rejected(
                        error.toOperatorError(NanoKvmOperatorOperation.SCRIPT_UPLOAD),
                    )
                } else {
                    NanoKvmOperatorScriptCommandResult.Indeterminate(
                        dispatchError = error.toOperatorError(NanoKvmOperatorOperation.SCRIPT_UPLOAD),
                    )
                }
            } finally {
                retainedContent.fill(0)
            }
        }

    suspend fun runScript(
        catalog: NanoKvmOperatorScriptCatalog,
        script: NanoKvmOperatorScript,
        mode: NanoKvmScriptRunMode,
    ): NanoKvmOperatorScriptCommandResult<NanoKvmOperatorScriptExecution> =
        operationMutex.withLock {
            val warnings = mode.operatorWarnings()
            validateScriptHandle(catalog, script, NanoKvmOperatorOperation.SCRIPT_RUN)?.let {
                return@withLock NanoKvmOperatorScriptCommandResult.Rejected(it, warnings)
            }

            // Consume the UI snapshot before dispatch so an ambiguous execution cannot be replayed.
            invalidateLatestScriptCatalog()
            try {
                val result = port.runScript(catalog.portCatalog, script.portScript, mode)
                requireCurrentBinding(NanoKvmOperatorOperation.SCRIPT_RUN)?.let {
                    return@withLock NanoKvmOperatorScriptCommandResult.Indeterminate(
                        dispatchError = it,
                        warnings = warnings,
                    )
                }
                if (
                    result.mode != mode ||
                    !result.output.hasBoundedUtf8Length(MAX_SCRIPT_OUTPUT_BYTES)
                ) {
                    return@withLock NanoKvmOperatorScriptCommandResult.Indeterminate(
                        dispatchError = operatorError(
                            NanoKvmOperatorOperation.SCRIPT_RUN,
                            NanoKvmOperatorError.Kind.INVALID_RESPONSE,
                        ),
                        warnings = warnings,
                    )
                }
                NanoKvmOperatorScriptCommandResult.Completed(
                    value = NanoKvmOperatorScriptExecution(
                        mode = result.mode,
                        output = NanoKvmOperatorScriptOutput(result.output),
                        warnings = warnings,
                    ),
                    warnings = warnings,
                )
            } catch (error: CancellationException) {
                // Foreground cancellation is deliberately documented by [warnings]; never replay.
                throw error
            } catch (error: Throwable) {
                requireCurrentBinding(NanoKvmOperatorOperation.SCRIPT_RUN)?.let {
                    return@withLock NanoKvmOperatorScriptCommandResult.Indeterminate(
                        dispatchError = it,
                        warnings = warnings,
                    )
                }
                if (error.isDefiniteOperatorRejection()) {
                    NanoKvmOperatorScriptCommandResult.Rejected(
                        error = error.toOperatorError(NanoKvmOperatorOperation.SCRIPT_RUN),
                        warnings = warnings,
                    )
                } else {
                    NanoKvmOperatorScriptCommandResult.Indeterminate(
                        dispatchError = error.toOperatorError(NanoKvmOperatorOperation.SCRIPT_RUN),
                        warnings = warnings,
                    )
                }
            }
        }

    suspend fun deleteScript(
        catalog: NanoKvmOperatorScriptCatalog,
        script: NanoKvmOperatorScript,
    ): NanoKvmOperatorScriptCommandResult<NanoKvmOperatorScriptDeleted> =
        operationMutex.withLock {
            validateScriptHandle(catalog, script, NanoKvmOperatorOperation.SCRIPT_DELETE)?.let {
                return@withLock NanoKvmOperatorScriptCommandResult.Rejected(it)
            }
            val deletedDisplayName = script.displayName
            invalidateLatestScriptCatalog()
            try {
                port.deleteScript(catalog.portCatalog, script.portScript)
                requireCurrentBinding(NanoKvmOperatorOperation.SCRIPT_DELETE)?.let {
                    NanoKvmOperatorScriptCommandResult.Indeterminate(dispatchError = it)
                } ?: NanoKvmOperatorScriptCommandResult.Completed(NanoKvmOperatorScriptDeleted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                requireCurrentBinding(NanoKvmOperatorOperation.SCRIPT_DELETE)?.let {
                    return@withLock NanoKvmOperatorScriptCommandResult.Indeterminate(
                        dispatchError = it,
                    )
                }
                if (error.isDefiniteOperatorRejection()) {
                    return@withLock NanoKvmOperatorScriptCommandResult.Rejected(
                        error.toOperatorError(NanoKvmOperatorOperation.SCRIPT_DELETE),
                    )
                }
                val dispatchError = error.toOperatorError(NanoKvmOperatorOperation.SCRIPT_DELETE)
                when (val refreshed = refreshScriptsLocked()) {
                    is NanoKvmOperatorScriptReadResult.Success ->
                        NanoKvmOperatorScriptCommandResult.Reconciled(
                            catalog = refreshed.catalog,
                            observation = if (
                                refreshed.catalog.scripts.none { it.displayName == deletedDisplayName }
                            ) {
                                NanoKvmScriptDeleteObservation.ABSENT
                            } else {
                                NanoKvmScriptDeleteObservation.PRESENT
                            },
                            dispatchError = dispatchError,
                        )
                    is NanoKvmOperatorScriptReadResult.Failure ->
                        NanoKvmOperatorScriptCommandResult.Indeterminate(
                            dispatchError = dispatchError,
                            refreshError = refreshed.error,
                        )
                }
            }
        }

    override fun close() {
        synchronized(scriptStateLock) {
            closed = true
            latestScriptCatalog = null
        }
        terminal.close()
    }

    private suspend fun refreshScriptsLocked(): NanoKvmOperatorScriptReadResult {
        requireCurrentBinding(NanoKvmOperatorOperation.SCRIPT_LIST)?.let {
            return NanoKvmOperatorScriptReadResult.Failure(it)
        }
        val portCatalog = try {
            port.listScripts()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return NanoKvmOperatorScriptReadResult.Failure(
                error.toOperatorError(NanoKvmOperatorOperation.SCRIPT_LIST),
            )
        }
        requireCurrentBinding(NanoKvmOperatorOperation.SCRIPT_LIST)?.let {
            return NanoKvmOperatorScriptReadResult.Failure(it)
        }
        val scripts = portCatalog.scripts.map { portScript ->
            NanoKvmOperatorScript(
                displayName = portScript.displayName,
                binding = binding,
                portScript = portScript,
            )
        }
        val catalog = NanoKvmOperatorScriptCatalog(
            scripts = scripts,
            binding = binding,
            portCatalog = portCatalog,
        )
        val installed = synchronized(scriptStateLock) {
            if (closed) {
                false
            } else {
                latestScriptCatalog = catalog
                true
            }
        }
        if (!installed) {
            return NanoKvmOperatorScriptReadResult.Failure(
                operatorError(
                    NanoKvmOperatorOperation.SCRIPT_LIST,
                    NanoKvmOperatorError.Kind.SESSION_CHANGED,
                ),
            )
        }
        return NanoKvmOperatorScriptReadResult.Success(catalog)
    }

    private fun validateScriptHandle(
        catalog: NanoKvmOperatorScriptCatalog,
        script: NanoKvmOperatorScript,
        operation: NanoKvmOperatorOperation,
    ): NanoKvmOperatorError? {
        requireCurrentBinding(operation)?.let { return it }
        if (
            catalog.binding != binding ||
            script.binding != binding ||
            synchronized(scriptStateLock) { latestScriptCatalog !== catalog } ||
            catalog.scripts.none { it === script } ||
            catalog.portCatalog.scripts.none { it === script.portScript }
        ) {
            return operatorError(operation, NanoKvmOperatorError.Kind.FOREIGN_OR_STALE_STATE)
        }
        return null
    }

    private fun requireCurrentBinding(operation: NanoKvmOperatorOperation): NanoKvmOperatorError? {
        val isClosed = synchronized(scriptStateLock) { closed }
        if (!isClosed && currentBinding() == binding) return null
        invalidateLatestScriptCatalog()
        return operatorError(operation, NanoKvmOperatorError.Kind.SESSION_CHANGED)
    }

    private fun invalidateLatestScriptCatalog() {
        synchronized(scriptStateLock) {
            latestScriptCatalog = null
        }
    }

    private fun <Value> rejected(
        operation: NanoKvmOperatorOperation,
        kind: NanoKvmOperatorError.Kind,
    ): NanoKvmOperatorScriptCommandResult<Value> =
        NanoKvmOperatorScriptCommandResult.Rejected(operatorError(operation, kind))

    private companion object {
        const val MAX_SCRIPT_UPLOAD_BYTES = 512 * 1024
        const val MAX_SCRIPT_OUTPUT_BYTES = 256 * 1024
    }
}

private val SAFE_OPERATOR_SCRIPT_BASENAME = Regex(
    pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,250}\\.(?:sh|py)",
    option = RegexOption.IGNORE_CASE,
)

private fun isSafeScriptBasename(value: String): Boolean =
    value.hasBoundedUtf8Length(255) && SAFE_OPERATOR_SCRIPT_BASENAME.matches(value) && ".." !in value

private fun String.hasBoundedUtf8Length(limit: Int): Boolean {
    var bytes = 0
    var index = 0
    while (index < length) {
        val value = this[index]
        bytes += when {
            value.code < 0x80 -> 1
            value.code < 0x800 -> 2
            value.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            else -> 3
        }
        if (bytes > limit) return false
        index++
    }
    return true
}

private fun operatorError(
    operation: NanoKvmOperatorOperation,
    kind: NanoKvmOperatorError.Kind,
): NanoKvmOperatorError = NanoKvmOperatorError(operation, kind)

private fun rejected(
    operation: NanoKvmOperatorOperation,
    kind: NanoKvmOperatorError.Kind,
): NanoKvmOperatorActionResult.Rejected =
    NanoKvmOperatorActionResult.Rejected(operatorError(operation, kind))

private fun Throwable.toOperatorError(operation: NanoKvmOperatorOperation): NanoKvmOperatorError =
    operatorError(
        operation,
        when (this) {
            is IllegalArgumentException -> NanoKvmOperatorError.Kind.INVALID_REQUEST
            is AuthenticationExpiredException -> NanoKvmOperatorError.Kind.AUTHENTICATION_EXPIRED
            is ApiResponseException, is NanoKvmScriptOperationException ->
                NanoKvmOperatorError.Kind.SERVER_REJECTED
            is InvalidApiResponseException -> NanoKvmOperatorError.Kind.INVALID_RESPONSE
            is IOException, is HttpResponseException -> NanoKvmOperatorError.Kind.CONNECTION
            else -> NanoKvmOperatorError.Kind.UNEXPECTED
        },
    )

private fun Throwable.isDefiniteOperatorRejection(): Boolean =
    this is IllegalArgumentException ||
        this is AuthenticationExpiredException ||
        this is ApiResponseException ||
        this is NanoKvmScriptOperationException

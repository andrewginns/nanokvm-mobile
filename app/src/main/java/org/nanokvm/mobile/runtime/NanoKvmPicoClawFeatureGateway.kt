package org.nanokvm.mobile.runtime

import java.io.IOException
import java.time.Instant
import java.util.Base64
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
import org.nanokvm.protocol.NanoKvmPicoClawAgentProfile
import org.nanokvm.protocol.NanoKvmPicoClawApiBase
import org.nanokvm.protocol.NanoKvmPicoClawApiException
import org.nanokvm.protocol.NanoKvmPicoClawAssistantMessageKind
import org.nanokvm.protocol.NanoKvmPicoClawGatewayEvent
import org.nanokvm.protocol.NanoKvmPicoClawGatewayState
import org.nanokvm.protocol.NanoKvmPicoClawHistoryRole
import org.nanokvm.protocol.NanoKvmPicoClawInboundMessage
import org.nanokvm.protocol.NanoKvmPicoClawManualHidLockState
import org.nanokvm.protocol.NanoKvmPicoClawMessageOptions
import org.nanokvm.protocol.NanoKvmPicoClawRuntimePhase
import org.nanokvm.protocol.NanoKvmPicoClawRuntimeStatus

/** User-facing security facts which must be shown before PicoClaw gains control. */
internal object NanoKvmPicoClawPermissionDisclosure {
    const val TITLE = "PicoClaw has broad control"
    const val EXPLANATION =
        "PicoClaw can read and write broadly across the NanoKVM appliance filesystem, " +
            "execute commands, create scheduled cron work, invoke configured MCP/tool " +
            "integrations, and send keyboard and mouse input to the attached host. While its " +
            "control session is open, manual KVM input is blocked."
    const val UNINSTALL_EXPLANATION =
        "Uninstall permanently removes the PicoClaw runtime and its configuration from the " +
            "NanoKVM appliance. This cannot be undone by the app."
}

internal enum class NanoKvmPicoClawOperation {
    FEATURE_ENTRY,
    STATUS_REFRESH,
    RUNTIME_INSTALL,
    RUNTIME_UNINSTALL,
    RUNTIME_START,
    RUNTIME_STOP,
    AGENT_PROFILE,
    MODEL_CONFIGURATION,
    HISTORY_LIST,
    HISTORY_DETAIL,
    HISTORY_DELETE,
    CHAT_OPEN,
    CHAT_SEND,
    CHAT_CANCEL,
    CHAT_RELEASE,
}

/** Error safe to retain in UI state, saved-state diagnostics, or logs. */
internal data class NanoKvmPicoClawError(
    val operation: NanoKvmPicoClawOperation,
    val kind: Kind,
) {
    enum class Kind {
        SESSION_CHANGED,
        FEATURE_ENTRY_REQUIRED,
        APPROVAL_REQUIRED,
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

internal enum class NanoKvmPicoClawRuntimeObservation {
    DESIRED_STATE,
    OTHER_STATE,
}

internal sealed interface NanoKvmPicoClawReadResult<out State> {
    data class Success<State>(val state: State) : NanoKvmPicoClawReadResult<State>
    data class Failure(val error: NanoKvmPicoClawError) : NanoKvmPicoClawReadResult<Nothing>
}

/** A mutation is dispatched at most once. Reconciliation is always a separate read. */
internal sealed interface NanoKvmPicoClawMutationResult<out State> {
    data class Applied<State>(val state: State) : NanoKvmPicoClawMutationResult<State>
    data class AlreadySatisfied<State>(val state: State) : NanoKvmPicoClawMutationResult<State>
    data class Accepted<State>(
        val state: State?,
        val refreshError: NanoKvmPicoClawError?,
    ) : NanoKvmPicoClawMutationResult<State>

    data class Reconciled<State>(
        val state: State,
        val observation: NanoKvmPicoClawRuntimeObservation,
        val dispatchError: NanoKvmPicoClawError,
    ) : NanoKvmPicoClawMutationResult<State>

    data class Indeterminate<State>(
        val state: State?,
        val dispatchError: NanoKvmPicoClawError,
        val refreshError: NanoKvmPicoClawError?,
    ) : NanoKvmPicoClawMutationResult<State>

    data class Rejected(val error: NanoKvmPicoClawError) :
        NanoKvmPicoClawMutationResult<Nothing>
}

internal enum class NanoKvmPicoClawUiRuntimePhase {
    CHECKING,
    INSTALLING,
    INSTALLED,
    READY,
    STOPPED,
    NOT_INSTALLED,
    MODEL_NOT_CONFIGURED,
    CONFIG_ERROR,
    UNAVAILABLE,
    ERROR,
    OTHER,
}

/** Bounded status for display. Server paths, commands, output, raw errors and session IDs vanish. */
internal class NanoKvmPicoClawRuntimeSnapshot internal constructor(
    val ready: Boolean,
    val installed: Boolean,
    val installing: Boolean,
    val installProgress: Int?,
    val installStage: String?,
    val agentProfile: NanoKvmPicoClawAgentProfile?,
    val modelConfigured: Boolean,
    val modelName: String?,
    val phase: NanoKvmPicoClawUiRuntimePhase,
    val hasConfigurationError: Boolean,
    val hasRuntimeError: Boolean,
    val checkedAt: Instant?,
    val manualInputLocked: Boolean,
    internal val binding: NanoKvmSessionBinding,
) {
    override fun toString(): String =
        "NanoKvmPicoClawRuntimeSnapshot(ready=$ready, installed=$installed, " +
            "installing=$installing, installProgress=$installProgress, phase=$phase, " +
            "modelConfigured=$modelConfigured, manualInputLocked=$manualInputLocked, " +
            "displayText=<redacted>)"
}

/**
 * Single-use model update which takes ownership of [apiKey]. The original array is cleared after
 * the first attempted use, including local rejection, cancellation, or protocol failure. It is
 * never exposed as String by the app layer and must never be put in saved state or logs.
 */
internal class NanoKvmPicoClawModelUpdate private constructor(
    val model: String,
    val apiBase: NanoKvmPicoClawApiBase,
    private val apiKey: CharArray,
) {
    private var consumed = false

    internal suspend fun <T> consume(block: suspend (CharArray) -> T): T {
        synchronized(this) {
            check(!consumed) { "PicoClaw model update has already been consumed" }
            consumed = true
        }
        return try {
            block(apiKey)
        } finally {
            apiKey.fill('\u0000')
        }
    }

    fun clear() {
        synchronized(this) { consumed = true }
        apiKey.fill('\u0000')
    }

    override fun toString(): String =
        "NanoKvmPicoClawModelUpdate(model=<redacted>, apiBase=<redacted>, apiKey=<redacted>)"

    companion object {
        fun takeOwnership(
            model: String,
            apiBase: NanoKvmPicoClawApiBase,
            apiKey: CharArray,
        ): NanoKvmPicoClawModelUpdate = try {
            require(model == model.trim() && model.isNotEmpty()) {
                "PicoClaw model name must not be blank or surrounded by whitespace"
            }
            require(model.utf8Size() <= MAX_UI_MODEL_NAME_BYTES) {
                "PicoClaw model name is too long"
            }
            require(apiKey.isNotEmpty() && apiKey.size <= MAX_UI_PROVIDER_KEY_CHARS) {
                "PicoClaw provider key is blank or too long"
            }
            require(apiKey.none(Char::isISOControl)) {
                "PicoClaw provider key contains unsupported control characters"
            }
            NanoKvmPicoClawModelUpdate(model, apiBase, apiKey)
        } catch (error: Throwable) {
            apiKey.fill('\u0000')
            throw error
        }
    }
}

/** One-use proof that the destructive uninstall warning was confirmed for this exact owner. */
internal class NanoKvmPicoClawUninstallConsent internal constructor(
    private val owner: Any,
    private val binding: NanoKvmSessionBinding,
) {
    private var consumed = false

    internal fun consume(expectedOwner: Any, expectedBinding: NanoKvmSessionBinding): Boolean =
        synchronized(this) {
            if (consumed || owner !== expectedOwner || binding != expectedBinding) {
                return@synchronized false
            }
            consumed = true
            true
        }

    override fun toString(): String =
        "NanoKvmPicoClawUninstallConsent(destination=<redacted>, consumed=$consumed)"
}

/** One-use proof that broad appliance, filesystem, execution and attached-host control was shown. */
internal class NanoKvmPicoClawControlConsent internal constructor(
    private val owner: Any,
    private val binding: NanoKvmSessionBinding,
) {
    private var consumed = false

    internal fun consume(expectedOwner: Any, expectedBinding: NanoKvmSessionBinding): Boolean =
        synchronized(this) {
            if (consumed || owner !== expectedOwner || binding != expectedBinding) {
                return@synchronized false
            }
            consumed = true
            true
        }

    override fun toString(): String =
        "NanoKvmPicoClawControlConsent(destination=<redacted>, consumed=$consumed)"
}

/** Opaque UI identity tied to one exact history catalog. */
internal class NanoKvmPicoClawHistoryItem internal constructor(
    val title: String,
    val preview: String,
    val messageCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    internal val binding: NanoKvmSessionBinding,
    internal val portSession: NanoKvmPicoClawPortHistorySession,
) {
    override fun toString(): String =
        "NanoKvmPicoClawHistoryItem(messageCount=$messageCount, content=<redacted>)"
}

internal class NanoKvmPicoClawHistoryCatalogSnapshot internal constructor(
    items: List<NanoKvmPicoClawHistoryItem>,
    internal val binding: NanoKvmSessionBinding,
    internal val portCatalog: NanoKvmPicoClawPortHistoryCatalog,
) {
    val items = items.toList()

    override fun toString(): String =
        "NanoKvmPicoClawHistoryCatalogSnapshot(items=${items.size})"
}

internal class NanoKvmPicoClawHistoryUiMessage internal constructor(
    val role: NanoKvmPicoClawHistoryRole,
    val content: String,
) {
    override fun toString(): String =
        "NanoKvmPicoClawHistoryUiMessage(role=$role, content=<redacted>)"
}

internal class NanoKvmPicoClawHistoryDetailSnapshot internal constructor(
    val item: NanoKvmPicoClawHistoryItem,
    messages: List<NanoKvmPicoClawHistoryUiMessage>,
    val summary: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val messages = messages.toList()

    override fun toString(): String =
        "NanoKvmPicoClawHistoryDetailSnapshot(messages=${messages.size}, content=<redacted>)"
}

internal class NanoKvmPicoClawHistoryDeletionConsent internal constructor(
    private val owner: Any,
    private val catalog: NanoKvmPicoClawHistoryCatalogSnapshot,
    private val item: NanoKvmPicoClawHistoryItem,
) {
    private var consumed = false

    internal fun consume(
        expectedOwner: Any,
        expectedCatalog: NanoKvmPicoClawHistoryCatalogSnapshot,
        expectedItem: NanoKvmPicoClawHistoryItem,
    ): Boolean = synchronized(this) {
        if (
            consumed || owner !== expectedOwner || catalog !== expectedCatalog ||
            item !== expectedItem
        ) {
            return@synchronized false
        }
        consumed = true
        true
    }

    override fun toString(): String =
        "NanoKvmPicoClawHistoryDeletionConsent(history=<opaque>, consumed=$consumed)"
}

internal enum class NanoKvmPicoClawHistoryDeleteObservation {
    ABSENT,
    PRESENT,
    UNKNOWN,
}

internal sealed interface NanoKvmPicoClawHistoryDeleteResult {
    data class Applied(val catalog: NanoKvmPicoClawHistoryCatalogSnapshot) :
        NanoKvmPicoClawHistoryDeleteResult

    data class Reconciled(
        val observation: NanoKvmPicoClawHistoryDeleteObservation,
        val dispatchError: NanoKvmPicoClawError,
    ) : NanoKvmPicoClawHistoryDeleteResult

    data class Accepted(val refreshError: NanoKvmPicoClawError?) :
        NanoKvmPicoClawHistoryDeleteResult

    data class Indeterminate(
        val dispatchError: NanoKvmPicoClawError,
        val refreshError: NanoKvmPicoClawError?,
    ) : NanoKvmPicoClawHistoryDeleteResult

    data class Rejected(val error: NanoKvmPicoClawError) :
        NanoKvmPicoClawHistoryDeleteResult
}

internal sealed interface NanoKvmPicoClawChatConnectionState {
    data object Inactive : NanoKvmPicoClawChatConnectionState
    data object Connecting : NanoKvmPicoClawChatConnectionState
    data object Open : NanoKvmPicoClawChatConnectionState
    data object Closing : NanoKvmPicoClawChatConnectionState
    data object Closed : NanoKvmPicoClawChatConnectionState
    data class Failed(val error: NanoKvmPicoClawError) : NanoKvmPicoClawChatConnectionState
}

/** Conspicuous manual-input state; any non-[Released] state must remain visible in the UI. */
internal sealed interface NanoKvmPicoClawManualInputState {
    val manualInputBlockedOrUncertain: Boolean
    val warning: String?

    data object Released : NanoKvmPicoClawManualInputState {
        override val manualInputBlockedOrUncertain = false
        override val warning: String? = null
    }

    data object Acquiring : NanoKvmPicoClawManualInputState {
        override val manualInputBlockedOrUncertain = true
        override val warning = "PicoClaw is taking exclusive keyboard and mouse control."
    }

    data object Held : NanoKvmPicoClawManualInputState {
        override val manualInputBlockedOrUncertain = true
        override val warning =
            "Manual keyboard and mouse input is blocked until PicoClaw control is closed."
    }

    data object HeldByOther : NanoKvmPicoClawManualInputState {
        override val manualInputBlockedOrUncertain = true
        override val warning = "Another PicoClaw session holds exclusive keyboard and mouse control."
    }

    data object Releasing : NanoKvmPicoClawManualInputState {
        override val manualInputBlockedOrUncertain = true
        override val warning = "Restoring manual keyboard and mouse control."
    }

    data object ReleaseUncertain : NanoKvmPicoClawManualInputState {
        override val manualInputBlockedOrUncertain = true
        override val warning =
            "The app could not confirm that manual keyboard and mouse control was restored."
    }
}

internal class NanoKvmPicoClawChatImage internal constructor(bytes: ByteArray) {
    private val retained = bytes.copyOf()

    init {
        require(retained.size <= MAX_UI_CHAT_IMAGE_BYTES) { "PicoClaw image is too large" }
    }

    val size: Int
        get() = retained.size

    fun copyBytes(): ByteArray = retained.copyOf()

    override fun toString(): String = "NanoKvmPicoClawChatImage(size=$size, data=<redacted>)"
}

internal enum class NanoKvmPicoClawAssistantUpdateKind {
    CREATED,
    UPDATED,
}

/** Replay-free, bounded chat event. Raw JSON, session IDs and peer close reasons never escape. */
internal sealed interface NanoKvmPicoClawChatEvent {
    data object TypingStarted : NanoKvmPicoClawChatEvent
    data object TypingStopped : NanoKvmPicoClawChatEvent

    class AssistantMessage internal constructor(
        val kind: NanoKvmPicoClawAssistantUpdateKind,
        val text: String,
    ) : NanoKvmPicoClawChatEvent {
        override fun toString(): String =
            "NanoKvmPicoClawChatEvent.AssistantMessage(kind=$kind, text=<redacted>)"
    }

    class Observation internal constructor(
        val text: String?,
        val image: NanoKvmPicoClawChatImage,
    ) : NanoKvmPicoClawChatEvent {
        override fun toString(): String =
            "NanoKvmPicoClawChatEvent.Observation(text=<redacted>, image=$image)"
    }

    class ToolAction internal constructor(
        val action: String,
        val x: Double?,
        val y: Double?,
    ) : NanoKvmPicoClawChatEvent {
        override fun toString(): String =
            "NanoKvmPicoClawChatEvent.ToolAction(action=<redacted>, x=$x, y=$y)"
    }

    data object RemoteError : NanoKvmPicoClawChatEvent
    data object FutureMessage : NanoKvmPicoClawChatEvent
}

internal sealed interface NanoKvmPicoClawChatActionResult {
    data object Dispatched : NanoKvmPicoClawChatActionResult

    /** The exact bounded user message that was dispatched and is safe to retain for display. */
    data class MessageDispatched(
        val message: PicoClawMessageUiState,
    ) : NanoKvmPicoClawChatActionResult

    data class Rejected(val error: NanoKvmPicoClawError) : NanoKvmPicoClawChatActionResult
}

internal sealed interface NanoKvmPicoClawChatReleaseResult {
    data object Released : NanoKvmPicoClawChatReleaseResult
    data object HeldByOther : NanoKvmPicoClawChatReleaseResult
    data class Indeterminate(val error: NanoKvmPicoClawError) :
        NanoKvmPicoClawChatReleaseResult
    data class Rejected(val error: NanoKvmPicoClawError) :
        NanoKvmPicoClawChatReleaseResult
}

/**
 * Owns one approved chat socket. The socket is created and connected once; input is never queued,
 * reconnect is impossible, cancel does not release HID, and only [closeAndRelease] performs the
 * authoritative one-shot release contract. Plain [close] is local cleanup and therefore leaves a
 * held lock represented as [NanoKvmPicoClawManualInputState.ReleaseUncertain].
 */
internal class NanoKvmPicoClawChatOwner internal constructor(
    private val port: NanoKvmPicoClawPort,
    val binding: NanoKvmSessionBinding,
    private val currentBinding: () -> NanoKvmSessionBinding?,
    private val scope: CoroutineScope,
    private val ownerIdentity: Any,
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow<NanoKvmPicoClawChatConnectionState>(
        NanoKvmPicoClawChatConnectionState.Inactive,
    )
    private val mutableManualInput = MutableStateFlow<NanoKvmPicoClawManualInputState>(
        NanoKvmPicoClawManualInputState.Released,
    )
    private val mutableEvents = MutableSharedFlow<NanoKvmPicoClawChatEvent>(
        replay = 0,
        extraBufferCapacity = MAX_RETAINED_CHAT_EVENTS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var active: ActiveChat? = null
    private var openAttempted = false
    private var disposed = false

    val state: StateFlow<NanoKvmPicoClawChatConnectionState> = mutableState.asStateFlow()
    val manualInput: StateFlow<NanoKvmPicoClawManualInputState> =
        mutableManualInput.asStateFlow()
    val events: SharedFlow<NanoKvmPicoClawChatEvent> = mutableEvents.asSharedFlow()

    fun open(consent: NanoKvmPicoClawControlConsent?): NanoKvmPicoClawChatActionResult {
        if (!hasCurrentBinding()) return rejected(
            NanoKvmPicoClawOperation.CHAT_OPEN,
            NanoKvmPicoClawError.Kind.SESSION_CHANGED,
        )
        if (consent?.consume(ownerIdentity, binding) != true) return rejected(
            NanoKvmPicoClawOperation.CHAT_OPEN,
            NanoKvmPicoClawError.Kind.APPROVAL_REQUIRED,
        )

        synchronized(lock) {
            if (disposed || openAttempted || active != null) return rejected(
                NanoKvmPicoClawOperation.CHAT_OPEN,
                NanoKvmPicoClawError.Kind.ALREADY_ACTIVE,
            )
            openAttempted = true
        }

        val chat = try {
            port.newChat(binding.sessionGeneration)
        } catch (error: Throwable) {
            mutableState.value = NanoKvmPicoClawChatConnectionState.Failed(
                error.toPicoClawError(NanoKvmPicoClawOperation.CHAT_OPEN),
            )
            return NanoKvmPicoClawChatActionResult.Rejected(
                error.toPicoClawError(NanoKvmPicoClawOperation.CHAT_OPEN),
            )
        }
        if (!hasCurrentBinding()) {
            runCatching { chat.close() }
            mutableManualInput.value = NanoKvmPicoClawManualInputState.ReleaseUncertain
            return rejected(
                NanoKvmPicoClawOperation.CHAT_OPEN,
                NanoKvmPicoClawError.Kind.SESSION_CHANGED,
            )
        }

        val installed = ActiveChat(chat)
        synchronized(lock) {
            if (disposed || active != null) {
                runCatching { chat.close() }
                return rejected(
                    NanoKvmPicoClawOperation.CHAT_OPEN,
                    NanoKvmPicoClawError.Kind.ALREADY_ACTIVE,
                )
            }
            active = installed
            mutableState.value = NanoKvmPicoClawChatConnectionState.Connecting
            mutableManualInput.value = NanoKvmPicoClawManualInputState.Acquiring
        }

        installed.stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            chat.state.collect { handleProtocolState(installed, it) }
        }
        installed.lockJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            chat.manualHidLock.collect { handleProtocolLock(installed, it) }
        }
        installed.eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            chat.events.collect { handleProtocolEvent(installed, it) }
        }

        if (!hasCurrentBinding()) {
            closeLocally(installed, sessionChanged = true)
            return rejected(
                NanoKvmPicoClawOperation.CHAT_OPEN,
                NanoKvmPicoClawError.Kind.SESSION_CHANGED,
            )
        }
        val connected = try {
            chat.connect()
        } catch (error: Throwable) {
            closeLocally(installed, sessionChanged = false)
            mutableState.value = NanoKvmPicoClawChatConnectionState.Failed(
                error.toPicoClawError(NanoKvmPicoClawOperation.CHAT_OPEN),
            )
            return NanoKvmPicoClawChatActionResult.Rejected(
                error.toPicoClawError(NanoKvmPicoClawOperation.CHAT_OPEN),
            )
        }
        if (!connected) {
            closeLocally(installed, sessionChanged = false)
            return rejected(
                NanoKvmPicoClawOperation.CHAT_OPEN,
                NanoKvmPicoClawError.Kind.CONNECTION,
            )
        }
        return NanoKvmPicoClawChatActionResult.Dispatched
    }

    fun sendMessage(
        content: String,
        maxSteps: Int = 20,
        maxRuntimeMillis: Int = 120_000,
    ): NanoKvmPicoClawChatActionResult {
        if (!hasCurrentBinding()) return rejected(
            NanoKvmPicoClawOperation.CHAT_SEND,
            NanoKvmPicoClawError.Kind.SESSION_CHANGED,
        )
        val normalized = content.trim()
        val displayContent = if (normalized.isEmpty()) {
            null
        } else {
            try {
                PicoClawMessageContent.ApplianceText(
                    role = PicoClawMessageRole.User,
                    value = normalized,
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        if (
            displayContent == null ||
            normalized.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        ) {
            return rejected(
                NanoKvmPicoClawOperation.CHAT_SEND,
                NanoKvmPicoClawError.Kind.INVALID_REQUEST,
            )
        }
        val options = try {
            NanoKvmPicoClawMessageOptions(maxSteps, maxRuntimeMillis)
        } catch (_: IllegalArgumentException) {
            return rejected(
                NanoKvmPicoClawOperation.CHAT_SEND,
                NanoKvmPicoClawError.Kind.INVALID_REQUEST,
            )
        }
        val chat = synchronized(lock) { active?.port } ?: return rejected(
            NanoKvmPicoClawOperation.CHAT_SEND,
            NanoKvmPicoClawError.Kind.NOT_CONNECTED,
        )
        val receipt = try {
            chat.sendMessage(displayContent.value, options)
        } catch (error: Throwable) {
            return NanoKvmPicoClawChatActionResult.Rejected(
                error.toPicoClawError(NanoKvmPicoClawOperation.CHAT_SEND),
            )
        }
        return if (receipt != null) {
            NanoKvmPicoClawChatActionResult.MessageDispatched(
                PicoClawMessageUiState(displayContent),
            )
        } else {
            rejected(
                NanoKvmPicoClawOperation.CHAT_SEND,
                NanoKvmPicoClawError.Kind.NOT_CONNECTED,
            )
        }
    }

    /** Cancels one run without changing or claiming to release the manual-HID lock. */
    fun cancelRun(): NanoKvmPicoClawChatActionResult {
        if (!hasCurrentBinding()) return rejected(
            NanoKvmPicoClawOperation.CHAT_CANCEL,
            NanoKvmPicoClawError.Kind.SESSION_CHANGED,
        )
        val chat = synchronized(lock) { active?.port } ?: return rejected(
            NanoKvmPicoClawOperation.CHAT_CANCEL,
            NanoKvmPicoClawError.Kind.NOT_CONNECTED,
        )
        val cancelled = try {
            chat.cancelRun()
        } catch (error: Throwable) {
            return NanoKvmPicoClawChatActionResult.Rejected(
                error.toPicoClawError(NanoKvmPicoClawOperation.CHAT_CANCEL),
            )
        }
        return if (cancelled) NanoKvmPicoClawChatActionResult.Dispatched else rejected(
            NanoKvmPicoClawOperation.CHAT_CANCEL,
            NanoKvmPicoClawError.Kind.NOT_CONNECTED,
        )
    }

    suspend fun closeAndRelease(): NanoKvmPicoClawChatReleaseResult {
        if (!hasCurrentBinding()) return NanoKvmPicoClawChatReleaseResult.Rejected(
            picoError(
                NanoKvmPicoClawOperation.CHAT_RELEASE,
                NanoKvmPicoClawError.Kind.SESSION_CHANGED,
            ),
        )
        val current = synchronized(lock) {
            val value = active ?: return NanoKvmPicoClawChatReleaseResult.Rejected(
                picoError(
                    NanoKvmPicoClawOperation.CHAT_RELEASE,
                    NanoKvmPicoClawError.Kind.NOT_CONNECTED,
                ),
            )
            if (value.releaseAttempted) return NanoKvmPicoClawChatReleaseResult.Rejected(
                picoError(
                    NanoKvmPicoClawOperation.CHAT_RELEASE,
                    NanoKvmPicoClawError.Kind.INVALID_REQUEST,
                ),
            )
            value.releaseAttempted = true
            mutableState.value = NanoKvmPicoClawChatConnectionState.Closing
            mutableManualInput.value = NanoKvmPicoClawManualInputState.Releasing
            value
        }

        return try {
            val release = current.port.closeAndRelease()
            val result = when {
                release.released && release.currentSession == null -> {
                    mutableManualInput.value = NanoKvmPicoClawManualInputState.Released
                    NanoKvmPicoClawChatReleaseResult.Released
                }
                release.currentSession != null &&
                    release.currentSession != current.port.state.value.scope.session -> {
                    mutableManualInput.value = NanoKvmPicoClawManualInputState.HeldByOther
                    NanoKvmPicoClawChatReleaseResult.HeldByOther
                }
                else -> {
                    mutableManualInput.value = NanoKvmPicoClawManualInputState.ReleaseUncertain
                    NanoKvmPicoClawChatReleaseResult.Indeterminate(
                        picoError(
                            NanoKvmPicoClawOperation.CHAT_RELEASE,
                            NanoKvmPicoClawError.Kind.INVALID_RESPONSE,
                        ),
                    )
                }
            }
            finishReleasedChat(current)
            result
        } catch (error: CancellationException) {
            mutableManualInput.value = NanoKvmPicoClawManualInputState.ReleaseUncertain
            throw error
        } catch (error: Throwable) {
            // A release DELETE is never replayed. A status GET can prove the lock state without
            // repeating the mutation; failure to read stays conspicuously uncertain.
            val dispatchError = error.toPicoClawError(NanoKvmPicoClawOperation.CHAT_RELEASE)
            val reconciled = reconcileReleaseAfterFailure(current)
            finishReleasedChat(current)
            reconciled ?: NanoKvmPicoClawChatReleaseResult.Indeterminate(dispatchError)
        }
    }

    override fun close() {
        val current = synchronized(lock) {
            if (disposed) return
            disposed = true
            active
        } ?: return
        closeLocally(current, sessionChanged = false)
    }

    private suspend fun reconcileReleaseAfterFailure(
        activeChat: ActiveChat,
    ): NanoKvmPicoClawChatReleaseResult? {
        if (!hasCurrentBinding()) {
            mutableManualInput.value = NanoKvmPicoClawManualInputState.ReleaseUncertain
            return null
        }
        return try {
            val status = port.runtimeStatus()
            when {
                status.currentSession == null -> {
                    mutableManualInput.value = NanoKvmPicoClawManualInputState.Released
                    NanoKvmPicoClawChatReleaseResult.Released
                }
                status.currentSession != activeChat.port.state.value.scope.session -> {
                    mutableManualInput.value = NanoKvmPicoClawManualInputState.HeldByOther
                    NanoKvmPicoClawChatReleaseResult.HeldByOther
                }
                else -> {
                    mutableManualInput.value = NanoKvmPicoClawManualInputState.ReleaseUncertain
                    null
                }
            }
        } catch (error: CancellationException) {
            mutableManualInput.value = NanoKvmPicoClawManualInputState.ReleaseUncertain
            throw error
        } catch (_: Throwable) {
            mutableManualInput.value = NanoKvmPicoClawManualInputState.ReleaseUncertain
            null
        }
    }

    private fun handleProtocolState(
        expected: ActiveChat,
        protocolState: NanoKvmPicoClawGatewayState,
    ) {
        if (!isCurrent(expected)) return
        if (!hasCurrentBinding()) {
            closeLocally(expected, sessionChanged = true)
            return
        }
        mutableState.value = when (protocolState) {
            is NanoKvmPicoClawGatewayState.New -> NanoKvmPicoClawChatConnectionState.Inactive
            is NanoKvmPicoClawGatewayState.Connecting ->
                NanoKvmPicoClawChatConnectionState.Connecting
            is NanoKvmPicoClawGatewayState.Open -> NanoKvmPicoClawChatConnectionState.Open
            is NanoKvmPicoClawGatewayState.Closing -> NanoKvmPicoClawChatConnectionState.Closing
            is NanoKvmPicoClawGatewayState.Closed -> NanoKvmPicoClawChatConnectionState.Closed
            is NanoKvmPicoClawGatewayState.Failed ->
                NanoKvmPicoClawChatConnectionState.Failed(
                    protocolState.cause.toPicoClawError(NanoKvmPicoClawOperation.CHAT_OPEN),
                )
        }
    }

    private fun handleProtocolLock(
        expected: ActiveChat,
        protocolState: NanoKvmPicoClawManualHidLockState,
    ) {
        if (!isCurrent(expected) || !hasCurrentBinding()) return
        mutableManualInput.value = when (protocolState) {
            is NanoKvmPicoClawManualHidLockState.Released ->
                NanoKvmPicoClawManualInputState.Released
            is NanoKvmPicoClawManualHidLockState.Acquiring ->
                NanoKvmPicoClawManualInputState.Acquiring
            is NanoKvmPicoClawManualHidLockState.Held -> NanoKvmPicoClawManualInputState.Held
            is NanoKvmPicoClawManualHidLockState.HeldByOther ->
                NanoKvmPicoClawManualInputState.HeldByOther
            is NanoKvmPicoClawManualHidLockState.Releasing ->
                NanoKvmPicoClawManualInputState.Releasing
            is NanoKvmPicoClawManualHidLockState.ReleaseUncertain ->
                NanoKvmPicoClawManualInputState.ReleaseUncertain
        }
    }

    private fun handleProtocolEvent(
        expected: ActiveChat,
        event: NanoKvmPicoClawGatewayEvent,
    ) {
        if (!isCurrent(expected) || !hasCurrentBinding()) return
        if (event !is NanoKvmPicoClawGatewayEvent.Message) return
        event.message.toUiEvent()?.let(mutableEvents::tryEmit)
    }

    private fun NanoKvmPicoClawInboundMessage.toUiEvent(): NanoKvmPicoClawChatEvent? {
        return when (this) {
            NanoKvmPicoClawInboundMessage.TypingStarted ->
                NanoKvmPicoClawChatEvent.TypingStarted
            NanoKvmPicoClawInboundMessage.TypingStopped ->
                NanoKvmPicoClawChatEvent.TypingStopped
            NanoKvmPicoClawInboundMessage.Pong -> null
            is NanoKvmPicoClawInboundMessage.AssistantMessage ->
                NanoKvmPicoClawChatEvent.AssistantMessage(
                    kind = when (kind) {
                        NanoKvmPicoClawAssistantMessageKind.CREATED ->
                            NanoKvmPicoClawAssistantUpdateKind.CREATED
                        NanoKvmPicoClawAssistantMessageKind.UPDATED ->
                            NanoKvmPicoClawAssistantUpdateKind.UPDATED
                    },
                    text = text.takeUtf8(MAX_UI_CHAT_EVENT_TEXT_BYTES),
                )
            is NanoKvmPicoClawInboundMessage.Observation -> {
                val decoded = runCatching { Base64.getDecoder().decode(imageBase64) }.getOrNull()
                    ?: return null
                if (decoded.size > MAX_UI_CHAT_IMAGE_BYTES) return null
                NanoKvmPicoClawChatEvent.Observation(
                    text = text?.takeUtf8(MAX_UI_CHAT_EVENT_TEXT_BYTES),
                    image = NanoKvmPicoClawChatImage(decoded),
                )
            }
            is NanoKvmPicoClawInboundMessage.ToolAction ->
                NanoKvmPicoClawChatEvent.ToolAction(
                    action = action.takeUtf8(MAX_UI_TOOL_ACTION_BYTES),
                    x = x,
                    y = y,
                )
            is NanoKvmPicoClawInboundMessage.Error -> NanoKvmPicoClawChatEvent.RemoteError
            is NanoKvmPicoClawInboundMessage.Other -> NanoKvmPicoClawChatEvent.FutureMessage
        }
    }

    private fun closeLocally(expected: ActiveChat, sessionChanged: Boolean) {
        if (!isCurrent(expected)) return
        runCatching { expected.port.close() }
        expected.cancelCollectors()
        synchronized(lock) { if (active === expected) active = null }
        mutableState.value = if (sessionChanged) {
            NanoKvmPicoClawChatConnectionState.Failed(
                picoError(
                    NanoKvmPicoClawOperation.CHAT_RELEASE,
                    NanoKvmPicoClawError.Kind.SESSION_CHANGED,
                ),
            )
        } else {
            NanoKvmPicoClawChatConnectionState.Closed
        }
        if (mutableManualInput.value.manualInputBlockedOrUncertain) {
            mutableManualInput.value = NanoKvmPicoClawManualInputState.ReleaseUncertain
        }
    }

    private fun finishReleasedChat(expected: ActiveChat) {
        expected.cancelCollectors()
        runCatching { expected.port.close() }
        synchronized(lock) { if (active === expected) active = null }
        mutableState.value = NanoKvmPicoClawChatConnectionState.Closed
    }

    private fun isCurrent(expected: ActiveChat): Boolean =
        synchronized(lock) { active === expected }

    private fun hasCurrentBinding(): Boolean = currentBinding() == binding

    private class ActiveChat(val port: NanoKvmPicoClawChatPort) {
        var stateJob: Job? = null
        var lockJob: Job? = null
        var eventJob: Job? = null
        var releaseAttempted = false

        fun cancelCollectors() {
            stateJob?.cancel()
            lockJob?.cancel()
            eventJob?.cancel()
        }
    }
}

/**
 * Session-bound PicoClaw feature domain.
 *
 * Construction is entirely local. [enterFeature] is the explicit feature-entry boundary and the
 * first method permitted to call PicoClaw runtime status, whose appliance handler can itself start
 * a probe loop. All state-changing methods perform one dispatch at most and reconcile using a
 * separate authoritative read; none reconnect or replay.
 */
internal class NanoKvmPicoClawFeatureGateway internal constructor(
    private val port: NanoKvmPicoClawPort,
    val binding: NanoKvmSessionBinding,
    private val currentBinding: () -> NanoKvmSessionBinding?,
    scope: CoroutineScope,
) : AutoCloseable {
    private val operationMutex = Mutex()
    private val ownerIdentity = Any()
    private var entered = false
    private var disposed = false
    private var latestRuntime: NanoKvmPicoClawRuntimeSnapshot? = null
    private var latestHistory: NanoKvmPicoClawHistoryCatalogSnapshot? = null

    val chat = NanoKvmPicoClawChatOwner(
        port = port,
        binding = binding,
        currentBinding = currentBinding,
        scope = scope,
        ownerIdentity = ownerIdentity,
    )

    /** The first network call for this surface; callers must invoke it only from explicit entry. */
    suspend fun enterFeature(): NanoKvmPicoClawReadResult<NanoKvmPicoClawRuntimeSnapshot> =
        operationMutex.withLock {
            if (disposed) return@withLock failed(
                NanoKvmPicoClawOperation.FEATURE_ENTRY,
                NanoKvmPicoClawError.Kind.SESSION_CHANGED,
            )
            if (currentBinding() != binding) return@withLock failed(
                NanoKvmPicoClawOperation.FEATURE_ENTRY,
                NanoKvmPicoClawError.Kind.SESSION_CHANGED,
            )
            val result = readRuntimeLocked(
                operation = NanoKvmPicoClawOperation.FEATURE_ENTRY,
                requireEntry = false,
            )
            if (result is NanoKvmPicoClawReadResult.Success) entered = true
            result
        }

    suspend fun refreshRuntime(): NanoKvmPicoClawReadResult<NanoKvmPicoClawRuntimeSnapshot> =
        operationMutex.withLock {
            readRuntimeLocked(NanoKvmPicoClawOperation.STATUS_REFRESH, requireEntry = true)
        }

    fun recordUninstallConsentAfterWarning(): NanoKvmPicoClawUninstallConsent? =
        if (canApprove()) NanoKvmPicoClawUninstallConsent(ownerIdentity, binding) else null

    fun recordBroadControlConsentAfterDisclosure(): NanoKvmPicoClawControlConsent? =
        if (canApprove()) NanoKvmPicoClawControlConsent(ownerIdentity, binding) else null

    fun recordHistoryDeletionConsent(
        catalog: NanoKvmPicoClawHistoryCatalogSnapshot,
        item: NanoKvmPicoClawHistoryItem,
    ): NanoKvmPicoClawHistoryDeletionConsent? {
        if (!canApprove() || !validHistoryHandle(catalog, item)) return null
        return NanoKvmPicoClawHistoryDeletionConsent(ownerIdentity, catalog, item)
    }

    suspend fun installRuntime(): NanoKvmPicoClawMutationResult<NanoKvmPicoClawRuntimeSnapshot> =
        operationMutex.withLock {
            mutateRuntimeLocked(
                operation = NanoKvmPicoClawOperation.RUNTIME_INSTALL,
                desired = { it.installed },
                dispatch = port::installRuntime,
            )
        }

    suspend fun uninstallRuntime(
        consent: NanoKvmPicoClawUninstallConsent?,
    ): NanoKvmPicoClawMutationResult<NanoKvmPicoClawRuntimeSnapshot> =
        operationMutex.withLock {
            if (consent?.consume(ownerIdentity, binding) != true) return@withLock rejectedMutation(
                NanoKvmPicoClawOperation.RUNTIME_UNINSTALL,
                NanoKvmPicoClawError.Kind.APPROVAL_REQUIRED,
            )
            mutateRuntimeLocked(
                operation = NanoKvmPicoClawOperation.RUNTIME_UNINSTALL,
                desired = { !it.installed },
                dispatch = port::uninstallRuntime,
            )
        }

    suspend fun startRuntime(): NanoKvmPicoClawMutationResult<NanoKvmPicoClawRuntimeSnapshot> =
        operationMutex.withLock {
            mutateRuntimeLocked(
                operation = NanoKvmPicoClawOperation.RUNTIME_START,
                desired = { it.ready && it.phase == NanoKvmPicoClawUiRuntimePhase.READY },
                dispatch = port::startRuntime,
            )
        }

    suspend fun stopRuntime(): NanoKvmPicoClawMutationResult<NanoKvmPicoClawRuntimeSnapshot> =
        operationMutex.withLock {
            mutateRuntimeLocked(
                operation = NanoKvmPicoClawOperation.RUNTIME_STOP,
                desired = { !it.ready && it.phase == NanoKvmPicoClawUiRuntimePhase.STOPPED },
                dispatch = port::stopRuntime,
            )
        }

    suspend fun setAgentProfile(
        profile: NanoKvmPicoClawAgentProfile,
    ): NanoKvmPicoClawMutationResult<NanoKvmPicoClawRuntimeSnapshot> =
        operationMutex.withLock {
            mutateRuntimeLocked(
                operation = NanoKvmPicoClawOperation.AGENT_PROFILE,
                desired = { it.agentProfile == profile },
                dispatch = { port.setAgentProfile(profile) },
            )
        }

    suspend fun updateModel(
        update: NanoKvmPicoClawModelUpdate,
    ): NanoKvmPicoClawMutationResult<NanoKvmPicoClawRuntimeSnapshot> = update.consume { apiKey ->
        operationMutex.withLock {
            mutateRuntimeLocked(
                operation = NanoKvmPicoClawOperation.MODEL_CONFIGURATION,
                desired = { it.modelConfigured && it.modelName == update.model },
                dispatch = { port.updateModel(update.model, update.apiBase, apiKey) },
            )
        }
    }

    suspend fun refreshHistories():
        NanoKvmPicoClawReadResult<NanoKvmPicoClawHistoryCatalogSnapshot> =
        operationMutex.withLock { refreshHistoriesLocked() }

    suspend fun historyDetail(
        catalog: NanoKvmPicoClawHistoryCatalogSnapshot,
        item: NanoKvmPicoClawHistoryItem,
    ): NanoKvmPicoClawReadResult<NanoKvmPicoClawHistoryDetailSnapshot> =
        operationMutex.withLock {
            requireEntered(NanoKvmPicoClawOperation.HISTORY_DETAIL)?.let {
                return@withLock NanoKvmPicoClawReadResult.Failure(it)
            }
            if (!validHistoryHandle(catalog, item)) return@withLock failed(
                NanoKvmPicoClawOperation.HISTORY_DETAIL,
                NanoKvmPicoClawError.Kind.FOREIGN_OR_STALE_STATE,
            )
            if (currentBinding() != binding) return@withLock failed(
                NanoKvmPicoClawOperation.HISTORY_DETAIL,
                NanoKvmPicoClawError.Kind.SESSION_CHANGED,
            )
            try {
                val detail = port.history(catalog.portCatalog, item.portSession)
                if (currentBinding() != binding) return@withLock failed(
                    NanoKvmPicoClawOperation.HISTORY_DETAIL,
                    NanoKvmPicoClawError.Kind.SESSION_CHANGED,
                )
                NanoKvmPicoClawReadResult.Success(
                    NanoKvmPicoClawHistoryDetailSnapshot(
                        item = item,
                        messages = detail.messages.take(MAX_UI_HISTORY_MESSAGES).map { message ->
                            NanoKvmPicoClawHistoryUiMessage(
                                role = message.role,
                                content = message.content.takeUtf8(MAX_UI_HISTORY_MESSAGE_BYTES),
                            )
                        },
                        summary = detail.summary?.takeUtf8(MAX_UI_HISTORY_SUMMARY_BYTES),
                        createdAt = detail.createdAt,
                        updatedAt = detail.updatedAt,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NanoKvmPicoClawReadResult.Failure(
                    error.toPicoClawError(NanoKvmPicoClawOperation.HISTORY_DETAIL),
                )
            }
        }

    suspend fun deleteHistory(
        catalog: NanoKvmPicoClawHistoryCatalogSnapshot,
        item: NanoKvmPicoClawHistoryItem,
        consent: NanoKvmPicoClawHistoryDeletionConsent?,
    ): NanoKvmPicoClawHistoryDeleteResult = operationMutex.withLock {
        requireEntered(NanoKvmPicoClawOperation.HISTORY_DELETE)?.let {
            return@withLock NanoKvmPicoClawHistoryDeleteResult.Rejected(it)
        }
        if (!validHistoryHandle(catalog, item)) return@withLock historyDeleteRejected(
            NanoKvmPicoClawError.Kind.FOREIGN_OR_STALE_STATE,
        )
        if (consent?.consume(ownerIdentity, catalog, item) != true) return@withLock historyDeleteRejected(
            NanoKvmPicoClawError.Kind.APPROVAL_REQUIRED,
        )
        if (currentBinding() != binding) return@withLock historyDeleteRejected(
            NanoKvmPicoClawError.Kind.SESSION_CHANGED,
        )

        // Once dispatch begins, the UI snapshot is consumed even when the response is lost.
        latestHistory = null
        try {
            port.deleteHistory(catalog.portCatalog, item.portSession)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val dispatchError = error.toPicoClawError(NanoKvmPicoClawOperation.HISTORY_DELETE)
            if (currentBinding() != binding) return@withLock NanoKvmPicoClawHistoryDeleteResult
                .Indeterminate(
                    picoError(
                        NanoKvmPicoClawOperation.HISTORY_DELETE,
                        NanoKvmPicoClawError.Kind.SESSION_CHANGED,
                    ),
                    refreshError = null,
                )
            val presence = try {
                port.historyPresence(catalog.portCatalog, item.portSession)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                NanoKvmPicoClawHistoryPresence.UNKNOWN
            }
            return@withLock when (presence) {
                NanoKvmPicoClawHistoryPresence.ABSENT ->
                    NanoKvmPicoClawHistoryDeleteResult.Reconciled(
                        NanoKvmPicoClawHistoryDeleteObservation.ABSENT,
                        dispatchError,
                    )
                NanoKvmPicoClawHistoryPresence.PRESENT ->
                    NanoKvmPicoClawHistoryDeleteResult.Reconciled(
                        NanoKvmPicoClawHistoryDeleteObservation.PRESENT,
                        dispatchError,
                    )
                NanoKvmPicoClawHistoryPresence.UNKNOWN ->
                    NanoKvmPicoClawHistoryDeleteResult.Indeterminate(
                        dispatchError,
                        refreshError = null,
                    )
            }
        }

        return@withLock when (val refreshed = refreshHistoriesLocked()) {
            is NanoKvmPicoClawReadResult.Success ->
                NanoKvmPicoClawHistoryDeleteResult.Applied(refreshed.state)
            is NanoKvmPicoClawReadResult.Failure ->
                NanoKvmPicoClawHistoryDeleteResult.Accepted(refreshed.error)
        }
    }

    override fun close() {
        if (disposed) return
        disposed = true
        entered = false
        latestRuntime = null
        latestHistory = null
        chat.close()
    }

    private suspend fun mutateRuntimeLocked(
        operation: NanoKvmPicoClawOperation,
        desired: (NanoKvmPicoClawRuntimeSnapshot) -> Boolean,
        dispatch: suspend () -> Unit,
    ): NanoKvmPicoClawMutationResult<NanoKvmPicoClawRuntimeSnapshot> {
        requireEntered(operation)?.let { return NanoKvmPicoClawMutationResult.Rejected(it) }
        if (currentBinding() != binding) return rejectedMutation(
            operation,
            NanoKvmPicoClawError.Kind.SESSION_CHANGED,
        )
        val before = when (val read = readRuntimeLocked(operation, requireEntry = true)) {
            is NanoKvmPicoClawReadResult.Success -> read.state
            is NanoKvmPicoClawReadResult.Failure ->
                return NanoKvmPicoClawMutationResult.Rejected(read.error)
        }
        if (desired(before)) return NanoKvmPicoClawMutationResult.AlreadySatisfied(before)
        if (currentBinding() != binding) return rejectedMutation(
            operation,
            NanoKvmPicoClawError.Kind.SESSION_CHANGED,
        )

        try {
            dispatch()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val dispatchError = error.toPicoClawError(operation)
            if (error.isDefinitivePicoClawRejection()) {
                return NanoKvmPicoClawMutationResult.Rejected(dispatchError)
            }
            return reconcileRuntimeFailure(operation, dispatchError, desired)
        }

        if (currentBinding() != binding) return NanoKvmPicoClawMutationResult.Indeterminate(
            state = null,
            dispatchError = picoError(operation, NanoKvmPicoClawError.Kind.SESSION_CHANGED),
            refreshError = null,
        )
        return when (val refreshed = readRuntimeLocked(operation, requireEntry = true)) {
            is NanoKvmPicoClawReadResult.Success -> if (desired(refreshed.state)) {
                NanoKvmPicoClawMutationResult.Applied(refreshed.state)
            } else {
                NanoKvmPicoClawMutationResult.Accepted(refreshed.state, refreshError = null)
            }
            is NanoKvmPicoClawReadResult.Failure ->
                NanoKvmPicoClawMutationResult.Accepted(null, refreshed.error)
        }
    }

    private suspend fun reconcileRuntimeFailure(
        operation: NanoKvmPicoClawOperation,
        dispatchError: NanoKvmPicoClawError,
        desired: (NanoKvmPicoClawRuntimeSnapshot) -> Boolean,
    ): NanoKvmPicoClawMutationResult<NanoKvmPicoClawRuntimeSnapshot> {
        if (currentBinding() != binding) return NanoKvmPicoClawMutationResult.Indeterminate(
            null,
            picoError(operation, NanoKvmPicoClawError.Kind.SESSION_CHANGED),
            null,
        )
        return when (val refreshed = readRuntimeLocked(operation, requireEntry = true)) {
            is NanoKvmPicoClawReadResult.Success -> NanoKvmPicoClawMutationResult.Reconciled(
                state = refreshed.state,
                observation = if (desired(refreshed.state)) {
                    NanoKvmPicoClawRuntimeObservation.DESIRED_STATE
                } else {
                    NanoKvmPicoClawRuntimeObservation.OTHER_STATE
                },
                dispatchError = dispatchError,
            )
            is NanoKvmPicoClawReadResult.Failure ->
                NanoKvmPicoClawMutationResult.Indeterminate(
                    state = null,
                    dispatchError = dispatchError,
                    refreshError = refreshed.error,
                )
        }
    }

    private suspend fun readRuntimeLocked(
        operation: NanoKvmPicoClawOperation,
        requireEntry: Boolean,
    ): NanoKvmPicoClawReadResult<NanoKvmPicoClawRuntimeSnapshot> {
        if (requireEntry) requireEntered(operation)?.let {
            return NanoKvmPicoClawReadResult.Failure(it)
        }
        if (disposed || currentBinding() != binding) return failed(
            operation,
            NanoKvmPicoClawError.Kind.SESSION_CHANGED,
        )
        return try {
            val status = port.runtimeStatus()
            if (currentBinding() != binding) return failed(
                operation,
                NanoKvmPicoClawError.Kind.SESSION_CHANGED,
            )
            val snapshot = status.toUiSnapshot(binding)
            latestRuntime = snapshot
            NanoKvmPicoClawReadResult.Success(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NanoKvmPicoClawReadResult.Failure(error.toPicoClawError(operation))
        }
    }

    private suspend fun refreshHistoriesLocked():
        NanoKvmPicoClawReadResult<NanoKvmPicoClawHistoryCatalogSnapshot> {
        requireEntered(NanoKvmPicoClawOperation.HISTORY_LIST)?.let {
            return NanoKvmPicoClawReadResult.Failure(it)
        }
        if (currentBinding() != binding) return failed(
            NanoKvmPicoClawOperation.HISTORY_LIST,
            NanoKvmPicoClawError.Kind.SESSION_CHANGED,
        )
        return try {
            val portCatalog = port.histories(MAX_UI_HISTORY_ITEMS)
            if (currentBinding() != binding) return failed(
                NanoKvmPicoClawOperation.HISTORY_LIST,
                NanoKvmPicoClawError.Kind.SESSION_CHANGED,
            )
            val snapshot = NanoKvmPicoClawHistoryCatalogSnapshot(
                items = portCatalog.sessions.take(MAX_UI_HISTORY_ITEMS).map { session ->
                    NanoKvmPicoClawHistoryItem(
                        title = session.title.takeUtf8(MAX_UI_HISTORY_TITLE_BYTES),
                        preview = session.preview.takeUtf8(MAX_UI_HISTORY_PREVIEW_BYTES),
                        messageCount = session.messageCount.coerceIn(0, MAX_UI_HISTORY_MESSAGE_COUNT),
                        createdAt = session.createdAt,
                        updatedAt = session.updatedAt,
                        binding = binding,
                        portSession = session,
                    )
                },
                binding = binding,
                portCatalog = portCatalog,
            )
            latestHistory = snapshot
            NanoKvmPicoClawReadResult.Success(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NanoKvmPicoClawReadResult.Failure(
                error.toPicoClawError(NanoKvmPicoClawOperation.HISTORY_LIST),
            )
        }
    }

    private fun validHistoryHandle(
        catalog: NanoKvmPicoClawHistoryCatalogSnapshot,
        item: NanoKvmPicoClawHistoryItem,
    ): Boolean =
        catalog === latestHistory && catalog.binding == binding && item.binding == binding &&
            catalog.items.any { it === item }

    private fun requireEntered(operation: NanoKvmPicoClawOperation): NanoKvmPicoClawError? =
        when {
            disposed || currentBinding() != binding ->
                picoError(operation, NanoKvmPicoClawError.Kind.SESSION_CHANGED)
            !entered -> picoError(operation, NanoKvmPicoClawError.Kind.FEATURE_ENTRY_REQUIRED)
            else -> null
        }

    private fun canApprove(): Boolean = !disposed && entered && currentBinding() == binding

    private fun historyDeleteRejected(kind: NanoKvmPicoClawError.Kind) =
        NanoKvmPicoClawHistoryDeleteResult.Rejected(
            picoError(NanoKvmPicoClawOperation.HISTORY_DELETE, kind),
        )
}

private fun NanoKvmPicoClawRuntimeStatus.toUiSnapshot(
    binding: NanoKvmSessionBinding,
) = NanoKvmPicoClawRuntimeSnapshot(
    ready = ready,
    installed = installed,
    installing = installing,
    installProgress = installProgress,
    installStage = installStage?.takeUtf8(MAX_UI_STATUS_TEXT_BYTES),
    agentProfile = agentProfile,
    modelConfigured = modelConfigured,
    modelName = modelName?.takeUtf8(MAX_UI_MODEL_NAME_BYTES),
    phase = phase.toUiPhase(),
    hasConfigurationError = !configError.isNullOrEmpty(),
    hasRuntimeError = !lastError.isNullOrEmpty(),
    checkedAt = checkedAt,
    manualInputLocked = manualHidLocked,
    binding = binding,
)

private fun NanoKvmPicoClawRuntimePhase.toUiPhase(): NanoKvmPicoClawUiRuntimePhase = when (this) {
    NanoKvmPicoClawRuntimePhase.Checking -> NanoKvmPicoClawUiRuntimePhase.CHECKING
    NanoKvmPicoClawRuntimePhase.Installing -> NanoKvmPicoClawUiRuntimePhase.INSTALLING
    NanoKvmPicoClawRuntimePhase.Installed -> NanoKvmPicoClawUiRuntimePhase.INSTALLED
    NanoKvmPicoClawRuntimePhase.Ready -> NanoKvmPicoClawUiRuntimePhase.READY
    NanoKvmPicoClawRuntimePhase.Stopped -> NanoKvmPicoClawUiRuntimePhase.STOPPED
    NanoKvmPicoClawRuntimePhase.NotInstalled -> NanoKvmPicoClawUiRuntimePhase.NOT_INSTALLED
    NanoKvmPicoClawRuntimePhase.ModelNotConfigured ->
        NanoKvmPicoClawUiRuntimePhase.MODEL_NOT_CONFIGURED
    NanoKvmPicoClawRuntimePhase.ConfigError -> NanoKvmPicoClawUiRuntimePhase.CONFIG_ERROR
    NanoKvmPicoClawRuntimePhase.Unavailable -> NanoKvmPicoClawUiRuntimePhase.UNAVAILABLE
    NanoKvmPicoClawRuntimePhase.Error -> NanoKvmPicoClawUiRuntimePhase.ERROR
    is NanoKvmPicoClawRuntimePhase.Other -> NanoKvmPicoClawUiRuntimePhase.OTHER
}

private fun Throwable.isDefinitivePicoClawRejection(): Boolean =
    this is AuthenticationExpiredException || this is ApiResponseException ||
        this is NanoKvmPicoClawApiException || this is IllegalArgumentException

private fun Throwable.toPicoClawError(operation: NanoKvmPicoClawOperation):
    NanoKvmPicoClawError = picoError(
    operation,
    when (this) {
        is AuthenticationExpiredException -> NanoKvmPicoClawError.Kind.AUTHENTICATION_EXPIRED
        is IllegalArgumentException -> NanoKvmPicoClawError.Kind.INVALID_REQUEST
        is ApiResponseException, is NanoKvmPicoClawApiException ->
            NanoKvmPicoClawError.Kind.SERVER_REJECTED
        is InvalidApiResponseException -> NanoKvmPicoClawError.Kind.INVALID_RESPONSE
        is IOException -> NanoKvmPicoClawError.Kind.CONNECTION
        is HttpResponseException -> NanoKvmPicoClawError.Kind.SERVER_REJECTED
        else -> NanoKvmPicoClawError.Kind.UNEXPECTED
    },
)

private fun picoError(
    operation: NanoKvmPicoClawOperation,
    kind: NanoKvmPicoClawError.Kind,
) = NanoKvmPicoClawError(operation, kind)

private fun failed(
    operation: NanoKvmPicoClawOperation,
    kind: NanoKvmPicoClawError.Kind,
) = NanoKvmPicoClawReadResult.Failure(picoError(operation, kind))

private fun rejectedMutation(
    operation: NanoKvmPicoClawOperation,
    kind: NanoKvmPicoClawError.Kind,
) = NanoKvmPicoClawMutationResult.Rejected(picoError(operation, kind))

private fun rejected(
    operation: NanoKvmPicoClawOperation,
    kind: NanoKvmPicoClawError.Kind,
) = NanoKvmPicoClawChatActionResult.Rejected(picoError(operation, kind))

private fun String.utf8Size(): Int = encodeToByteArray().size

private fun String.takeUtf8(maxBytes: Int): String {
    if (utf8Size() <= maxBytes) return this
    val result = StringBuilder(minOf(length, maxBytes))
    var bytes = 0
    var index = 0
    while (index < length) {
        val first = this[index]
        val charCount: Int
        val encodedBytes: Int
        if (first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
            charCount = 2
            encodedBytes = 4
        } else {
            charCount = 1
            encodedBytes = when {
                first.code < 0x80 -> 1
                first.code < 0x800 -> 2
                else -> 3
            }
        }
        if (bytes + encodedBytes > maxBytes) break
        result.append(first)
        if (charCount == 2) result.append(this[index + 1])
        bytes += encodedBytes
        index += charCount
    }
    return result.toString()
}

private const val MAX_UI_MODEL_NAME_BYTES = 256
private const val MAX_UI_PROVIDER_KEY_CHARS = 4_096
private const val MAX_UI_STATUS_TEXT_BYTES = 256
private const val MAX_UI_HISTORY_ITEMS = 50
private const val MAX_UI_HISTORY_TITLE_BYTES = 256
private const val MAX_UI_HISTORY_PREVIEW_BYTES = 512
private const val MAX_UI_HISTORY_MESSAGES = 256
private const val MAX_UI_HISTORY_MESSAGE_COUNT = 2_048
private const val MAX_UI_HISTORY_MESSAGE_BYTES = 32 * 1_024
private const val MAX_UI_HISTORY_SUMMARY_BYTES = 8 * 1_024
private const val MAX_UI_CHAT_EVENT_TEXT_BYTES = 32 * 1_024
private const val MAX_UI_CHAT_IMAGE_BYTES = 768 * 1_024
private const val MAX_UI_TOOL_ACTION_BYTES = 256
private const val MAX_RETAINED_CHAT_EVENTS = 32

package org.nanokvm.mobile.runtime

import android.view.Surface
import java.net.URI
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.protocol.NanoKvmServerCapabilities

/**
 * App-facing boundary for the NanoKVM protocol and decoder modules.
 *
 * Implementations own networking, reconnects, HID report serialization and decoding. Calls from
 * gesture handlers are deliberately synchronous: implementations should enqueue and return rather
 * than perform I/O on the main thread. Release events must never be coalesced away.
 */
interface RemoteInputSink {
    fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton> = emptySet())
    fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton> = emptySet())
    fun mouseButton(button: MouseButton, pressed: Boolean)
    fun scrollWheel(steps: Int)
    fun scrollHorizontal(steps: Int)
    fun typeCommittedText(text: String, layout: KeyboardLayout = KeyboardLayout.Us)
    fun key(key: RemoteKey, pressed: Boolean)
    fun releaseAllInput()
}

interface VideoSurfaceSink {
    fun attachVideoSurface(surface: Surface, width: Int, height: Int)
    fun resizeVideoSurface(width: Int, height: Int)
    fun detachVideoSurface(surface: Surface)
}

/** Low-rate controls that are intrinsic to every connected console. */
interface ConsoleCoreControls {
    fun reconnect()
    /** Stops only the currently active automatic/manual reconnect run. */
    fun cancelReconnect()
    fun updateVideo(settings: VideoSettings)
    /** Explicit, replay-free write for the app-local MJPEG frame-detection preference. */
    fun setMjpegFrameDetectionEnabled(enabled: Boolean)
    fun resetHid()
    /** Dispatches a one-shot host control only to the exact destination the user approved. */
    fun power(destination: ApprovedCoreDestination, action: PowerAction)
    /** Types destination-bound, explicitly approved phone text through HID. */
    fun pasteText(request: ApprovedPasteRequest)
    /** Cancels an in-progress paced clipboard operation after its current atomic key pair. */
    fun cancelPaste()
}

/** Commands owned by the virtual-media and Wake-on-LAN surface. */
interface Phase3Controls {
    /** Opens/closes low-frequency Phase 3 polling; implementations must stop it in background. */
    fun setPhase3SurfaceVisible(visible: Boolean)
    fun refreshPhase3()
    fun mountPhase3Image(
        destination: ApprovedPhase3Destination,
        imageId: Long,
        mode: Phase3ImageMountMode,
    )
    fun restorePhase3PhysicalMedia(destination: ApprovedPhase3Destination)
    fun deletePhase3Image(destination: ApprovedPhase3Destination, imageId: Long)
    fun setPhase3HidMode(
        destination: ApprovedPhase3Destination,
        selection: Phase3HidModeSelection,
    )
    fun setPhase3NetworkEnabled(destination: ApprovedPhase3Destination, enabled: Boolean)
    fun setPhase3DiskEnabled(destination: ApprovedPhase3Destination, enabled: Boolean)
    fun startPhase3ImageTransfer(destination: ApprovedPhase3Destination, sourceUrl: String)
    fun sendPhase3WakeOnLan(destination: ApprovedPhase3Destination, macAddress: String)
    fun renamePhase3WakeOnLanTarget(
        destination: ApprovedPhase3Destination,
        targetId: Long,
        name: String,
    )
    fun deletePhase3WakeOnLanTarget(
        destination: ApprovedPhase3Destination,
        targetId: Long,
    )
}

/** Commands owned by the authenticated appliance-administration surface. */
interface AdministrationControls {
    /** Opens/closes privileged administration loading; hidden/background surfaces perform no I/O. */
    fun setAdministrationSurfaceVisible(visible: Boolean)
    fun refreshAdministration()
    fun setAdministrationPreviewUpdates(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    )
    fun startAdministrationOnlineUpdate(destination: ApprovedAdministrationDestination)
    fun rebootAdministrationAppliance(destination: ApprovedAdministrationDestination)
    fun setAdministrationOledSleep(
        destination: ApprovedAdministrationDestination,
        preset: AdministrationOledPreset,
    )
    fun setAdministrationSshEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    )
    fun setAdministrationHostname(
        destination: ApprovedAdministrationDestination,
        hostname: String,
    )
    fun setAdministrationMdnsEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    )
    fun setAdministrationWebTitle(
        destination: ApprovedAdministrationDestination,
        title: String,
    )
    fun resetAdministrationWebTitle(destination: ApprovedAdministrationDestination)
    fun setAdministrationManualDns(
        destination: ApprovedAdministrationDestination,
        servers: List<String>,
    )
    fun setAdministrationDhcpDns(destination: ApprovedAdministrationDestination)
    /** Ownership of [password] transfers to the sink, which clears every terminal path. */
    fun connectAdministrationWifi(
        destination: ApprovedAdministrationDestination,
        ssid: String,
        password: CharArray,
    )
    fun disconnectAdministrationWifi(destination: ApprovedAdministrationDestination)
    fun executeAdministrationTailscale(
        destination: ApprovedAdministrationDestination,
        command: AdministrationTailscaleCommand,
    )
    /** Acknowledges only the exact generation-bound request that Android opened successfully. */
    fun acknowledgeAdministrationNavigationOpened(
        destination: ApprovedAdministrationDestination,
        requestId: Long,
    )
    fun setAdministrationHdmiEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    )
    fun resetAdministrationHdmi(destination: ApprovedAdministrationDestination)
    fun setAdministrationMouseJiggler(
        destination: ApprovedAdministrationDestination,
        selection: AdministrationMouseJigglerSelection,
    )
    fun setAdministrationMemoryLimitEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    )
    fun setAdministrationSwapSize(
        destination: ApprovedAdministrationDestination,
        preset: AdministrationSwapPreset,
    )
    /** Enables appliance TLS once. Deliberately no disable operation is exposed. */
    fun enableAdministrationTls(destination: ApprovedAdministrationDestination)
}

/** State and commands owned by the operator terminal, serial, and script tools. */
interface OperatorControls {
    val operatorState: StateFlow<OperatorUiState>
    /** Replay-free, memory-only terminal and serial output. */
    val operatorOutput: SharedFlow<OperatorEphemeralOutput>
    fun setOperatorSurfaceVisible(visible: Boolean)
    fun refreshOperatorScripts()
    fun enterOperatorTerminal(destination: ApprovedOperatorDestination)
    fun closeOperatorTerminal(destination: ApprovedOperatorDestination)
    fun sendOperatorTerminalInput(
        destination: ApprovedOperatorDestination,
        text: String,
    )
    fun resizeOperatorTerminal(
        destination: ApprovedOperatorDestination,
        rows: Int,
        columns: Int,
    )
    fun startOperatorSerial(
        destination: ApprovedOperatorDestination,
        configuration: OperatorSerialConfiguration,
    )
    fun exitOperatorSerial(destination: ApprovedOperatorDestination)
    fun uploadOperatorScript(
        destination: ApprovedOperatorDestination,
        request: OperatorScriptUploadRequest,
    )
    fun runOperatorScript(
        destination: ApprovedOperatorDestination,
        scriptId: Long,
        mode: OperatorScriptRunMode,
    )
    fun deleteOperatorScript(
        destination: ApprovedOperatorDestination,
        scriptId: Long,
    )
}

/** State and commands owned by the opt-in PicoClaw surface. */
interface PicoClawControls {
    /** Construction and dialog discovery do not probe the appliance. */
    val picoClawState: StateFlow<PicoClawUiState>
    /** The surface may be discovered without entering/probing PicoClaw. */
    fun setPicoClawSurfaceVisible(visible: Boolean)
    /** Called only after the broad filesystem/exec/cron/MCP/HID warning is confirmed. */
    fun enterPicoClaw(destination: ApprovedPicoClawDestination)
    fun refreshPicoClaw(destination: ApprovedPicoClawDestination)
    fun installPicoClawRuntime(destination: ApprovedPicoClawDestination)
    fun startPicoClawRuntime(destination: ApprovedPicoClawDestination)
    fun stopPicoClawRuntime(destination: ApprovedPicoClawDestination)
    fun uninstallPicoClawRuntime(destination: ApprovedPicoClawDestination)
    fun setPicoClawProfile(
        destination: ApprovedPicoClawDestination,
        profile: PicoClawProfile,
    )
    fun configurePicoClawModel(
        destination: ApprovedPicoClawDestination,
        request: PicoClawModelConfigurationRequest,
    )
    fun refreshPicoClawHistories(destination: ApprovedPicoClawDestination)
    fun loadPicoClawHistory(destination: ApprovedPicoClawDestination, historyId: Long)
    fun deletePicoClawHistory(destination: ApprovedPicoClawDestination, historyId: Long)
    fun openPicoClawChat(destination: ApprovedPicoClawDestination)
    fun sendPicoClawChatMessage(destination: ApprovedPicoClawDestination, content: String)
    fun cancelPicoClawChat(destination: ApprovedPicoClawDestination)
    fun closeAndReleasePicoClaw(destination: ApprovedPicoClawDestination)
}

/** App-internal owner for the feature-contained automation editor. */
internal interface NanoKvmAutomationFeatureOwner {
    fun setAutomationSurfaceVisible(visible: Boolean)
    fun currentAutomationGateway(): NanoKvmAutomationGateway?
}

/**
 * App-internal owner for the generation-bound, one-shot offline updater. The gateway remains
 * outside the public command interface and must never become durable console state.
 */
internal interface NanoKvmOfflineUpdateFeatureOwner {
    fun setOfflineUpdateSurfaceVisible(visible: Boolean)
    fun currentOfflineUpdateGateway(): NanoKvmOfflineUpdateGateway?
}

/**
 * App-internal factory for one exact, generation-bound password-change coordinator. Keeping this
 * out of [ConsoleCoreControls] prevents privileged credential mutation from becoming a replayable
 * fire-and-forget UI command.
 */
internal interface NanoKvmPasswordChangeFeatureOwner {
    fun createPasswordChangeCoordinator(
        request: NanoKvmPasswordChangeRequest,
    ): NanoKvmPasswordChangeCoordinator?
}

/**
 * Concrete, typed feature inventory for one console backend.
 *
 * Optional entries represent genuinely unavailable features (and keep focused tests from having
 * to fabricate unrelated behavior). Production commands never fall through to default no-ops.
 */
class ConsoleFeatureBundle internal constructor(
    val core: ConsoleCoreControls,
    val phase3: Phase3Controls? = null,
    val administration: AdministrationControls? = null,
    val operator: OperatorControls? = null,
    val picoClaw: PicoClawControls? = null,
    internal val automation: NanoKvmAutomationFeatureOwner? = null,
    internal val offlineUpdate: NanoKvmOfflineUpdateFeatureOwner? = null,
    internal val passwordChange: NanoKvmPasswordChangeFeatureOwner? = null,
)

/** Exact authenticated destination shown in the final core host-control confirmation. */
data class ApprovedCoreDestination(
    val profileId: String,
    val authority: String,
    val sessionGeneration: Long,
) {
    init {
        require(profileId.isNotBlank()) { "Profile ID must not be blank" }
        require(authority.isNotBlank()) { "Destination authority must not be blank" }
        require(sessionGeneration >= 0L) { "Session generation must not be negative" }
    }

    override fun toString(): String =
        "ApprovedCoreDestination(profileId=<redacted>, authority=<redacted>, " +
            "sessionGeneration=$sessionGeneration)"
}

internal fun ApprovedCoreDestination.matches(binding: NanoKvmSessionBinding): Boolean =
    profileId == binding.profileId &&
        authority == binding.authority &&
        sessionGeneration == binding.sessionGeneration

data class ApprovedPicoClawDestination(
    val profileId: String,
    val authority: String,
    val sessionGeneration: Long,
) {
    init {
        require(profileId.isNotBlank()) { "Profile ID must not be blank" }
        require(authority.isNotBlank()) { "Destination authority must not be blank" }
        require(sessionGeneration >= 0L) { "Session generation must not be negative" }
    }

    override fun toString(): String =
        "ApprovedPicoClawDestination(profileId=<redacted>, authority=<redacted>, " +
            "sessionGeneration=$sessionGeneration)"
}

internal fun ApprovedPicoClawDestination.matches(binding: NanoKvmSessionBinding): Boolean =
    profileId == binding.profileId && authority == binding.authority &&
        sessionGeneration == binding.sessionGeneration

internal fun picoClawSurfaceCanLoad(
    visible: Boolean,
    foreground: Boolean,
    currentBinding: NanoKvmSessionBinding?,
    installedBinding: NanoKvmSessionBinding?,
): Boolean = visible && foreground && currentBinding != null && currentBinding == installedBinding

enum class PicoClawSupport { Unknown, Supported, Unsupported }
enum class PicoClawRuntimeUiPhase {
    NotEntered, Checking, Installing, Installed, Ready, Stopped, NotInstalled,
    ModelNotConfigured, ConfigError, Unavailable, Error, Other,
}
enum class PicoClawProfile { Default, Kvm }
enum class PicoClawChatUiPhase { Inactive, Connecting, Open, Closing, Closed, Failed }
enum class PicoClawManualInputUiState { Released, Acquiring, Held, HeldByOther, Releasing, Uncertain }
enum class PicoClawNoticeKind { Information, Applied, Reconciled, Indeterminate, Rejected }
enum class PicoClawMessageRole { User, Assistant, Observation, Tool }

private const val MAX_PICOCLAW_MESSAGE_TEXT_BYTES = 32 * 1_024
private const val MAX_PICOCLAW_TOOL_ACTION_BYTES = 256

class PicoClawHistoryUiState(
    val id: Long,
    val title: String,
    val preview: String,
    val messageCount: Int,
) {
    override fun toString(): String =
        "PicoClawHistoryUiState(id=$id, messageCount=$messageCount, content=<redacted>)"
}

/**
 * Closed chat-display content that keeps appliance text opaque while representing app copy
 * semantically. Text variants enforce the ingress limits at this state boundary and redact their
 * payloads from diagnostics.
 */
sealed interface PicoClawMessageContent {
    val role: PicoClawMessageRole

    class ApplianceText(
        override val role: PicoClawMessageRole,
        val value: String,
    ) : PicoClawMessageContent {
        val utf8ByteCount: Int = requireNotNull(
            value.utf8SizeAtMost(MAX_PICOCLAW_MESSAGE_TEXT_BYTES),
        ) { "PicoClaw message text exceeds the app display limit" }

        override fun toString(): String =
            "PicoClawMessageContent.ApplianceText(role=$role, " +
                "utf8ByteCount=$utf8ByteCount, value=<redacted>)"
    }

    data object ScreenObservationCaptured : PicoClawMessageContent {
        override val role: PicoClawMessageRole = PicoClawMessageRole.Observation
    }

    class ToolAction(val action: String) : PicoClawMessageContent {
        override val role: PicoClawMessageRole = PicoClawMessageRole.Tool
        val utf8ByteCount: Int = requireNotNull(
            action.utf8SizeAtMost(MAX_PICOCLAW_TOOL_ACTION_BYTES),
        ) { "PicoClaw tool action exceeds the app display limit" }

        override fun toString(): String =
            "PicoClawMessageContent.ToolAction(utf8ByteCount=$utf8ByteCount, " +
                "action=<redacted>)"
    }
}

class PicoClawMessageUiState(val content: PicoClawMessageContent) {
    val role: PicoClawMessageRole
        get() = content.role

    override fun toString(): String =
        "PicoClawMessageUiState(role=$role, content=<redacted>)"
}

data class PicoClawUiState(
    val support: PicoClawSupport = PicoClawSupport.Unknown,
    val entered: Boolean = false,
    val loading: Boolean = false,
    val operationInProgress: Boolean = false,
    val installed: Boolean = false,
    val ready: Boolean = false,
    val installing: Boolean = false,
    val installProgress: Int? = null,
    val runtimePhase: PicoClawRuntimeUiPhase = PicoClawRuntimeUiPhase.NotEntered,
    val profile: PicoClawProfile? = null,
    val modelConfigured: Boolean = false,
    val modelName: String? = null,
    val historiesLoaded: Boolean = false,
    val histories: List<PicoClawHistoryUiState> = emptyList(),
    val selectedHistoryTitle: String? = null,
    val selectedHistoryMessages: List<PicoClawMessageUiState> = emptyList(),
    val chatPhase: PicoClawChatUiPhase = PicoClawChatUiPhase.Inactive,
    val manualInput: PicoClawManualInputUiState = PicoClawManualInputUiState.Released,
    val chatMessages: List<PicoClawMessageUiState> = emptyList(),
    val notice: PicoClawNotice? = null,
) {
    val manualInputBlockedOrUncertain: Boolean
        get() = manualInput != PicoClawManualInputUiState.Released

    override fun toString(): String =
        "PicoClawUiState(support=$support, entered=$entered, loading=$loading, " +
            "operationInProgress=$operationInProgress, installed=$installed, ready=$ready, " +
            "runtimePhase=$runtimePhase, modelConfigured=$modelConfigured, " +
            "histories=${histories.size}, chatPhase=$chatPhase, manualInput=$manualInput, " +
            "displayContent=<redacted>)"
}

/** Ownership of [apiKey] transfers to the backend, which clears every terminal path. */
class PicoClawModelConfigurationRequest(
    val model: String,
    val apiBase: String,
    val apiKey: CharArray,
) {
    fun clear() = apiKey.fill('\u0000')

    override fun toString(): String =
        "PicoClawModelConfigurationRequest(model=<redacted>, apiBase=<redacted>, apiKey=<redacted>)"
}

data class ApprovedOperatorDestination(
    val profileId: String,
    val authority: String,
    val sessionGeneration: Long,
) {
    init {
        require(profileId.isNotBlank()) { "Profile ID must not be blank" }
        require(authority.isNotBlank()) { "Destination authority must not be blank" }
        require(sessionGeneration >= 0L) { "Session generation must not be negative" }
    }

    override fun toString(): String =
        "ApprovedOperatorDestination(profileId=<redacted>, authority=<redacted>, " +
            "sessionGeneration=$sessionGeneration)"
}

internal fun ApprovedOperatorDestination.matches(binding: NanoKvmSessionBinding): Boolean =
    profileId == binding.profileId &&
        authority == binding.authority &&
        sessionGeneration == binding.sessionGeneration

internal fun operatorSurfaceCanLoad(
    visible: Boolean,
    foreground: Boolean,
    currentBinding: NanoKvmSessionBinding?,
    installedBinding: NanoKvmSessionBinding?,
): Boolean = visible && foreground && currentBinding != null && currentBinding == installedBinding

enum class OperatorTerminalUiPhase { Inactive, Connecting, Connected, Closing, Failed }

enum class OperatorNoticeKind { Information, Applied, Reconciled, Indeterminate, Rejected }

data class OperatorScriptUiState(
    val id: Long,
    val displayName: String,
)

data class OperatorUiState(
    val available: Boolean = false,
    val loadingScripts: Boolean = false,
    val operationInProgress: Boolean = false,
    val terminalPhase: OperatorTerminalUiPhase = OperatorTerminalUiPhase.Inactive,
    val serialActive: Boolean = false,
    val scriptsLoaded: Boolean = false,
    val scripts: List<OperatorScriptUiState> = emptyList(),
    val notice: OperatorNotice? = null,
)

enum class OperatorOutputKind { Terminal, Script }

/** Ephemeral output value whose diagnostics redact content. It is delivered on a replay-free flow. */
class OperatorEphemeralOutput internal constructor(
    val kind: OperatorOutputKind,
    content: String,
) {
    private val retainedContent = content
    val utf8ByteCount: Int = requireNotNull(content.utf8SizeAtMost(MAX_OPERATOR_OUTPUT_BYTES)) {
        "Operator output exceeds the app display limit"
    }

    fun copyText(): String = retainedContent.toCharArray().concatToString()

    override fun toString(): String =
        "OperatorEphemeralOutput(kind=$kind, utf8ByteCount=$utf8ByteCount, content=<redacted>)"
}

enum class OperatorScriptRunMode { Foreground, Background }

enum class OperatorSerialPort(val devicePath: String) { TtyS1("/dev/ttyS1"), TtyS2("/dev/ttyS2") }
enum class OperatorSerialBaud(val bitsPerSecond: Int) {
    B9600(9_600), B19200(19_200), B38400(38_400), B57600(57_600),
    B115200(115_200), B230400(230_400), B460800(460_800), B921600(921_600),
}
enum class OperatorSerialParity { None, Even, Odd }
enum class OperatorSerialFlowControl { None, Software, Hardware }
enum class OperatorSerialDataBits(val count: Int) { Seven(7), Eight(8) }
enum class OperatorSerialStopBits(val count: Int) { One(1), Two(2) }

data class OperatorSerialConfiguration(
    val port: OperatorSerialPort = OperatorSerialPort.TtyS1,
    val baud: OperatorSerialBaud = OperatorSerialBaud.B115200,
    val parity: OperatorSerialParity = OperatorSerialParity.None,
    val flowControl: OperatorSerialFlowControl = OperatorSerialFlowControl.None,
    val dataBits: OperatorSerialDataBits = OperatorSerialDataBits.Eight,
    val stopBits: OperatorSerialStopBits = OperatorSerialStopBits.One,
)

/** Ownership of [content] transfers to the backend, which clears it on every terminal path. */
class OperatorScriptUploadRequest(
    val fileName: String,
    val content: ByteArray,
) {
    override fun toString(): String =
        "OperatorScriptUploadRequest(fileName=<redacted>, byteCount=${content.size}, content=<redacted>)"

    fun clear() = content.fill(0)
}

/**
 * Incremental, bounded storage for terminal and script output.
 *
 * Output arrives in many small events. Keeping pre-counted UTF-8 chunks avoids re-encoding the
 * entire retained history and allocating one temporary substring per code point whenever the
 * limit is reached. This owner is intentionally not thread-safe; the operator UI owns and mutates
 * it from its lifecycle-aware Main collector.
 */
internal class BoundedOperatorOutputBuffer(
    private val maximumUtf8Bytes: Int = MAX_OPERATOR_OUTPUT_BYTES,
) {
    private data class Chunk(
        val text: StringBuilder = StringBuilder(),
        var startCharIndex: Int = 0,
        var utf8Bytes: Int = 0,
    )

    private val chunks = java.util.ArrayDeque<Chunk>()
    private val targetChunkBytes = minOf(
        OPERATOR_OUTPUT_CHUNK_BYTES,
        maximumUtf8Bytes.coerceAtLeast(MAX_UTF8_SCALAR_BYTES),
    )
    private var retainedCharacters = 0
    private var retainedBytes = 0

    init {
        require(maximumUtf8Bytes > 0) { "Output limit must be positive" }
    }

    internal val retainedUtf8Bytes: Int
        get() = retainedBytes

    internal val retainedChunkCount: Int
        get() = chunks.size

    fun append(incoming: String) {
        if (incoming.isEmpty()) return
        var index = appendSurrogatePairAcrossEventBoundary(incoming)
        while (index < incoming.length) {
            val scalar = incoming.utf8ScalarInfoAt(index)
            val charCount = scalar ushr UTF8_SCALAR_CHAR_COUNT_SHIFT
            val byteCount = scalar and UTF8_SCALAR_BYTE_COUNT_MASK
            var tail = chunks.peekLast()
            if (
                tail == null ||
                tail.startCharIndex != 0 ||
                tail.utf8Bytes + byteCount > targetChunkBytes
            ) {
                tail = Chunk()
                chunks.addLast(tail)
            }
            tail.text.append(incoming, index, index + charCount)
            tail.utf8Bytes += byteCount
            retainedCharacters += charCount
            retainedBytes += byteCount
            index += charCount
        }
        trimToLimit()
    }

    fun snapshot(): String = buildString(retainedCharacters) {
        chunks.forEach { chunk ->
            append(chunk.text, chunk.startCharIndex, chunk.text.length)
        }
    }

    fun clear() {
        chunks.clear()
        retainedCharacters = 0
        retainedBytes = 0
    }

    private fun appendSurrogatePairAcrossEventBoundary(incoming: String): Int {
        if (!incoming.first().isLowSurrogate()) return 0
        val tail = chunks.peekLast() ?: return 0
        if (tail.startCharIndex >= tail.text.length || !tail.text.last().isHighSurrogate()) return 0

        tail.text.append(incoming.first())
        val addedBytes = MAX_UTF8_SCALAR_BYTES - INVALID_UTF16_CODE_UNIT_UTF8_BYTES
        tail.utf8Bytes += addedBytes
        retainedCharacters++
        retainedBytes += addedBytes
        trimToLimit()
        return 1
    }

    private fun trimToLimit() {
        while (retainedBytes > maximumUtf8Bytes) {
            val first = chunks.peekFirst() ?: return
            val excess = retainedBytes - maximumUtf8Bytes
            if (first.utf8Bytes <= excess) {
                retainedBytes -= first.utf8Bytes
                retainedCharacters -= first.text.length - first.startCharIndex
                chunks.removeFirst()
                continue
            }

            val previousStart = first.startCharIndex
            var index = previousStart
            var removedBytes = 0
            while (removedBytes < excess && index < first.text.length) {
                val scalar = first.text.utf8ScalarInfoAt(index)
                removedBytes += scalar and UTF8_SCALAR_BYTE_COUNT_MASK
                index += scalar ushr UTF8_SCALAR_CHAR_COUNT_SHIFT
            }
            first.startCharIndex = index
            first.utf8Bytes -= removedBytes
            retainedBytes -= removedBytes
            retainedCharacters -= index - previousStart
            if (first.startCharIndex == first.text.length) chunks.removeFirst()
        }
    }
}

/** Packs UTF-16 char count in the high byte and UTF-8 byte count in the low byte. */
private fun CharSequence.utf8ScalarInfoAt(index: Int): Int {
    val first = this[index]
    if (first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return (2 shl UTF8_SCALAR_CHAR_COUNT_SHIFT) or MAX_UTF8_SCALAR_BYTES
    }
    val byteCount = when {
        first.code <= 0x7f -> 1
        first.code <= 0x7ff -> 2
        first.isHighSurrogate() || first.isLowSurrogate() ->
            INVALID_UTF16_CODE_UNIT_UTF8_BYTES
        else -> 3
    }
    return (1 shl UTF8_SCALAR_CHAR_COUNT_SHIFT) or byteCount
}

const val MAX_OPERATOR_OUTPUT_BYTES: Int = 256 * 1024
private const val OPERATOR_OUTPUT_CHUNK_BYTES: Int = 4 * 1024
private const val MAX_UTF8_SCALAR_BYTES: Int = 4
private const val UTF8_SCALAR_CHAR_COUNT_SHIFT: Int = 8
private const val UTF8_SCALAR_BYTE_COUNT_MASK: Int = 0xff
private val INVALID_UTF16_CODE_UNIT_UTF8_BYTES: Int = "\uD800".encodeToByteArray().size

/** Exact authenticated destination shown in the final administration confirmation. */
data class ApprovedAdministrationDestination(
    val profileId: String,
    val authority: String,
    val sessionGeneration: Long,
) {
    init {
        require(profileId.isNotBlank()) { "Profile ID must not be blank" }
        require(authority.isNotBlank()) { "Destination authority must not be blank" }
        require(sessionGeneration >= 0L) { "Session generation must not be negative" }
    }

    override fun toString(): String =
        "ApprovedAdministrationDestination(profileId=<redacted>, authority=<redacted>, " +
            "sessionGeneration=$sessionGeneration)"
}

internal fun ApprovedAdministrationDestination.matches(binding: NanoKvmSessionBinding): Boolean =
    profileId == binding.profileId &&
        authority == binding.authority &&
        sessionGeneration == binding.sessionGeneration

internal fun administrationSurfaceCanLoad(
    visible: Boolean,
    foreground: Boolean,
    currentBinding: NanoKvmSessionBinding?,
    installedBinding: NanoKvmSessionBinding?,
): Boolean =
    visible && foreground && currentBinding != null && currentBinding == installedBinding

enum class AdministrationOledPreset(val seconds: Int) {
    Never(0),
    Seconds15(15),
    Seconds30(30),
    Minute1(60),
    Minutes3(180),
    Minutes5(300),
    Minutes10(600),
    Minutes30(1_800),
    Hour1(3_600),
}

data class AdministrationAccountUiState(
    val username: String,
    val passwordUpdated: Boolean,
)

data class AdministrationUpdateUiState(
    val currentVersion: String,
    val latestVersion: String?,
    val previewUpdatesEnabled: Boolean,
) {
    val updateAvailable: Boolean
        get() = latestVersion != null && latestVersion != currentVersion
}

data class AdministrationOledUiState(
    val exists: Boolean,
    val sleepSeconds: Int,
    val preset: AdministrationOledPreset?,
)

data class AdministrationDnsUiState(
    val mode: AdministrationDnsMode,
    val configuredServers: List<String>,
    val effectiveServers: List<String>,
    val dhcpServers: List<String>,
)

enum class AdministrationDnsMode { Dhcp, Manual, Other }

data class AdministrationWifiUiState(
    val supported: Boolean,
    val accessPointMode: Boolean,
    val connected: Boolean,
    val ssid: String?,
) {
    override fun toString(): String =
        "AdministrationWifiUiState(supported=$supported, accessPointMode=$accessPointMode, " +
            "connected=$connected, ssid=<redacted>)"
}

enum class AdministrationTailscaleSelection {
    NotInstalled,
    NotRunning,
    NotLoggedIn,
    Stopped,
    Running,
    Other,
}

enum class AdministrationTailscaleCommand {
    Install,
    Uninstall,
    Start,
    Stop,
    Restart,
    Up,
    Down,
    Login,
    Logout,
}

data class AdministrationTailscaleUiState(
    val selection: AdministrationTailscaleSelection,
    val reportedState: String? = null,
    val deviceName: String? = null,
    val ipv4: String? = null,
    val account: String? = null,
) {
    override fun toString(): String =
        "AdministrationTailscaleUiState(selection=$selection, identity=<redacted>)"
}

enum class AdministrationMouseJigglerSelection { Off, Relative, Absolute, Other }

data class AdministrationMouseJigglerUiState(
    val selection: AdministrationMouseJigglerSelection,
    /** Present only for a bounded future value, which remains visible and read-only. */
    val reportedMode: String? = null,
)

data class AdministrationMemoryLimitUiState(
    val enabled: Boolean,
    val limitMegabytes: Long,
    /** False for a bounded value outside the pinned 2.4.3 active or disabled presets. */
    val writable: Boolean,
)

enum class AdministrationSwapPreset(val megabytes: Long) {
    Disabled(0L),
    Mb64(64L),
    Mb128(128L),
    Mb256(256L),
    Mb512(512L),
}

data class AdministrationSwapUiState(
    val sizeMegabytes: Long,
    /** Null for a bounded future value, which remains visible and read-only. */
    val preset: AdministrationSwapPreset?,
)

enum class AdministrationNoticeKind { Information, Applied, Reconciled, Indeterminate, Rejected }

/**
 * Memory-only handoff for one official HTTPS administration page.
 *
 * The URL and destination identity deliberately have no public accessors and are redacted from
 * diagnostics. [requestId] lets the UI acknowledge only the request it actually opened, while the
 * destination and session generation prevent an acknowledgement crossing authenticated sessions.
 */
class PendingAdministrationHttpsNavigationRequest internal constructor(
    val requestId: Long,
    private val profileId: String,
    private val authority: String,
    val sessionGeneration: Long,
    private val value: String,
) {
    init {
        require(requestId > 0L) { "Navigation request ID must be positive" }
        require(profileId.isNotBlank()) { "Profile ID must not be blank" }
        require(authority.isNotBlank()) { "Destination authority must not be blank" }
        require(sessionGeneration >= 0L) { "Session generation must not be negative" }
        val parsed = runCatching { URI(value) }.getOrNull()
        require(parsed?.scheme.equals("https", ignoreCase = true) && !parsed?.host.isNullOrBlank()) {
            "External administration navigation must use HTTPS"
        }
    }

    fun open(opener: (String) -> Unit) = opener(value)

    internal fun matches(
        destination: ApprovedAdministrationDestination,
        expectedRequestId: Long,
    ): Boolean =
        requestId == expectedRequestId &&
            profileId == destination.profileId &&
            authority == destination.authority &&
            sessionGeneration == destination.sessionGeneration

    override fun toString(): String =
        "PendingAdministrationHttpsNavigationRequest(requestId=$requestId, " +
            "destination=<redacted>, sessionGeneration=$sessionGeneration, url=<redacted>)"
}

data class AdministrationUiState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val operationInProgress: Boolean = false,
    val account: AdministrationAccountUiState? = null,
    val updates: AdministrationUpdateUiState? = null,
    val oled: AdministrationOledUiState? = null,
    val sshEnabled: Boolean? = null,
    val hostname: String? = null,
    val mdnsEnabled: Boolean? = null,
    val webTitle: String? = null,
    val webTitleIsDefault: Boolean? = null,
    val dns: AdministrationDnsUiState? = null,
    val wifi: AdministrationWifiUiState? = null,
    val tailscale: AdministrationTailscaleUiState? = null,
    val hdmiEnabled: Boolean? = null,
    val mouseJiggler: AdministrationMouseJigglerUiState? = null,
    val memoryLimit: AdministrationMemoryLimitUiState? = null,
    val swap: AdministrationSwapUiState? = null,
    val notice: AdministrationNotice? = null,
    val pendingHttpsNavigation: PendingAdministrationHttpsNavigationRequest? = null,
)

internal fun AdministrationUiState.acknowledgeOpenedHttpsNavigation(
    destination: ApprovedAdministrationDestination,
    requestId: Long,
): AdministrationUiState {
    val pending = pendingHttpsNavigation
    if (pending == null || !pending.matches(destination, requestId)) return this
    return copy(
        notice = AdministrationNotice.Guidance(
            AdministrationNotice.GuidanceReason.TailscaleAuthorizationPageOpened,
        ),
        pendingHttpsNavigation = null,
    )
}

/** Exact destination the user saw before approving a low-frequency Phase 3 mutation. */
data class ApprovedPhase3Destination(
    val profileId: String,
    val authority: String,
    val sessionGeneration: Long,
) {
    init {
        require(profileId.isNotBlank()) { "Profile ID must not be blank" }
        require(authority.isNotBlank()) { "Destination authority must not be blank" }
        require(sessionGeneration >= 0L) { "Session generation must not be negative" }
    }

    override fun toString(): String =
        "ApprovedPhase3Destination(profileId=<redacted>, authority=<redacted>, " +
            "sessionGeneration=$sessionGeneration)"
}

internal fun ApprovedPhase3Destination.matches(binding: NanoKvmSessionBinding): Boolean =
    profileId == binding.profileId &&
        authority == binding.authority &&
        sessionGeneration == binding.sessionGeneration

enum class Phase3ImageMountMode { MassStorage, CdRom }

data class Phase3MediaImageUiState(
    val id: Long,
    val displayName: String,
    val mounted: Boolean,
)

enum class Phase3TransferPhase { Unknown, Idle, InProgress, Other }

enum class Phase3HidModeSelection { Normal, HidOnly, Other }

data class Phase3HidModeUiState(
    val selection: Phase3HidModeSelection,
    /** Bounded future value from the appliance; null for known modes. */
    val reportedMode: String? = null,
)

data class Phase3VirtualMediaUiState(
    val loaded: Boolean = false,
    val images: List<Phase3MediaImageUiState> = emptyList(),
    val mountedDisplayName: String? = null,
    val hasUnlistedMountedImage: Boolean = false,
    val cdRomEnabled: Boolean = false,
    val networkEnabled: Boolean? = null,
    /** MEDIA is reported by NanoKVM but has no supported 2.4.3 mutation route. */
    val mediaEnabled: Boolean? = null,
    val diskEnabled: Boolean? = null,
    val remoteTransferEnabled: Boolean? = null,
    val transferPhase: Phase3TransferPhase = Phase3TransferPhase.Unknown,
    val transferPercentage: Double? = null,
)

data class Phase3WakeOnLanTargetUiState(
    val id: Long,
    val macAddress: String,
    val name: String?,
)

data class Phase3FeatureUiState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val operationInProgress: Boolean = false,
    val hidMode: Phase3HidModeUiState? = null,
    val virtualMedia: Phase3VirtualMediaUiState = Phase3VirtualMediaUiState(),
    val wakeOnLanLoaded: Boolean = false,
    val wakeOnLanTargets: List<Phase3WakeOnLanTargetUiState> = emptyList(),
    /** Semantic read/action guidance belonging only to the virtual-media surface. */
    val virtualMediaNotice: Phase3Notice? = null,
    /** Semantic read/action guidance belonging only to the Wake-on-LAN surface. */
    val wakeOnLanNotice: Phase3Notice? = null,
    /** Presentation-independent guidance shared by the Phase 3 surfaces. */
    val notice: Phase3Notice? = null,
)

/**
 * Memory-only command produced by the clipboard confirmation UI.
 *
 * The backend must compare every destination field with its live authenticated session before it
 * releases input or sends any part of [content]. Diagnostics deliberately redact the text.
 */
class ApprovedPasteRequest(
    val profileId: String,
    val authority: String,
    val sessionGeneration: Long,
    val content: String,
    val keyboardLayout: KeyboardLayout,
) {
    init {
        require(profileId.isNotBlank()) { "Profile ID must not be blank" }
        require(authority.isNotBlank()) { "Destination authority must not be blank" }
        require(sessionGeneration >= 0) { "Session generation must not be negative" }
    }

    override fun toString(): String =
        "ApprovedPasteRequest(profileId=$profileId, authority=$authority, " +
            "sessionGeneration=$sessionGeneration, content=<redacted>, " +
            "keyboardLayout=$keyboardLayout)"
}

internal fun ApprovedPasteRequest.matchesDestination(
    profileId: String,
    authority: String,
    sessionGeneration: Long,
): Boolean =
    this.profileId == profileId &&
        this.authority == authority &&
        this.sessionGeneration == sessionGeneration

interface ConsoleBackend :
    AutoCloseable,
    RemoteInputSink,
    VideoSurfaceSink,
    ConsoleCoreControls {
    val session: StateFlow<BackendSession>
    val features: ConsoleFeatureBundle

    /** Performs a TLS-only trust gate before the caller unlocks or collects a password. */
    suspend fun preflightTrust(profile: HostProfile): TrustPreflightOutcome

    suspend fun connect(request: ConnectRequest): ConnectOutcome
    suspend fun disconnect()
    fun setForeground(isForeground: Boolean)

    /** Updates app-local intent only; settings collection must not perform appliance I/O. */
    fun setMjpegFrameDetectionPreference(enabled: Boolean)

    /** Releases transports and worker threads owned by the backend. */
    override fun close() {
        releaseAllInput()
    }

    /** Completes only after all backend-owned transports and workers have released ownership. */
    suspend fun closeAndAwait() {
        close()
    }
}

data class ConnectRequest(
    val profile: HostProfile,
    /** Mutable so the owner can erase the plaintext buffer after authentication is terminal. */
    val password: CharArray,
    val acceptedCertificateSha256: String? = profile.trustedCertificateSha256,
) {
    fun clearPassword() {
        password.fill('\u0000')
    }
}

sealed interface ConnectOutcome {
    data object Connected : ConnectOutcome
    data class CertificateReviewRequired(val certificate: CertificateDetails) : ConnectOutcome
    data class Failed(
        val failure: ConnectionFailure,
        val retryable: Boolean = true,
    ) : ConnectOutcome
}

enum class CertificateTrustSource { System, SavedLeafPin }

sealed interface TrustPreflightOutcome {
    data class Trusted(
        val source: CertificateTrustSource,
        val certificate: CertificateDetails,
    ) : TrustPreflightOutcome

    data class CertificateReviewRequired(val certificate: CertificateDetails) : TrustPreflightOutcome
    data class Failed(
        val failure: ConnectionFailure,
        val retryable: Boolean,
    ) : TrustPreflightOutcome
}

data class CertificateDetails(
    val sha256: String,
    val subject: String,
    val issuer: String,
    val subjectAlternativeNames: List<String>,
    val validFrom: String,
    val validUntil: String,
    val reason: CertificatePresentationReason = CertificatePresentationReason.NotTrustedByAndroid,
    /** True when certificate identity metadata was shortened or neutralized for safe display. */
    val metadataTruncated: Boolean = true,
)

data class BackendSession(
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Changes for each authenticated/reconnected control session to invalidate stale approvals. */
    val sessionGeneration: Long = 0,
    val remoteWidth: Int = 1920,
    val remoteHeight: Int = 1080,
    val streamLabel: VideoStreamDescriptor = VideoStreamDescriptor.DirectH264,
    val videoSettings: VideoSettings = VideoSettings(),
    /** Recreates the TextureView/BufferQueue when producer APIs or codecs must be switched. */
    val videoSurfaceGeneration: Long = 0,
    val framesPerSecond: Int? = null,
    val roundTripMs: Int? = null,
    /** Cumulative diagnostics for this authenticated connection; never drawn over video. */
    val droppedFrames: Long = 0L,
    val videoStallEvents: Long = 0L,
    val deviceStatus: NanoKvmDeviceStatus = NanoKvmDeviceStatus(),
    val capabilities: NanoKvmServerCapabilities? = null,
    val phase3: Phase3FeatureUiState = Phase3FeatureUiState(),
    val administration: AdministrationUiState = AdministrationUiState(),
    val pasteProgress: RemotePasteProgress? = null,
    /** Latest connection/video state; high-frequency transitions are intentionally latest-wins. */
    val status: ConsoleMessage.Status? = null,
    /** Latest user-action feedback, isolated from unrelated video/status callbacks. */
    val lastActionFeedback: SequencedConsoleActionFeedback? = null,
    val reconnectAttempt: Int? = null,
    val reconnectMaximumAttempts: Int? = null,
    val nextReconnectDelayMillis: Long? = null,
    /** Monotonically changes whenever all HID state is released, so UI latches can resynchronize. */
    val inputReleaseGeneration: Long = 0,
)

internal fun BackendSession.withActionFeedback(
    content: ConsoleMessage.ActionFeedback,
): BackendSession {
    val previousRevision = lastActionFeedback?.revision ?: 0L
    val revision = if (previousRevision == Long.MAX_VALUE) 1L else previousRevision + 1L
    return copy(
        lastActionFeedback = SequencedConsoleActionFeedback(
            revision = revision,
            content = content,
        ),
    )
}

internal fun BackendSession.recordDroppedFrames(count: Int): BackendSession {
    if (count <= 0) return this
    val increment = count.toLong()
    val updated = if (droppedFrames > Long.MAX_VALUE - increment) {
        Long.MAX_VALUE
    } else {
        droppedFrames + increment
    }
    return copy(droppedFrames = updated)
}

internal fun BackendSession.recordVideoStall(): BackendSession = copy(
    videoStallEvents = if (videoStallEvents == Long.MAX_VALUE) {
        Long.MAX_VALUE
    } else {
        videoStallEvents + 1L
    },
)

data class NanoKvmDeviceStatus(
    val applicationVersion: String = "",
    val imageVersion: String? = null,
    val hardwareVersion: String? = null,
    val deviceKey: String? = null,
    val mdnsName: String? = null,
    val networkAddresses: List<String> = emptyList(),
    val networkInterfaces: List<NanoKvmNetworkInterfaceStatus> = emptyList(),
    val powerOn: Boolean? = null,
    val hardDriveActive: Boolean? = null,
) {
    override fun toString(): String =
        "NanoKvmDeviceStatus(applicationVersion=$applicationVersion, " +
            "imageVersion=$imageVersion, hardwareVersion=$hardwareVersion, " +
            "deviceKey=<redacted>, mdnsName=<redacted>, " +
            "networkInterfaces=${networkInterfaces.size}, powerOn=$powerOn, " +
            "hardDriveActive=$hardDriveActive)"
}

data class NanoKvmNetworkInterfaceStatus(
    val name: String?,
    val address: String,
    val version: String?,
    val type: String?,
) {
    override fun toString(): String =
        "NanoKvmNetworkInterfaceStatus(identity=<redacted>)"
}

data class RemotePasteProgress(
    val operationToken: Long,
    val sentKeystrokes: Int,
    val totalKeystrokes: Int,
    val phase: RemotePastePhase = RemotePastePhase.Typing,
) {
    init {
        require(operationToken > 0) { "Paste operation token must be positive" }
        require(totalKeystrokes >= 0) { "Total keystrokes must not be negative" }
        require(sentKeystrokes in 0..totalKeystrokes) {
            "Sent keystrokes must be within the operation total"
        }
    }
}

enum class RemotePastePhase { Typing, Cancelling }

enum class ConnectionState { Disconnected, Connecting, Connected, Degraded, Reconnecting, Failed }

/** True while authenticated input/control remains available, including real video fallback. */
val ConnectionState.isSessionUsable: Boolean
    get() = this == ConnectionState.Connected || this == ConnectionState.Degraded

enum class MouseButton { Left, Right, Middle, Back, Forward }
enum class KeyboardLayout { Us, Uk }

enum class RemoteKey {
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    Digit1, Digit2, Digit3, Digit4, Digit5, Digit6, Digit7, Digit8, Digit9, Digit0,
    Enter, Escape, Backspace, Tab, Space,
    Minus, Equal, LeftBracket, RightBracket, Backslash, NonUsHash,
    Semicolon, Apostrophe, Grave, Comma, Period, Slash, CapsLock,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    PrintScreen, ScrollLock, Pause, Insert, Home, PageUp, Delete, End, PageDown,
    ArrowRight, ArrowLeft, ArrowDown, ArrowUp,
    NumLock, NumpadDivide, NumpadMultiply, NumpadSubtract, NumpadAdd, NumpadEnter,
    Numpad1, Numpad2, Numpad3, Numpad4, Numpad5,
    Numpad6, Numpad7, Numpad8, Numpad9, Numpad0, NumpadDecimal, NumpadComma,
    NumpadLeftParen, NumpadRightParen,
    NonUsBackslash, ContextMenu, NumpadEqual,
    Help, Cut, Copy, Paste, VolumeMute, VolumeUp, VolumeDown,
    Control, Shift, Alt, Super,
    RightControl, RightShift, RightAlt, RightSuper,
}

data class VideoSettings(
    val transportPreference: VideoTransportPreference = VideoTransportPreference.Auto,
    /** Zero keeps the NanoKVM encoder at its native/automatic capture resolution. */
    val resolutionHeight: Int = 0,
    val framesPerSecond: Int = 30,
    val bitrateKbps: Int = 3_000,
    val jpegQuality: Int = 80,
    val gopFrames: Int = 30,
) {
    init {
        require(resolutionHeight in setOf(0, 480, 600, 720, 1080)) {
            "Unsupported video height: $resolutionHeight"
        }
        require(framesPerSecond in 10..60) { "Frame rate must be between 10 and 60" }
        require(bitrateKbps in setOf(1_000, 2_000, 3_000, 5_000)) {
            "Unsupported H.264 bitrate: $bitrateKbps"
        }
        require(jpegQuality in setOf(50, 60, 80, 100)) {
            "Unsupported MJPEG quality: $jpegQuality"
        }
        require(gopFrames in setOf(10, 30, 50, 100)) {
            "Unsupported H.264 GOP: $gopFrames"
        }
    }
}

enum class VideoTransportPreference { Auto, WEBRTC, H264, MJPEG }

sealed interface PowerAction {
    data object ShortPress : PowerAction
    data object Reset : PowerAction
    data class LongPress(val seconds: Int = 12) : PowerAction
    data object CtrlAltDelete : PowerAction
}

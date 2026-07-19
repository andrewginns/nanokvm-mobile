package org.nanokvm.mobile.runtime

import android.view.Surface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.data.ProfilesRepository
import org.nanokvm.mobile.security.SavedCredentials
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

interface ConsoleCommandSink {
    /** Replay-free, memory-only operator streams. Existing test/no-op sinks receive empty streams. */
    val operatorState: StateFlow<OperatorUiState>
        get() = EmptyOperatorFlows.state
    val operatorOutput: SharedFlow<OperatorEphemeralOutput>
        get() = EmptyOperatorFlows.output
    /** Opt-in PicoClaw state. Construction and dialog discovery do not probe the appliance. */
    val picoClawState: StateFlow<PicoClawUiState>
        get() = EmptyPicoClawFlows.state
    /** Replay-free external navigation; authorization URLs never enter durable UI state. */
    val externalNavigation: SharedFlow<ExternalHttpsNavigationRequest>
        get() = EmptyExternalNavigation.flow

    fun reconnect()
    fun updateVideo(settings: VideoSettings)
    /** Explicit, replay-free write for the app-local MJPEG frame-detection preference. */
    fun setMjpegFrameDetectionEnabled(enabled: Boolean) = Unit
    fun resetHid()
    fun power(action: PowerAction)
    /** Types destination-bound, explicitly approved phone text through HID. */
    fun pasteText(request: ApprovedPasteRequest)
    /** Cancels an in-progress paced clipboard operation after its current atomic key pair. */
    fun cancelPaste()

    /** Opens/closes low-frequency Phase 3 polling; implementations must stop it in background. */
    fun setPhase3SurfaceVisible(visible: Boolean) = Unit
    fun refreshPhase3() = Unit
    fun mountPhase3Image(
        destination: ApprovedPhase3Destination,
        imageId: Long,
        mode: Phase3ImageMountMode,
    ) = Unit
    fun restorePhase3PhysicalMedia(destination: ApprovedPhase3Destination) = Unit
    fun deletePhase3Image(destination: ApprovedPhase3Destination, imageId: Long) = Unit
    fun setPhase3HidMode(
        destination: ApprovedPhase3Destination,
        selection: Phase3HidModeSelection,
    ) = Unit
    fun setPhase3NetworkEnabled(destination: ApprovedPhase3Destination, enabled: Boolean) = Unit
    fun setPhase3DiskEnabled(destination: ApprovedPhase3Destination, enabled: Boolean) = Unit
    fun startPhase3ImageTransfer(destination: ApprovedPhase3Destination, sourceUrl: String) = Unit
    fun sendPhase3WakeOnLan(destination: ApprovedPhase3Destination, macAddress: String) = Unit
    fun renamePhase3WakeOnLanTarget(
        destination: ApprovedPhase3Destination,
        targetId: Long,
        name: String,
    ) = Unit
    fun deletePhase3WakeOnLanTarget(
        destination: ApprovedPhase3Destination,
        targetId: Long,
    ) = Unit

    /** Opens/closes privileged administration loading; hidden/background surfaces perform no I/O. */
    fun setAdministrationSurfaceVisible(visible: Boolean) = Unit
    fun refreshAdministration() = Unit
    fun setAdministrationPreviewUpdates(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    fun startAdministrationOnlineUpdate(destination: ApprovedAdministrationDestination) = Unit
    fun rebootAdministrationAppliance(destination: ApprovedAdministrationDestination) = Unit
    fun setAdministrationOledSleep(
        destination: ApprovedAdministrationDestination,
        preset: AdministrationOledPreset,
    ) = Unit
    fun setAdministrationSshEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    fun setAdministrationHostname(
        destination: ApprovedAdministrationDestination,
        hostname: String,
    ) = Unit
    fun setAdministrationMdnsEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    fun setAdministrationWebTitle(
        destination: ApprovedAdministrationDestination,
        title: String,
    ) = Unit
    fun resetAdministrationWebTitle(destination: ApprovedAdministrationDestination) = Unit
    fun setAdministrationManualDns(
        destination: ApprovedAdministrationDestination,
        servers: List<String>,
    ) = Unit
    fun setAdministrationDhcpDns(destination: ApprovedAdministrationDestination) = Unit
    /** Ownership of [password] transfers to the sink, which clears every terminal path. */
    fun connectAdministrationWifi(
        destination: ApprovedAdministrationDestination,
        ssid: String,
        password: CharArray,
    ) {
        password.fill('\u0000')
    }
    fun disconnectAdministrationWifi(destination: ApprovedAdministrationDestination) = Unit
    fun executeAdministrationTailscale(
        destination: ApprovedAdministrationDestination,
        command: AdministrationTailscaleCommand,
    ) = Unit
    fun setAdministrationHdmiEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    fun resetAdministrationHdmi(destination: ApprovedAdministrationDestination) = Unit
    fun setAdministrationMouseJiggler(
        destination: ApprovedAdministrationDestination,
        selection: AdministrationMouseJigglerSelection,
    ) = Unit
    fun setAdministrationMemoryLimitEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    fun setAdministrationSwapSize(
        destination: ApprovedAdministrationDestination,
        preset: AdministrationSwapPreset,
    ) = Unit
    /** Enables appliance TLS once. Deliberately no disable operation is exposed. */
    fun enableAdministrationTls(destination: ApprovedAdministrationDestination) = Unit

    fun setOperatorSurfaceVisible(visible: Boolean) = Unit
    fun refreshOperatorScripts() = Unit
    fun enterOperatorTerminal(destination: ApprovedOperatorDestination) = Unit
    fun closeOperatorTerminal(destination: ApprovedOperatorDestination) = Unit
    fun sendOperatorTerminalInput(
        destination: ApprovedOperatorDestination,
        text: String,
    ) = Unit
    fun resizeOperatorTerminal(
        destination: ApprovedOperatorDestination,
        rows: Int,
        columns: Int,
    ) = Unit
    fun startOperatorSerial(
        destination: ApprovedOperatorDestination,
        configuration: OperatorSerialConfiguration,
    ) = Unit
    fun exitOperatorSerial(destination: ApprovedOperatorDestination) = Unit
    fun uploadOperatorScript(
        destination: ApprovedOperatorDestination,
        request: OperatorScriptUploadRequest,
    ) = Unit
    fun runOperatorScript(
        destination: ApprovedOperatorDestination,
        scriptId: Long,
        mode: OperatorScriptRunMode,
    ) = Unit
    fun deleteOperatorScript(
        destination: ApprovedOperatorDestination,
        scriptId: Long,
    ) = Unit

    /** The surface may be discovered without entering/probing PicoClaw. */
    fun setPicoClawSurfaceVisible(visible: Boolean) = Unit
    /** Called only after the broad filesystem/exec/cron/MCP/HID warning is confirmed. */
    fun enterPicoClaw(destination: ApprovedPicoClawDestination) = Unit
    fun refreshPicoClaw(destination: ApprovedPicoClawDestination) = Unit
    fun installPicoClawRuntime(destination: ApprovedPicoClawDestination) = Unit
    fun startPicoClawRuntime(destination: ApprovedPicoClawDestination) = Unit
    fun stopPicoClawRuntime(destination: ApprovedPicoClawDestination) = Unit
    fun uninstallPicoClawRuntime(destination: ApprovedPicoClawDestination) = Unit
    fun setPicoClawProfile(
        destination: ApprovedPicoClawDestination,
        profile: PicoClawProfile,
    ) = Unit
    fun configurePicoClawModel(
        destination: ApprovedPicoClawDestination,
        request: PicoClawModelConfigurationRequest,
    ) = Unit
    fun refreshPicoClawHistories(destination: ApprovedPicoClawDestination) = Unit
    fun loadPicoClawHistory(destination: ApprovedPicoClawDestination, historyId: Long) = Unit
    fun deletePicoClawHistory(destination: ApprovedPicoClawDestination, historyId: Long) = Unit
    fun openPicoClawChat(destination: ApprovedPicoClawDestination) = Unit
    fun sendPicoClawChatMessage(destination: ApprovedPicoClawDestination, content: String) = Unit
    fun cancelPicoClawChat(destination: ApprovedPicoClawDestination) = Unit
    fun closeAndReleasePicoClaw(destination: ApprovedPicoClawDestination) = Unit
}

/**
 * App-internal bridge for the feature-contained automation editor. Keeping the rich, opaque
 * gateway out of the public command interface prevents handles and root-equivalent script content
 * from becoming durable UI state or a generally reusable API.
 */
internal interface NanoKvmAutomationFeatureOwner {
    fun setAutomationSurfaceVisible(visible: Boolean)
    /** Opaque at the public backend boundary; only the app-internal helper may recover the type. */
    fun currentAutomationGatewayToken(): Any?
}

internal fun NanoKvmAutomationFeatureOwner.currentAutomationGateway():
    NanoKvmAutomationGateway? = currentAutomationGatewayToken() as? NanoKvmAutomationGateway

/**
 * App-internal bridge for the generation-bound, one-shot offline updater. The public backend
 * exposes only an opaque token so document openers and update approvals cannot become a reusable
 * command API or durable console state.
 */
internal interface NanoKvmOfflineUpdateFeatureOwner {
    fun setOfflineUpdateSurfaceVisible(visible: Boolean)
    fun currentOfflineUpdateGatewayToken(): Any?
}

internal fun NanoKvmOfflineUpdateFeatureOwner.currentOfflineUpdateGateway():
    NanoKvmOfflineUpdateGateway? =
    currentOfflineUpdateGatewayToken() as? NanoKvmOfflineUpdateGateway

/**
 * App-internal factory for one exact, generation-bound password-change coordinator. Keeping this
 * out of [ConsoleCommandSink] prevents privileged credential mutation from becoming a replayable
 * fire-and-forget UI command.
 */
internal interface NanoKvmPasswordChangeFeatureOwner {
    fun createPasswordChangeCoordinatorToken(requestToken: Any): Any?
}

internal fun NanoKvmPasswordChangeFeatureOwner.createPasswordChangeCoordinator(
    destination: ApprovedAdministrationDestination,
    profile: HostProfile,
    savedCredentials: SavedCredentials,
    profilesRepository: ProfilesRepository,
    sessionTerminator: NanoKvmPasswordChangeSessionTerminator,
): NanoKvmPasswordChangeCoordinator? = createPasswordChangeCoordinatorToken(
    NanoKvmPasswordChangeFactoryRequest(
        destination,
        profile,
        savedCredentials,
        profilesRepository,
        sessionTerminator,
    ),
) as? NanoKvmPasswordChangeCoordinator

private class NanoKvmPasswordChangeFactoryRequest(
    val destination: ApprovedAdministrationDestination,
    val profile: HostProfile,
    val savedCredentials: SavedCredentials,
    val profilesRepository: ProfilesRepository,
    val sessionTerminator: NanoKvmPasswordChangeSessionTerminator,
) {
    override fun toString(): String =
        "NanoKvmPasswordChangeFactoryRequest(destination=<redacted>, profile=<redacted>)"
}

internal fun Any.passwordChangeFactoryRequestOrNull(): PasswordChangeFactoryValues? =
    (this as? NanoKvmPasswordChangeFactoryRequest)?.let {
        PasswordChangeFactoryValues(
            it.destination,
            it.profile,
            it.savedCredentials,
            it.profilesRepository,
            it.sessionTerminator,
        )
    }

internal class PasswordChangeFactoryValues(
    val destination: ApprovedAdministrationDestination,
    val profile: HostProfile,
    val savedCredentials: SavedCredentials,
    val profilesRepository: ProfilesRepository,
    val sessionTerminator: NanoKvmPasswordChangeSessionTerminator,
) {
    override fun toString(): String =
        "PasswordChangeFactoryValues(destination=<redacted>, profile=<redacted>)"
}

private object EmptyOperatorFlows {
    val state = MutableStateFlow(OperatorUiState())
    val output = MutableSharedFlow<OperatorEphemeralOutput>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}

private object EmptyPicoClawFlows {
    val state = MutableStateFlow(PicoClawUiState())
}

private object EmptyExternalNavigation {
    val flow = MutableSharedFlow<ExternalHttpsNavigationRequest>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}

/** Short-lived HTTPS handoff whose sensitive URL is deliberately absent from properties/strings. */
class ExternalHttpsNavigationRequest internal constructor(
    private val value: String,
) {
    fun open(opener: (String) -> Unit) = opener(value)

    override fun toString(): String = "ExternalHttpsNavigationRequest(url=<redacted>)"
}

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

data class PicoClawNoticeUiState(
    val kind: PicoClawNoticeKind,
    /** App-authored, bounded guidance only. */
    val message: String,
)

class PicoClawHistoryUiState(
    val id: Long,
    val title: String,
    val preview: String,
    val messageCount: Int,
) {
    override fun toString(): String =
        "PicoClawHistoryUiState(id=$id, messageCount=$messageCount, content=<redacted>)"
}

class PicoClawMessageUiState(
    val role: PicoClawMessageRole,
    val content: String,
) {
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
    val notice: PicoClawNoticeUiState? = null,
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

data class OperatorNoticeUiState(
    val kind: OperatorNoticeKind,
    /** Bounded app-authored guidance only. */
    val message: String,
)

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
    val notice: OperatorNoticeUiState? = null,
)

enum class OperatorOutputKind { Terminal, Script }

/** Ephemeral output value whose diagnostics redact content. It is delivered on a replay-free flow. */
class OperatorEphemeralOutput internal constructor(
    val kind: OperatorOutputKind,
    content: String,
) {
    private val retainedContent = content
    val utf8ByteCount: Int = content.encodeToByteArray().size

    init {
        require(utf8ByteCount <= MAX_OPERATOR_OUTPUT_BYTES) {
            "Operator output exceeds the app display limit"
        }
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

internal fun appendBoundedOperatorOutput(
    current: String,
    incoming: String,
    maximumUtf8Bytes: Int = MAX_OPERATOR_OUTPUT_BYTES,
): String {
    require(maximumUtf8Bytes > 0) { "Output limit must be positive" }
    val combined = current + incoming
    val bytes = combined.encodeToByteArray()
    if (bytes.size <= maximumUtf8Bytes) return combined
    var start = combined.length
    var retainedBytes = 0
    while (start > 0) {
        var candidate = start - 1
        if (
            candidate > 0 &&
            combined[candidate].isLowSurrogate() &&
            combined[candidate - 1].isHighSurrogate()
        ) {
            candidate--
        }
        val codePointBytes = combined.substring(candidate, start).encodeToByteArray().size
        if (retainedBytes + codePointBytes > maximumUtf8Bytes) break
        retainedBytes += codePointBytes
        start = candidate
    }
    return combined.substring(start)
}

const val MAX_OPERATOR_OUTPUT_BYTES: Int = 256 * 1024

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

data class AdministrationNoticeUiState(
    val kind: AdministrationNoticeKind,
    /** Bounded app-authored guidance only; never a server response body or Throwable message. */
    val message: String,
)

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
    val notice: AdministrationNoticeUiState? = null,
)

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
    /** Read/action guidance belonging only to the virtual-media surface. */
    val virtualMediaNotice: String? = null,
    /** Read/action guidance belonging only to the Wake-on-LAN surface. */
    val wakeOnLanNotice: String? = null,
    /** Safe, bounded guidance only; never a server body, path, URL, or Throwable message. */
    val notice: String? = null,
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
    ConsoleCommandSink {
    val session: StateFlow<BackendSession>

    /** Performs a TLS-only trust gate before the caller unlocks or collects a password. */
    suspend fun preflightTrust(profile: HostProfile): TrustPreflightOutcome =
        TrustPreflightOutcome.Failed("Certificate preflight is unavailable.", retryable = false)

    suspend fun connect(request: ConnectRequest): ConnectOutcome
    suspend fun disconnect()
    fun setForeground(isForeground: Boolean)

    /** Updates app-local intent only; settings collection must not perform appliance I/O. */
    fun setMjpegFrameDetectionPreference(enabled: Boolean) = Unit

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
    data class Failed(val userMessage: String, val retryable: Boolean = true) : ConnectOutcome
}

enum class CertificateTrustSource { System, SavedLeafPin }

sealed interface TrustPreflightOutcome {
    data class Trusted(
        val source: CertificateTrustSource,
        val certificate: CertificateDetails,
    ) : TrustPreflightOutcome

    data class CertificateReviewRequired(val certificate: CertificateDetails) : TrustPreflightOutcome
    data class Failed(val userMessage: String, val retryable: Boolean) : TrustPreflightOutcome
}

data class CertificateDetails(
    val sha256: String,
    val subject: String,
    val issuer: String,
    val subjectAlternativeNames: List<String>,
    val validFrom: String,
    val validUntil: String,
    val reason: String = "This certificate is not trusted by Android.",
)

data class BackendSession(
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Changes for each authenticated/reconnected control session to invalidate stale approvals. */
    val sessionGeneration: Long = 0,
    val remoteWidth: Int = 1920,
    val remoteHeight: Int = 1080,
    val streamLabel: String = "H.264 direct",
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
    val message: String? = null,
    val reconnectAttempt: Int? = null,
    val reconnectMaximumAttempts: Int? = null,
    val nextReconnectDelayMillis: Long? = null,
    /** Monotonically changes whenever all HID state is released, so UI latches can resynchronize. */
    val inputReleaseGeneration: Long = 0,
)

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

enum class ConnectionState { Disconnected, Connecting, Connected, Reconnecting, Failed }
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

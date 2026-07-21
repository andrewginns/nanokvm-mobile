package org.nanokvm.mobile.runtime

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Surface
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.protocol.AbsoluteMouseReport
import org.nanokvm.protocol.ApiResponseException
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.CertificateFingerprint
import org.nanokvm.protocol.CertificateInspection
import org.nanokvm.protocol.CertificateTrustSource as ProtocolCertificateTrustSource
import org.nanokvm.protocol.EndpointTrustPreflight
import org.nanokvm.protocol.EndpointTrustPreflightResult
import org.nanokvm.protocol.GpioAction
import org.nanokvm.protocol.HidKeyboardReport
import org.nanokvm.protocol.HidModifier
import org.nanokvm.protocol.HidUsage
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.InputConnectionState
import org.nanokvm.protocol.KeyboardReportState
import org.nanokvm.protocol.NanoKvmClient
import org.nanokvm.protocol.NanoKvmApplicationVersion
import org.nanokvm.protocol.NanoKvmCapability
import org.nanokvm.protocol.NanoKvmCapabilitySupport
import org.nanokvm.protocol.NanoKvmEndpoint
import org.nanokvm.protocol.NanoKvmException
import org.nanokvm.protocol.NanoKvmInputSocket
import org.nanokvm.protocol.NanoKvmImageMountMode
import org.nanokvm.protocol.NanoKvmMacAddress
import org.nanokvm.protocol.NanoKvmMemoryLimitState
import org.nanokvm.protocol.NanoKvmMouseJigglerMode
import org.nanokvm.protocol.NanoKvmMouseJigglerState
import org.nanokvm.protocol.NanoKvmOledSleepPreset
import org.nanokvm.protocol.NanoKvmPicoClawAgentProfile
import org.nanokvm.protocol.NanoKvmPicoClawApiBase
import org.nanokvm.protocol.NanoKvmProbeResult
import org.nanokvm.protocol.NanoKvmRemoteImageUrl
import org.nanokvm.protocol.NanoKvmScriptRunMode
import org.nanokvm.protocol.NanoKvmSerialBaud
import org.nanokvm.protocol.NanoKvmSerialConfiguration
import org.nanokvm.protocol.NanoKvmSerialDataBits
import org.nanokvm.protocol.NanoKvmSerialFlowControl
import org.nanokvm.protocol.NanoKvmSerialParity
import org.nanokvm.protocol.NanoKvmSerialPort
import org.nanokvm.protocol.NanoKvmSerialStopBits
import org.nanokvm.protocol.NanoKvmTerminalSize
import org.nanokvm.protocol.NanoKvmSwapSizePreset
import org.nanokvm.protocol.NanoKvmSwapState
import org.nanokvm.protocol.NanoKvmTailscaleCommand
import org.nanokvm.protocol.NanoKvmVirtualDevice
import org.nanokvm.protocol.KeyboardLayout as ProtocolKeyboardLayout
import org.nanokvm.protocol.PacedCommittedTextProgress
import org.nanokvm.protocol.PacedCommittedTextResult
import org.nanokvm.protocol.RelativeMouseReport
import org.nanokvm.protocol.ScreenSetting
import org.nanokvm.protocol.SessionToken
import org.nanokvm.protocol.TlsMode
import org.nanokvm.protocol.TrustPreflightRejection
import org.nanokvm.video.H264FrameDropReason
import org.nanokvm.video.H264DecoderConfig
import org.nanokvm.video.NanoKvmVideoConfig
import org.nanokvm.video.NanoKvmVideoListener
import org.nanokvm.video.NanoKvmVideoPreference
import org.nanokvm.video.NanoKvmWebRtcRuntime
import org.nanokvm.video.NanoKvmVideoSession
import org.nanokvm.video.NanoKvmVideoStatus
import org.nanokvm.video.NanoKvmVideoTransport

/** Real console adapter joining the protocol, HID, video, and Compose-facing contracts. */
internal class NanoKvmConsoleBackend internal constructor(
    private val workerDispatcher: CoroutineDispatcher,
    private val reconnectPolicy: ReconnectPolicy,
    private val webRtcRuntimeProvider: WebRtcRuntimeProvider? = null,
) : ConsoleBackend,
    Phase3Controls,
    AdministrationControls,
    OperatorControls,
    PicoClawControls,
    NanoKvmAutomationFeatureOwner,
    NanoKvmOfflineUpdateFeatureOwner,
    NanoKvmPasswordChangeFeatureOwner {
    constructor() : this(Dispatchers.IO, ReconnectPolicy(), null)
    constructor(webRtcRuntime: NanoKvmWebRtcRuntime) :
        this(Dispatchers.IO, ReconnectPolicy(), WebRtcRuntimeProvider { webRtcRuntime })
    constructor(webRtcRuntimeProvider: () -> NanoKvmWebRtcRuntime?) :
        this(Dispatchers.IO, ReconnectPolicy(), WebRtcRuntimeProvider(webRtcRuntimeProvider))

    private val mutableSession = MutableStateFlow(BackendSession())
    override val session: StateFlow<BackendSession> = mutableSession.asStateFlow()
    override val features = ConsoleFeatureBundle(
        core = this,
        phase3 = this,
        administration = this,
        operator = this,
        picoClaw = this,
        automation = this,
        offlineUpdate = this,
        passwordChange = this,
    )

    private val lifecycleMutex = Mutex()
    /** Serializes paced typing with every keyboard/host-state command that cancels it. */
    private val pasteExecutionMutex = Mutex()
    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private val closeScope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private val closeCompletion = CompletableDeferred<Unit>()
    private val mjpegFrameDetectionCoordinator = NanoKvmMjpegFrameDetectionCoordinator(
        scope = scope,
        currentBinding = ::currentMjpegFrameDetectionBinding,
        onAuthenticationExpired = { binding -> expireAuthenticatedSession(binding) },
        onRejected = { message ->
            mutableSession.update { current ->
                if (current.connection.isSessionUsable) {
                    current.withActionFeedback(message)
                } else {
                    current
                }
            }
        },
    )
    private val controlGate = ControlCommandGate()
    private val phase3Lifecycle = SessionBoundFeatureLifecycle<NanoKvmPhase3FeatureGateway>()
    private val administrationLifecycle =
        SessionBoundFeatureLifecycle<NanoKvmAdministrationGateway>()
    private val deviceControlLifecycle =
        SessionBoundFeatureLifecycle<NanoKvmDeviceControlGateway>()
    private val operatorLifecycle = SessionBoundFeatureLifecycle<NanoKvmOperatorGateway>()
    private val picoClawLifecycle = SessionBoundFeatureLifecycle<NanoKvmPicoClawFeatureGateway>()
    private val automationLifecycle = SessionBoundFeatureLifecycle<NanoKvmAutomationGateway>()
    private val offlineUpdateLifecycle =
        SessionBoundFeatureLifecycle<NanoKvmOfflineUpdateGateway>()
    private val mutableOperatorState = MutableStateFlow(OperatorUiState())
    override val operatorState: StateFlow<OperatorUiState> = mutableOperatorState.asStateFlow()
    private val mutableOperatorOutput = MutableSharedFlow<OperatorEphemeralOutput>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val operatorOutput: SharedFlow<OperatorEphemeralOutput> =
        mutableOperatorOutput.asSharedFlow()
    private val mutablePicoClawState = MutableStateFlow(PicoClawUiState())
    override val picoClawState: StateFlow<PicoClawUiState> = mutablePicoClawState.asStateFlow()
    private val applianceStatusPollingBackoff = PollingBackoffPolicy()
    private val videoCallbacks: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NanoKVM-video-callback").apply { isDaemon = true }
    }

    @Volatile private var authenticatedSession: AuthenticatedNanoKvmSession? = null
    private val client: NanoKvmClient?
        get() = authenticatedSession?.client
    @Volatile private var input: NanoKvmInputSocket? = null
    private var inputMonitor: Job? = null
    private var applianceStatusMonitor: Job? = null
    private var phase3RefreshJob: Job? = null
    private var phase3ActionJob: Job? = null
    private var phase3TransferPollJob: Job? = null
    private var administrationRefreshJob: Job? = null
    private var administrationActionJob: Job? = null
    private var operatorScriptRefreshJob: Job? = null
    private var operatorActionJob: Job? = null
    private var operatorTerminalStateJob: Job? = null
    private var operatorTerminalOutputJob: Job? = null
    private var picoClawActionJob: Job? = null
    private var picoClawChatStateJob: Job? = null
    private var picoClawChatLockJob: Job? = null
    private var picoClawChatEventJob: Job? = null
    private var reconnectJob: Job? = null
    private val pasteOperations = PasteOperationTracker()
    private var activePaste: ActivePasteOperation? = null
    private var queuedKeyboardTail: Job? = null
    private var activeConnectJob: Job? = null
    private var video: NanoKvmVideoSession? = null
    private var activeVideoListener: SessionBoundVideoListener? = null
    private var surface: Surface? = null
    @Volatile private var foreground = true
    @Volatile private var phase3SurfaceVisible = false
    @Volatile private var administrationSurfaceVisible = false
    @Volatile private var operatorSurfaceVisible = false
    @Volatile private var picoClawSurfaceVisible = false
    @Volatile private var automationSurfaceVisible = false
    @Volatile private var offlineUpdateSurfaceVisible = false
    @Volatile private var phase3UsbMutationInFlight = false
    @Volatile private var closed = false
    private var keyboardState = KeyboardReportState()
    private val pressedMouseButtons = linkedSetOf<MouseButton>()
    private var lastAbsoluteX = 0
    private var lastAbsoluteY = 0
    private var usedAbsoluteMouse = false
    @Volatile private var videoSettings = VideoSettings()
    private var renderedFrameWindowStartNanos = 0L
    private var renderedFramesInWindow = 0
    private var sessionGenerationCounter = 0L
    private var commandAcceptanceEpoch = 0L
    private var acceptingCommands = false
    private var phase3MediaCatalog: NanoKvmMediaCatalog? = null
    private var phase3MediaHandles: Map<Long, NanoKvmMediaImage> = emptyMap()
    private var phase3WakeSnapshot: NanoKvmWakeOnLanSnapshot? = null
    private var phase3WakeHandles: Map<Long, NanoKvmWakeOnLanTarget> = emptyMap()
    private var phase3HandleCounter = 0L
    private var administrationNavigationRequestCounter = 0L
    private var operatorScriptCatalog: NanoKvmOperatorScriptCatalog? = null
    private var operatorScriptHandles: Map<Long, NanoKvmOperatorScript> = emptyMap()
    private var operatorScriptHandleCounter = 0L
    private var picoClawHistoryCatalog: NanoKvmPicoClawHistoryCatalogSnapshot? = null
    private var picoClawHistoryHandles: Map<Long, NanoKvmPicoClawHistoryItem> = emptyMap()
    private var picoClawHistoryHandleCounter = 0L

    private data class CloseResources(
        val input: NanoKvmInputSocket?,
        val connectJob: Job?,
    )

    override suspend fun preflightTrust(profile: HostProfile): TrustPreflightOutcome =
        withContext(workerDispatcher) {
            if (closed) {
                return@withContext TrustPreflightOutcome.Failed(
                    ConnectionFailure.SessionClosed,
                    retryable = false,
                )
            }
            val endpoint = runCatching { NanoKvmEndpoint.parse(profile.baseUrl) }.getOrElse {
                return@withContext TrustPreflightOutcome.Failed(
                    ConnectionFailure.InvalidAddress,
                    retryable = false,
                )
            }
            performTrustPreflight(endpoint, profile.trustedCertificateSha256)
        }

    override suspend fun connect(request: ConnectRequest): ConnectOutcome = withContext(workerDispatcher) {
        closeCommandAcceptanceBarrier()
        controlGate.invalidate()
        cancelReconnectJob()
        val operationJob = currentCoroutineContext()[Job]
        val accepted = synchronized(stateLock) {
            if (closed || !foreground) false else {
                activeConnectJob = operationJob
                true
            }
        }
        if (!accepted) {
            val failure = if (closed) {
                ConnectionFailure.SessionClosed
            } else {
                ConnectionFailure.AppInBackground
            }
            return@withContext ConnectOutcome.Failed(failure, false)
        }
        try {
            cancelPasteAndJoin(userInitiated = false)
            lifecycleMutex.withLock { connectLocked(request) }
        } finally {
            synchronized(stateLock) {
                if (activeConnectJob === operationJob) activeConnectJob = null
            }
        }
    }

    private suspend fun connectLocked(request: ConnectRequest): ConnectOutcome {
        if (closed) return ConnectOutcome.Failed(ConnectionFailure.SessionClosed, false)
        cleanupSessionLocked(forgetClient = true)
        videoSettings = VideoSettings()
        mutableSession.value = BackendSession(
            connection = ConnectionState.Connecting,
            status = ConsoleMessage.VerifyingDestination(request.profile.authority),
        )

        val endpoint = runCatching { NanoKvmEndpoint.parse(request.profile.baseUrl) }
            .getOrElse {
                mutableSession.value = BackendSession(connection = ConnectionState.Failed)
                return ConnectOutcome.Failed(ConnectionFailure.InvalidAddress, false)
            }
        when (val trust = performTrustPreflight(endpoint, request.acceptedCertificateSha256)) {
            is TrustPreflightOutcome.CertificateReviewRequired -> {
                mutableSession.value = BackendSession(connection = ConnectionState.Disconnected)
                return ConnectOutcome.CertificateReviewRequired(trust.certificate)
            }
            is TrustPreflightOutcome.Failed -> {
                mutableSession.value = BackendSession(connection = ConnectionState.Failed)
                return ConnectOutcome.Failed(trust.failure, trust.retryable)
            }
            is TrustPreflightOutcome.Trusted -> Unit
        }
        mutableSession.update {
            it.copy(status = ConsoleMessage.AuthenticatingDestination(request.profile.authority))
        }
        val tlsMode = try {
            request.acceptedCertificateSha256?.let {
                TlsMode.PinnedCertificate(CertificateFingerprint.parse(it))
            } ?: TlsMode.SystemTrusted
        } catch (error: IllegalArgumentException) {
            mutableSession.value = BackendSession(connection = ConnectionState.Failed)
            return ConnectOutcome.Failed(ConnectionFailure.InvalidSavedCertificate, false)
        }

        val createdClient = NanoKvmClient.create(endpoint, tlsMode)
        var pendingInput: NanoKvmInputSocket? = null
        var inputFailureStatus: Int? = null
        return try {
            val token = createdClient.api.login(request.profile.username, request.password)
            val probeStartedAt = System.nanoTime()
            val probe = createdClient.api.probeCapabilities()
            val discoveryElapsedMs = ((System.nanoTime() - probeStartedAt) / 1_000_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val info = (probe.vmInfo as? NanoKvmProbeResult.Supported)?.value
                // VM information is the compatibility gate, so an inconclusive optional probe is
                // retried as a required read and allowed to fail the connection with its real error.
                ?: createdClient.api.vmInfo()
            if (!isSupportedNanoKvmApplication(info.application)) {
                createdClient.forgetSession()
                createdClient.close()
                mutableSession.value = BackendSession(connection = ConnectionState.Failed)
                return ConnectOutcome.Failed(
                    ConnectionFailure.UnsupportedApplicationVersion,
                    false,
                )
            }

            val createdInput = createdClient.newInputSocket()
            pendingInput = createdInput
            createdInput.connect()
            when (val inputState = withTimeoutOrNull(INPUT_CONNECT_TIMEOUT_MS) {
                createdInput.state.first {
                    it is InputConnectionState.Connected || it is InputConnectionState.Failed
                }
            } ?: throw SocketTimeoutException("NanoKVM input WebSocket timed out")) {
                is InputConnectionState.Failed -> {
                    inputFailureStatus = inputState.httpStatus
                    throw inputState.cause
                }
                else -> Unit
            }

            val createdSession = AuthenticatedNanoKvmSession(
                client = createdClient,
                profileId = request.profile.id,
                authority = request.profile.authority,
                vmInfo = info,
                capabilities = probe.capabilities,
            )
            val hardware = (probe.hardware as? NanoKvmProbeResult.Supported)?.value
            val gpio = (probe.gpio as? NanoKvmProbeResult.Supported)?.value
            synchronized(stateLock) {
                val generation = ++sessionGenerationCounter
                authenticatedSession = createdSession
                input = createdInput
                installSessionFeatureSetLocked(createdSession, generation)
                mutableSession.value = BackendSession(
                    connection = ConnectionState.Connected,
                    sessionGeneration = generation,
                    streamLabel = VideoStreamDescriptor.DirectH264,
                    roundTripMs = discoveryElapsedMs,
                    deviceStatus = info.toNanoKvmDeviceStatus(hardware, gpio),
                    capabilities = probe.capabilities,
                    phase3 = Phase3FeatureUiState(available = true),
                    administration = AdministrationUiState(available = true),
                    status = ConsoleMessage.ConnectedToNanoKvm,
                )
                openCommandAcceptanceLocked()
            }
            pendingInput = null
            activateOfflineUpdateGatewayIfCurrent()
            monitorInput(createdInput, checkNotNull(currentMjpegFrameDetectionBinding()))
            monitorApplianceStatus(createdSession)
            startVideoIfReadyLocked(token)
            startPhase3SurfaceWorkIfNeeded()
            startAdministrationSurfaceWorkIfNeeded()
            startOperatorSurfaceWorkIfNeeded()
            startPicoClawSurfaceWorkIfNeeded()
            ConnectOutcome.Connected
        } catch (error: Throwable) {
            if (client === createdClient) {
                cleanupSessionLocked(forgetClient = true)
            } else {
                pendingInput?.close()
                createdClient.forgetSession()
                createdClient.close()
            }
            if (error is CancellationException) throw error
            mutableSession.value = BackendSession(connection = ConnectionState.Failed)
            val failure = ReconnectFailure(error, inputFailureStatus)
            ConnectOutcome.Failed(
                failure.toConnectionFailure(),
                retryable = failure.disposition() == ReconnectFailureDisposition.RETRY,
            )
        }
    }

    override suspend fun disconnect() {
        closeCommandAcceptanceBarrier()
        controlGate.invalidate()
        cancelReconnectJob()
        withContext(workerDispatcher) {
            cancelPasteAndJoin(userInitiated = false)
            lifecycleMutex.withLock {
                cleanupSessionLocked(forgetClient = true)
                videoSettings = VideoSettings()
                mutableSession.value = BackendSession()
            }
        }
    }

    override fun reconnect() {
        scheduleReconnect(
            ReconnectFailure(IOException("Manual reconnect requested")),
            immediateFirstAttempt = true,
        )
    }

    override fun cancelReconnect() {
        val cancelled = synchronized(stateLock) {
            if (mutableSession.value.connection != ConnectionState.Reconnecting) {
                return@synchronized null
            }
            val job = reconnectJob ?: return@synchronized null
            reconnectJob = null
            mutableSession.value = mutableSession.value.copy(
                connection = ConnectionState.Failed,
                status = ConsoleMessage.ReconnectCancelled,
                reconnectAttempt = null,
                reconnectMaximumAttempts = null,
                nextReconnectDelayMillis = null,
            )
            job
        }
        if (cancelled != null) {
            cancelled.cancel(CancellationException("Reconnect cancelled by operator"))
            controlGate.invalidate()
            requestPasteCancellation(userInitiated = false)
            releaseAllInputNow()
        }
    }

    override fun setForeground(isForeground: Boolean) {
        var connectToCancel: Job? = null
        val changed = synchronized(stateLock) {
            val value = foreground != isForeground
            foreground = isForeground
            if (!isForeground) connectToCancel = activeConnectJob
            value
        }
        if (!changed) return
        if (isForeground) {
            scheduleReconnect(
                ReconnectFailure(IOException("Returning to foreground")),
                immediateFirstAttempt = true,
            )
            return
        }
        closeCommandAcceptanceBarrier()
        controlGate.invalidate()
        cancelReconnectJob()
        connectToCancel?.cancel(CancellationException("App moved to background"))
        releaseAllInput()
        val administrationJobs = synchronized(stateLock) {
            listOfNotNull(
                administrationRefreshJob.also { administrationRefreshJob = null },
                administrationActionJob.also { administrationActionJob = null },
            )
        }
        administrationJobs.forEach {
            it.cancel(CancellationException("Administration paused in background"))
        }
        scope.launch {
            cancelPasteAndJoin(userInitiated = false)
            lifecycleMutex.withLock {
                // Ignore an older lifecycle callback that ran after a newer foreground change.
                if (closed || foreground != isForeground) return@withLock
                if (connectToCancel != null) {
                    cleanupSessionLocked(forgetClient = true)
                } else if (client != null) {
                    stopStreamingLocked()
                }
                mutableSession.update {
                    it.copy(
                        connection = ConnectionState.Disconnected,
                        status = ConsoleMessage.PausedInBackground,
                        lastActionFeedback = null,
                        reconnectAttempt = null,
                        reconnectMaximumAttempts = null,
                        nextReconnectDelayMillis = null,
                    )
                }
            }
        }
    }

    override fun attachVideoSurface(surface: Surface, width: Int, height: Int) {
        synchronized(stateLock) { this.surface = surface }
        scope.launch {
            lifecycleMutex.withLock {
                if (this@NanoKvmConsoleBackend.surface === surface && surface.isValid) {
                    startVideoIfReadyLocked()
                }
            }
        }
    }

    override fun resizeVideoSurface(width: Int, height: Int) = Unit

    override fun detachVideoSurface(surface: Surface) {
        synchronized(stateLock) {
            if (this.surface === surface) this.surface = null
        }
        scope.launch {
            lifecycleMutex.withLock {
                if (synchronized(stateLock) { this@NanoKvmConsoleBackend.surface == null }) {
                    closeVideoAndAwaitDecoderReleaseLocked()
                }
            }
        }
    }

    override fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton>) {
        val carriesButtons = synchronized(stateLock) {
            buttons.isNotEmpty() || pressedMouseButtons.isNotEmpty()
        }
        if (carriesButtons) {
            queueKeyboardCommandAfterPaste { moveAbsoluteNow(x, y, buttons) }
        } else {
            moveAbsoluteNow(x, y, buttons)
        }
    }

    private fun moveAbsoluteNow(x: Int, y: Int, buttons: Set<MouseButton>) {
        val socket: NanoKvmInputSocket?
        val effectiveButtons: Set<MouseButton>
        synchronized(stateLock) {
            if (!acceptingCommands) return
            lastAbsoluteX = x.coerceIn(0, 32_767)
            lastAbsoluteY = y.coerceIn(0, 32_767)
            usedAbsoluteMouse = true
            socket = input
            effectiveButtons = if (buttons.isEmpty()) pressedMouseButtons.toSet() else buttons
        }
        socket?.sendMouse(
            AbsoluteMouseReport.create(effectiveButtons.toProtocolButtons(), lastAbsoluteX, lastAbsoluteY),
        )
    }

    override fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) {
        val carriesButtons = synchronized(stateLock) {
            buttons.isNotEmpty() || pressedMouseButtons.isNotEmpty()
        }
        if (carriesButtons) {
            queueKeyboardCommandAfterPaste { moveRelativeNow(deltaX, deltaY, buttons) }
        } else {
            moveRelativeNow(deltaX, deltaY, buttons)
        }
    }

    private fun moveRelativeNow(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) {
        val socket: NanoKvmInputSocket?
        val effectiveButtons: Set<MouseButton>
        synchronized(stateLock) {
            if (!acceptingCommands) return
            socket = input
            usedAbsoluteMouse = false
            effectiveButtons = if (buttons.isEmpty()) pressedMouseButtons.toSet() else buttons
        }
        socket?.sendMouse(
            RelativeMouseReport.create(
                buttons = effectiveButtons.toProtocolButtons(),
                deltaX = deltaX,
                deltaY = deltaY,
            ),
        )
    }

    override fun mouseButton(button: MouseButton, pressed: Boolean) {
        queueKeyboardCommandAfterPaste { mouseButtonNow(button, pressed) }
    }

    private fun mouseButtonNow(button: MouseButton, pressed: Boolean) {
        val socket: NanoKvmInputSocket?
        val buttons: Set<org.nanokvm.protocol.MouseButton>
        val absolute: Boolean
        val x: Int
        val y: Int
        synchronized(stateLock) {
            if (pressed) pressedMouseButtons += button else pressedMouseButtons -= button
            socket = input
            buttons = pressedMouseButtons.toSet().toProtocolButtons()
            absolute = usedAbsoluteMouse
            x = lastAbsoluteX
            y = lastAbsoluteY
        }
        if (absolute) {
            socket?.sendMouse(AbsoluteMouseReport.create(buttons, x, y))
        } else {
            socket?.sendMouse(RelativeMouseReport.create(buttons))
        }
    }

    override fun scrollWheel(steps: Int) {
        if (steps == 0) return
        queueKeyboardCommandAfterPaste { scrollWheelNow(steps) }
    }

    private fun scrollWheelNow(steps: Int) {
        val socket: NanoKvmInputSocket?
        val buttons: Set<org.nanokvm.protocol.MouseButton>
        val absolute: Boolean
        val x: Int
        val y: Int
        synchronized(stateLock) {
            socket = input
            buttons = pressedMouseButtons.toSet().toProtocolButtons()
            absolute = usedAbsoluteMouse
            x = lastAbsoluteX
            y = lastAbsoluteY
        }
        if (absolute) {
            socket?.sendMouse(AbsoluteMouseReport.create(buttons = buttons, x = x, y = y, wheel = steps))
        } else {
            socket?.sendMouse(RelativeMouseReport.create(buttons = buttons, wheel = steps))
        }
    }

    /** NanoKVM exposes one wheel axis, so horizontal scroll uses an atomic Shift+wheel transaction. */
    override fun scrollHorizontal(steps: Int) {
        if (steps == 0) return
        queueKeyboardCommandAfterPaste { scrollHorizontalNow(steps) }
    }

    private fun scrollHorizontalNow(steps: Int) {
        synchronized(stateLock) {
            val socket = input ?: return
            val buttons = pressedMouseButtons.toSet().toProtocolButtons()
            val mouseReport = if (usedAbsoluteMouse) {
                AbsoluteMouseReport.create(
                    buttons = buttons,
                    x = lastAbsoluteX,
                    y = lastAbsoluteY,
                    wheel = steps,
                )
            } else {
                RelativeMouseReport.create(buttons = buttons, wheel = steps)
            }
            socket.sendShiftWheel(
                shiftedKeyboard = keyboardState.snapshotWithModifiers(setOf(HidModifier.LEFT_SHIFT)),
                mouse = mouseReport,
                restoredKeyboard = keyboardState.snapshot(),
            )
        }
    }

    override fun typeCommittedText(text: String, layout: KeyboardLayout) {
        if (text.isEmpty()) return
        queueKeyboardCommandAfterPaste {
            val socket: NanoKvmInputSocket?
            val heldModifiers: Set<HidModifier>
            synchronized(stateLock) {
                socket = input
                heldModifiers = keyboardState.modifiersSnapshot()
            }
            val result = socket?.sendCommittedText(
                text,
                layout.toProtocolLayout(),
                heldModifiers,
            ) ?: return@queueKeyboardCommandAfterPaste
            if (result.unsupported.isNotEmpty()) {
                mutableSession.update {
                    it.withActionFeedback(
                        ConsoleMessage.UnsupportedKeyboardCharacters(
                            result.unsupported.size,
                        ),
                    )
                }
            }
        }
    }

    override fun key(key: RemoteKey, pressed: Boolean) {
        queueKeyboardCommandAfterPaste {
            val socket: NanoKvmInputSocket?
            val report: HidKeyboardReport
            synchronized(stateLock) {
                socket = input
                val modifier = key.toModifier()
                val usage = key.toUsage()
                when {
                    modifier != null && pressed -> keyboardState.press(modifier)
                    modifier != null -> keyboardState.release(modifier)
                    usage != null && pressed -> keyboardState.press(usage)
                    usage != null -> keyboardState.release(usage)
                    else -> Unit
                }
                report = keyboardState.snapshot()
            }
            socket?.sendKeyboard(report)
        }
    }

    override fun releaseAllInput() {
        queueKeyboardCommandAfterPaste {
            releaseAllInputNow()
        }
    }

    override fun updateVideo(settings: VideoSettings) {
        scope.launch {
            lifecycleMutex.withLock {
                val activeSession = synchronized(stateLock) {
                    if (currentSessionBindingLocked() == null) null else authenticatedSession
                }
                if (activeSession == null) {
                    mutableSession.update {
                        it.withActionFeedback(ConsoleMessage.ConnectBeforeChangingVideoSettings)
                    }
                    return@withLock
                }
                mutableSession.update { it.withActionFeedback(ConsoleMessage.ApplyingVideoSettings) }
                try {
                    activeSession.console.updateScreen(
                        ScreenSetting.RESOLUTION,
                        settings.resolutionHeight,
                    )
                    activeSession.console.updateScreen(ScreenSetting.FPS, settings.framesPerSecond)
                    // NanoKVM overloads `quality`: bitrate values update H.264 while percentage
                    // values update MJPEG. Apply both so AUTO can fall back without stale settings.
                    activeSession.console.updateScreen(ScreenSetting.QUALITY, settings.bitrateKbps)
                    activeSession.console.updateScreen(ScreenSetting.QUALITY, settings.jpegQuality)
                    activeSession.console.updateScreen(ScreenSetting.GOP, settings.gopFrames)
                    videoSettings = settings
                    closeVideoAndAwaitDecoderReleaseLocked()
                    forgetVideoSurfaceLocked()
                    mutableSession.update { it.withAppliedVideoSettings(settings) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    mutableSession.update {
                        it.withActionFeedback(
                            ConsoleMessage.VideoSettingsNotApplied(
                                error.toConnectionFailure(),
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun setMjpegFrameDetectionPreference(enabled: Boolean) {
        mjpegFrameDetectionCoordinator.setPreference(enabled)
    }

    override fun setMjpegFrameDetectionEnabled(enabled: Boolean) {
        mjpegFrameDetectionCoordinator.setEnabledByUser(enabled)
    }

    override fun resetHid() {
        runControlAfterPaste(CONTROL_RESET_HID, ConsoleMessage.HidInterfaceReset) {
            releaseAllInputNow()
            it.console.resetHid()
        }
    }

    override fun pasteText(request: ApprovedPasteRequest) {
        if (request.content.isEmpty() || request.content.encodeToByteArray().size > MAX_PASTE_BYTES) {
            mutableSession.update {
                it.withActionFeedback(
                    ConsoleMessage.ClipboardTextOutsideByteLimit(MAX_PASTE_BYTES),
                )
            }
            return
        }
        installApprovedPaste(request)
    }

    override fun cancelPaste() {
        requestPasteCancellation(userInitiated = true)
    }

    override fun setPhase3SurfaceVisible(visible: Boolean) {
        phase3SurfaceVisible = visible
        if (visible) {
            startPhase3SurfaceWorkIfNeeded()
        } else {
            val jobs = synchronized(stateLock) {
                listOfNotNull(
                    phase3RefreshJob.also { phase3RefreshJob = null },
                    phase3TransferPollJob.also { phase3TransferPollJob = null },
                )
            }
            jobs.forEach { it.cancel(CancellationException("Phase 3 surface closed")) }
            mutableSession.update { current ->
                current.copy(phase3 = current.phase3.copy(loading = false))
            }
        }
    }

    override fun refreshPhase3() {
        startPhase3RefreshIfPossible()
    }

    override fun mountPhase3Image(
        destination: ApprovedPhase3Destination,
        imageId: Long,
        mode: Phase3ImageMountMode,
    ) {
        val selection = synchronized(stateLock) {
            phase3MediaCatalog?.let { catalog ->
                phase3MediaHandles[imageId]?.let { image -> catalog to image }
            }
        }
        if (selection == null) {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(
                    Phase3Notice.GuidanceReason.RefreshMediaBeforeSelectingImage,
                ),
                Phase3NoticeScope.VirtualMedia,
            )
            return
        }
        val action = Phase3Notice.Action.MountImage(mode)
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = true,
            noticeScope = Phase3NoticeScope.VirtualMedia,
        ) { gateway, binding ->
            phase3UsbMutationInFlight = true
            try {
                val result = gateway.mountImage(
                    selection.first,
                    selection.second,
                    when (mode) {
                        Phase3ImageMountMode.MassStorage -> NanoKvmImageMountMode.MASS_STORAGE
                        Phase3ImageMountMode.CdRom -> NanoKvmImageMountMode.CD_ROM
                    },
                )
                publishUsbMediaResult(
                    binding = binding,
                    result = result,
                    action = action,
                )
            } finally {
                resumeInputMonitoringAfterPhase3UsbMutation(binding)
            }
        }
    }

    override fun restorePhase3PhysicalMedia(destination: ApprovedPhase3Destination) {
        val action = Phase3Notice.Action.RestorePhysicalMedia
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = true,
            noticeScope = Phase3NoticeScope.VirtualMedia,
        ) { gateway, binding ->
            phase3UsbMutationInFlight = true
            try {
                val result = gateway.restorePhysicalMedia()
                publishUsbMediaResult(
                    binding = binding,
                    result = result,
                    action = action,
                )
            } finally {
                resumeInputMonitoringAfterPhase3UsbMutation(binding)
            }
        }
    }

    override fun deletePhase3Image(destination: ApprovedPhase3Destination, imageId: Long) {
        val selection = synchronized(stateLock) {
            phase3MediaCatalog?.let { catalog ->
                phase3MediaHandles[imageId]?.let { image -> catalog to image }
            }
        }
        if (selection == null) {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(
                    Phase3Notice.GuidanceReason.RefreshMediaBeforeDeletingImage,
                ),
                Phase3NoticeScope.VirtualMedia,
            )
            return
        }
        val action = Phase3Notice.Action.DeleteImage
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = true,
            noticeScope = Phase3NoticeScope.VirtualMedia,
        ) { gateway, binding ->
            val result = gateway.deleteImage(selection.first, selection.second)
            publishMediaMutation(binding, result, action)
        }
    }

    override fun setPhase3DiskEnabled(
        destination: ApprovedPhase3Destination,
        enabled: Boolean,
    ) {
        val action = Phase3Notice.Action.SetDiskEnabled(enabled)
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = true,
            noticeScope = Phase3NoticeScope.VirtualMedia,
        ) { gateway, binding ->
            phase3UsbMutationInFlight = true
            try {
                val result = gateway.setVirtualDeviceEnabled(NanoKvmVirtualDevice.DISK, enabled)
                publishUsbDeviceResult(
                    binding = binding,
                    result = result,
                    action = action,
                )
            } finally {
                resumeInputMonitoringAfterPhase3UsbMutation(binding)
            }
        }
    }

    override fun setPhase3HidMode(
        destination: ApprovedPhase3Destination,
        selection: Phase3HidModeSelection,
    ) {
        if (selection == Phase3HidModeSelection.Other) {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(Phase3Notice.GuidanceReason.UnknownHidModeIsReadOnly),
                Phase3NoticeScope.VirtualMedia,
            )
            return
        }
        val action = Phase3Notice.Action.SetHidMode(
            when (selection) {
                Phase3HidModeSelection.Normal -> Phase3Notice.HidMode.Normal
                Phase3HidModeSelection.HidOnly -> Phase3Notice.HidMode.HidOnly
                Phase3HidModeSelection.Other -> return
            },
        )
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = true,
            noticeScope = Phase3NoticeScope.VirtualMedia,
        ) { gateway, binding ->
            phase3UsbMutationInFlight = true
            try {
                val result = gateway.setHidMode(selection.toGatewaySelection())
                publishUsbHidModeResult(
                    binding = binding,
                    result = result,
                    action = action,
                )
            } finally {
                resumeInputMonitoringAfterPhase3UsbMutation(binding)
            }
        }
    }

    override fun setPhase3NetworkEnabled(
        destination: ApprovedPhase3Destination,
        enabled: Boolean,
    ) {
        val action = Phase3Notice.Action.SetNetworkEnabled(enabled)
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = true,
            noticeScope = Phase3NoticeScope.VirtualMedia,
        ) { gateway, binding ->
            phase3UsbMutationInFlight = true
            try {
                val result = gateway.setVirtualDeviceEnabled(NanoKvmVirtualDevice.NETWORK, enabled)
                publishUsbDeviceResult(
                    binding = binding,
                    result = result,
                    action = action,
                )
            } finally {
                resumeInputMonitoringAfterPhase3UsbMutation(binding)
            }
        }
    }

    override fun startPhase3ImageTransfer(
        destination: ApprovedPhase3Destination,
        sourceUrl: String,
    ) {
        val source = runCatching { NanoKvmRemoteImageUrl.parse(sourceUrl) }.getOrElse {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(Phase3Notice.GuidanceReason.EnterValidImageUrl),
                Phase3NoticeScope.VirtualMedia,
            )
            return
        }
        val action = Phase3Notice.Action.StartImageTransfer
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = true,
            noticeScope = Phase3NoticeScope.VirtualMedia,
        ) { gateway, binding ->
            val result = gateway.startImageTransfer(source)
            publishTransferMutation(
                binding,
                result,
                action,
            )
            phase3TransferSnapshot(result)?.let { snapshot ->
                startPhase3TransferPollingIfNeeded(binding, gateway, snapshot)
            }
        }
    }

    override fun sendPhase3WakeOnLan(
        destination: ApprovedPhase3Destination,
        macAddress: String,
    ) {
        val canonical = runCatching { NanoKvmMacAddress.parse(macAddress) }.getOrElse {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(Phase3Notice.GuidanceReason.EnterValidMacAddress),
                Phase3NoticeScope.WakeOnLan,
            )
            return
        }
        val action = Phase3Notice.Action.SendWakePacket
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = false,
            noticeScope = Phase3NoticeScope.WakeOnLan,
        ) { gateway, binding ->
            val result = gateway.sendWakeOnLan(canonical)
            publishWakeOnLanMutation(
                binding,
                result,
                action,
            )
        }
    }

    override fun renamePhase3WakeOnLanTarget(
        destination: ApprovedPhase3Destination,
        targetId: Long,
        name: String,
    ) {
        val selection = synchronized(stateLock) {
            phase3WakeSnapshot?.let { snapshot ->
                phase3WakeHandles[targetId]?.let { target -> snapshot to target }
            }
        }
        if (selection == null) {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(
                    Phase3Notice.GuidanceReason.RefreshWakeHistoryBeforeRenaming,
                ),
                Phase3NoticeScope.WakeOnLan,
            )
            return
        }
        val action = Phase3Notice.Action.RenameWakeTarget
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = false,
            noticeScope = Phase3NoticeScope.WakeOnLan,
        ) { gateway, binding ->
            val result = gateway.renameWakeOnLanTarget(selection.first, selection.second, name)
            publishWakeOnLanMutation(binding, result, action)
        }
    }

    override fun deletePhase3WakeOnLanTarget(
        destination: ApprovedPhase3Destination,
        targetId: Long,
    ) {
        val selection = synchronized(stateLock) {
            phase3WakeSnapshot?.let { snapshot ->
                phase3WakeHandles[targetId]?.let { target -> snapshot to target }
            }
        }
        if (selection == null) {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(
                    Phase3Notice.GuidanceReason.RefreshWakeHistoryBeforeDeleting,
                ),
                Phase3NoticeScope.WakeOnLan,
            )
            return
        }
        val action = Phase3Notice.Action.DeleteWakeTarget
        beginPhase3Action(
            destination = destination,
            action = action,
            releaseInput = false,
            noticeScope = Phase3NoticeScope.WakeOnLan,
        ) { gateway, binding ->
            val result = gateway.deleteWakeOnLanTarget(selection.first, selection.second)
            publishWakeOnLanMutation(binding, result, action)
        }
    }

    override fun setAdministrationSurfaceVisible(visible: Boolean) {
        administrationSurfaceVisible = visible
        if (visible) {
            startAdministrationSurfaceWorkIfNeeded()
        } else {
            val jobs = synchronized(stateLock) {
                listOfNotNull(
                    administrationRefreshJob.also { administrationRefreshJob = null },
                    administrationActionJob.also { administrationActionJob = null },
                )
            }
            jobs.forEach { it.cancel(CancellationException("Administration surface closed")) }
            mutableSession.update { current ->
                current.copy(
                    administration = AdministrationUiState(
                        available = current.connection.isSessionUsable,
                    ),
                )
            }
        }
    }

    override fun refreshAdministration() {
        startAdministrationRefreshIfPossible()
    }

    override fun setAdministrationPreviewUpdates(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = beginAdministrationAction(
        destination,
        AdministrationNotice.Action.SetPreviewUpdates(enabled),
    ) { gateway, binding, action ->
        publishAdministrationMutation(
            binding,
            gateway.setPreviewUpdatesEnabled(enabled),
            action,
        ) { state, snapshot -> state.copy(updates = snapshot.toUiState()) }
    }

    override fun startAdministrationOnlineUpdate(destination: ApprovedAdministrationDestination) =
        beginAdministrationAction(
            destination,
            AdministrationNotice.Action.StartOnlineUpdate,
        ) { gateway, binding, action ->
            publishAdministrationMutation(binding, gateway.startOnlineUpdate(), action) {
                    state, snapshot ->
                state.copy(updates = snapshot.toUiState())
            }
        }

    override fun rebootAdministrationAppliance(destination: ApprovedAdministrationDestination) =
        beginAdministrationAction(
            destination,
            AdministrationNotice.Action.RebootAppliance,
        ) { gateway, binding, action ->
            publishAdministrationMutation(binding, gateway.rebootSystem(), action) { state, _ ->
                state
            }
        }

    override fun setAdministrationOledSleep(
        destination: ApprovedAdministrationDestination,
        preset: AdministrationOledPreset,
    ) = beginAdministrationAction(
        destination,
        AdministrationNotice.Action.SetOledSleep(preset),
    ) { gateway, binding, action ->
        publishAdministrationMutation(
            binding,
            gateway.setOledSleep(preset.toProtocolPreset()),
            action,
        ) { state, snapshot -> state.copy(oled = snapshot.toUiState()) }
    }

    override fun setAdministrationSshEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = beginAdministrationAction(
        destination,
        AdministrationNotice.Action.SetSshEnabled(enabled),
    ) { gateway, binding, action ->
        publishAdministrationMutation(binding, gateway.setSshEnabled(enabled), action) {
                state, snapshot ->
            state.copy(sshEnabled = snapshot.enabled)
        }
    }

    override fun setAdministrationHostname(
        destination: ApprovedAdministrationDestination,
        hostname: String,
    ) = beginAdministrationAction(
        destination,
        AdministrationNotice.Action.SetHostname,
    ) { gateway, binding, action ->
        publishAdministrationMutation(binding, gateway.setHostname(hostname), action) {
                state, snapshot ->
            state.copy(hostname = snapshot.hostname)
        }
    }

    override fun setAdministrationMdnsEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = beginAdministrationAction(
        destination,
        AdministrationNotice.Action.SetMdnsEnabled(enabled),
    ) { gateway, binding, action ->
        publishAdministrationMutation(binding, gateway.setMdnsEnabled(enabled), action) {
                state, snapshot ->
            state.copy(mdnsEnabled = snapshot.enabled)
        }
    }

    override fun setAdministrationWebTitle(
        destination: ApprovedAdministrationDestination,
        title: String,
    ) = beginAdministrationAction(
        destination,
        AdministrationNotice.Action.SetWebTitle,
    ) { gateway, binding, action ->
        publishAdministrationMutation(binding, gateway.setCustomWebTitle(title), action) {
                state, snapshot ->
            state.copy(webTitle = snapshot.title, webTitleIsDefault = snapshot.isDefault)
        }
    }

    override fun resetAdministrationWebTitle(destination: ApprovedAdministrationDestination) =
        beginAdministrationAction(
            destination,
            AdministrationNotice.Action.ResetWebTitle,
        ) { gateway, binding, action ->
            publishAdministrationMutation(binding, gateway.resetWebTitle(), action) {
                    state, snapshot ->
                state.copy(webTitle = snapshot.title, webTitleIsDefault = snapshot.isDefault)
            }
        }

    override fun setAdministrationManualDns(
        destination: ApprovedAdministrationDestination,
        servers: List<String>,
    ) = beginAdministrationAction(
        destination,
        AdministrationNotice.Action.SetManualDns,
    ) { gateway, binding, action ->
        publishAdministrationMutation(binding, gateway.setManualDns(servers), action) {
                state, snapshot ->
            state.copy(dns = snapshot.toUiState())
        }
    }

    override fun setAdministrationDhcpDns(destination: ApprovedAdministrationDestination) =
        beginAdministrationAction(
            destination,
            AdministrationNotice.Action.SetDhcpDns,
        ) { gateway, binding, action ->
            publishAdministrationMutation(binding, gateway.setDhcpDns(), action) { state, snapshot ->
                state.copy(dns = snapshot.toUiState())
            }
        }

    override fun connectAdministrationWifi(
        destination: ApprovedAdministrationDestination,
        ssid: String,
        password: CharArray,
    ) = beginAdministrationAction(
        destination = destination,
        action = AdministrationNotice.Action.ConnectWifi,
        onCompletion = { password.fill('\u0000') },
    ) { gateway, binding, action ->
        publishAdministrationMutation(binding, gateway.connectWifi(ssid, password), action) {
                state, snapshot ->
            state.copy(wifi = snapshot.toUiState())
        }
    }

    override fun disconnectAdministrationWifi(destination: ApprovedAdministrationDestination) =
        beginAdministrationAction(
            destination,
            AdministrationNotice.Action.DisconnectWifi,
        ) { gateway, binding, action ->
            publishAdministrationMutation(binding, gateway.disconnectWifi(), action) {
                    state, snapshot ->
                state.copy(wifi = snapshot.toUiState())
            }
        }

    override fun executeAdministrationTailscale(
        destination: ApprovedAdministrationDestination,
        command: AdministrationTailscaleCommand,
    ) = beginAdministrationAction(
        destination,
        AdministrationNotice.Action.Tailscale(command),
    ) { gateway, binding, action ->
        if (command == AdministrationTailscaleCommand.Login) {
            when (val outcome = gateway.loginTailscale()) {
                is NanoKvmAdministrationTailscaleLoginOutcome.AuthorizationRequired -> {
                    if (currentAdministrationBinding() == binding) {
                        val request = newAdministrationHttpsNavigationRequest(
                            binding,
                            outcome.url.value,
                        )
                        updateAdministrationIfCurrent(binding) {
                            it.copy(
                                notice = AdministrationNotice.Guidance(
                                    AdministrationNotice.GuidanceReason
                                        .TailscaleAuthorizationReady,
                                ),
                                pendingHttpsNavigation = request,
                            )
                        }
                    }
                    null
                }
                is NanoKvmAdministrationTailscaleLoginOutcome.Completed ->
                    publishAdministrationMutation(
                        binding,
                        outcome.result,
                        action,
                    ) { state, snapshot -> state.copy(tailscale = snapshot.toUiState()) }
            }
        } else {
            publishAdministrationMutation(
                binding,
                gateway.executeTailscale(command.toProtocolCommand()),
                action,
            ) { state, snapshot -> state.copy(tailscale = snapshot.toUiState()) }
        }
    }

    override fun acknowledgeAdministrationNavigationOpened(
        destination: ApprovedAdministrationDestination,
        requestId: Long,
    ) {
        val binding = currentAdministrationBinding() ?: return
        if (!destination.matches(binding)) return
        updateAdministrationIfCurrent(binding) {
            it.acknowledgeOpenedHttpsNavigation(destination, requestId)
        }
    }

    override fun setAdministrationHdmiEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = beginDeviceControlAction(
        destination,
        AdministrationNotice.Action.SetHdmiEnabled(enabled),
    ) { gateway, binding, action ->
        publishDeviceControlMutation(
            binding = binding,
            result = gateway.setHdmiEnabled(enabled),
            action = action,
        ) { state, snapshot -> state.copy(hdmiEnabled = snapshot.enabled) }
    }

    override fun resetAdministrationHdmi(destination: ApprovedAdministrationDestination) =
        beginDeviceControlAction(
            destination,
            AdministrationNotice.Action.ResetHdmi,
        ) { gateway, binding, action ->
            publishDeviceControlMutation(
                binding = binding,
                result = gateway.resetHdmi(),
                action = action,
            ) { state, _ -> state }
        }

    override fun setAdministrationMouseJiggler(
        destination: ApprovedAdministrationDestination,
        selection: AdministrationMouseJigglerSelection,
    ) {
        if (selection == AdministrationMouseJigglerSelection.Other) {
            rejectAdministrationUiCommand(
                AdministrationNotice.Guidance(
                    AdministrationNotice.GuidanceReason.UnknownMouseJigglerModeIsReadOnly,
                ),
            )
            return
        }
        val enabled = selection != AdministrationMouseJigglerSelection.Off
        val mode = when (selection) {
            AdministrationMouseJigglerSelection.Off,
            AdministrationMouseJigglerSelection.Relative -> NanoKvmMouseJigglerMode.Relative
            AdministrationMouseJigglerSelection.Absolute -> NanoKvmMouseJigglerMode.Absolute
            AdministrationMouseJigglerSelection.Other -> return
        }
        val action = AdministrationNotice.Action.SetMouseJiggler(
            when (selection) {
                AdministrationMouseJigglerSelection.Off ->
                    AdministrationNotice.MouseJigglerMode.Off
                AdministrationMouseJigglerSelection.Relative ->
                    AdministrationNotice.MouseJigglerMode.Relative
                AdministrationMouseJigglerSelection.Absolute ->
                    AdministrationNotice.MouseJigglerMode.Absolute
                AdministrationMouseJigglerSelection.Other -> return
            },
        )
        beginDeviceControlAction(destination, action) { gateway, binding, ownedAction ->
            publishDeviceControlMutation(
                binding = binding,
                result = gateway.setMouseJiggler(enabled, mode),
                action = ownedAction,
            ) { state, snapshot -> state.copy(mouseJiggler = snapshot.toUiState()) }
        }
    }

    override fun setAdministrationMemoryLimitEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = beginDeviceControlAction(
        destination,
        AdministrationNotice.Action.SetMemoryLimitEnabled(enabled),
    ) { gateway, binding, action ->
        publishDeviceControlMutation(
            binding = binding,
            result = gateway.setMemoryLimitEnabled(enabled),
            action = action,
        ) { state, snapshot -> state.copy(memoryLimit = snapshot.toUiState()) }
    }

    override fun setAdministrationSwapSize(
        destination: ApprovedAdministrationDestination,
        preset: AdministrationSwapPreset,
    ) = beginDeviceControlAction(
        destination,
        AdministrationNotice.Action.SetSwapSize(preset),
    ) { gateway, binding, action ->
        publishDeviceControlMutation(
            binding = binding,
            result = gateway.setSwapSize(preset.toProtocolPreset()),
            action = action,
        ) { state, snapshot -> state.copy(swap = snapshot.toUiState()) }
    }

    override fun enableAdministrationTls(destination: ApprovedAdministrationDestination) =
        beginDeviceControlAction(
            destination,
            AdministrationNotice.Action.EnableTls,
        ) { gateway, binding, action ->
            publishDeviceControlMutation(
                binding = binding,
                result = gateway.enableApplianceTls(),
                action = action,
            ) { state, _ -> state }
        }

    override fun setOperatorSurfaceVisible(visible: Boolean) {
        val installedBinding = operatorLifecycle.binding()
        val installedGateway = installedBinding?.let(operatorLifecycle::resolve)
        operatorSurfaceVisible = visible
        if (visible) {
            mutableOperatorState.update {
                it.copy(available = mutableSession.value.connection.isSessionUsable)
            }
            installedGateway?.terminal?.onForeground()
            startOperatorSurfaceWorkIfNeeded()
        } else {
            installedGateway?.terminal?.onBackground()
            val jobs = synchronized(stateLock) {
                clearOperatorScriptHandlesLocked()
                listOfNotNull(
                    operatorScriptRefreshJob.also { operatorScriptRefreshJob = null },
                    operatorActionJob.also { operatorActionJob = null },
                    operatorTerminalStateJob.also { operatorTerminalStateJob = null },
                    operatorTerminalOutputJob.also { operatorTerminalOutputJob = null },
                )
            }
            jobs.forEach { it.cancel(CancellationException("Operator surface closed")) }
            mutableOperatorState.value = OperatorUiState(
                available = mutableSession.value.connection.isSessionUsable,
            )
        }
    }

    override fun refreshOperatorScripts() {
        startOperatorScriptRefreshIfPossible()
    }

    override fun enterOperatorTerminal(destination: ApprovedOperatorDestination) {
        beginOperatorAction(
            destination,
            OperatorNotice.Action.EnterTerminal,
        ) { gateway, binding, action ->
            val approval = gateway.terminal.recordExplicitElevatedEntryApproval()
            publishOperatorTerminalResult(
                binding,
                gateway.terminal.enter(approval),
                action,
            )
        }
    }

    override fun closeOperatorTerminal(destination: ApprovedOperatorDestination) {
        val selection = resolveOperatorGateway(destination) ?: return
        selection.first.terminal.onBackground()
        if (operatorSurfaceVisible && currentOperatorBinding() == selection.second) {
            selection.first.terminal.onForeground()
        }
        updateOperatorIfCurrent(selection.second) {
            it.copy(
                terminalPhase = OperatorTerminalUiPhase.Inactive,
                serialActive = false,
                notice = OperatorNotice.Guidance(
                    OperatorNotice.GuidanceReason.TerminalClosedNeedsFreshConfirmation,
                ),
            )
        }
    }

    override fun sendOperatorTerminalInput(
        destination: ApprovedOperatorDestination,
        text: String,
    ) {
        val selection = resolveOperatorGateway(destination) ?: return
        if (text.isEmpty()) return
        publishOperatorTerminalResult(
            selection.second,
            selection.first.terminal.sendInput(text),
            OperatorNotice.Action.SendTerminalInput,
            publishSuccessNotice = false,
        )
    }

    override fun resizeOperatorTerminal(
        destination: ApprovedOperatorDestination,
        rows: Int,
        columns: Int,
    ) {
        val selection = resolveOperatorGateway(destination) ?: return
        val size = runCatching { NanoKvmTerminalSize(rows, columns) }.getOrElse {
            rejectOperatorUiCommand(
                OperatorNotice.Guidance(OperatorNotice.GuidanceReason.InvalidTerminalDimensions),
            )
            return
        }
        publishOperatorTerminalResult(
            selection.second,
            selection.first.terminal.resize(size),
            OperatorNotice.Action.ResizeTerminal,
        )
    }

    override fun startOperatorSerial(
        destination: ApprovedOperatorDestination,
        configuration: OperatorSerialConfiguration,
    ) {
        beginOperatorAction(
            destination,
            OperatorNotice.Action.StartSerial,
        ) { gateway, binding, action ->
            publishOperatorTerminalResult(
                binding,
                gateway.terminal.startSerial(configuration.toProtocolConfiguration()),
                action,
            )
        }
    }

    override fun exitOperatorSerial(destination: ApprovedOperatorDestination) {
        beginOperatorAction(
            destination,
            OperatorNotice.Action.ExitSerial,
        ) { gateway, binding, action ->
            publishOperatorTerminalResult(
                binding,
                gateway.terminal.exitSerial(),
                action,
            )
        }
    }

    override fun uploadOperatorScript(
        destination: ApprovedOperatorDestination,
        request: OperatorScriptUploadRequest,
    ) {
        val accepted = beginOperatorAction(
            destination = destination,
            action = OperatorNotice.Action.UploadScript,
            onCompletion = request::clear,
        ) { gateway, binding, action ->
            clearOperatorScriptHandles()
            val result = gateway.uploadScript(request.fileName, request.content)
            publishOperatorScriptResult(binding, result, action)
            refreshOperatorScriptsAfterMutation(binding, gateway)
        }
        if (!accepted) request.clear()
    }

    override fun runOperatorScript(
        destination: ApprovedOperatorDestination,
        scriptId: Long,
        mode: OperatorScriptRunMode,
    ) {
        val selection = resolveOperatorScript(scriptId)
        if (selection == null) {
            rejectOperatorUiCommand(
                OperatorNotice.Guidance(OperatorNotice.GuidanceReason.RefreshScriptCatalog),
            )
            return
        }
        beginOperatorAction(
            destination,
            OperatorNotice.Action.RunScript(mode),
        ) { gateway, binding, action ->
            clearOperatorScriptHandles()
            val result = gateway.runScript(
                selection.first,
                selection.second,
                when (mode) {
                    OperatorScriptRunMode.Foreground -> NanoKvmScriptRunMode.FOREGROUND
                    OperatorScriptRunMode.Background -> NanoKvmScriptRunMode.BACKGROUND
                },
            )
            if (result is NanoKvmOperatorScriptCommandResult.Completed) {
                val output = result.value.output.content
                mutableOperatorOutput.tryEmit(
                    OperatorEphemeralOutput(OperatorOutputKind.Script, output),
                )
            }
            publishOperatorScriptResult(binding, result, action)
            refreshOperatorScriptsAfterMutation(binding, gateway)
        }
    }

    override fun deleteOperatorScript(
        destination: ApprovedOperatorDestination,
        scriptId: Long,
    ) {
        val selection = resolveOperatorScript(scriptId)
        if (selection == null) {
            rejectOperatorUiCommand(
                OperatorNotice.Guidance(OperatorNotice.GuidanceReason.RefreshScriptCatalog),
            )
            return
        }
        beginOperatorAction(
            destination,
            OperatorNotice.Action.DeleteScript,
        ) { gateway, binding, action ->
            clearOperatorScriptHandles()
            val result = gateway.deleteScript(selection.first, selection.second)
            publishOperatorScriptResult(binding, result, action)
            refreshOperatorScriptsAfterMutation(binding, gateway)
        }
    }

    override fun setAutomationSurfaceVisible(visible: Boolean) {
        val gateway = synchronized(stateLock) {
            automationSurfaceVisible = visible
            automationLifecycle.binding()?.let(automationLifecycle::resolve)
        }
        if (visible && foreground) gateway?.onForeground() else gateway?.onBackground()
    }

    override fun currentAutomationGateway(): NanoKvmAutomationGateway? {
        val binding = currentAutomationBinding() ?: return null
        return automationLifecycle.resolve(binding)
    }

    override fun setOfflineUpdateSurfaceVisible(visible: Boolean) {
        val gateway = synchronized(stateLock) {
            offlineUpdateSurfaceVisible = visible
            offlineUpdateLifecycle.binding()?.let(offlineUpdateLifecycle::resolve)
        }
        gateway?.setForeground(foreground)
        gateway?.setSurfaceVisible(visible)
    }

    override fun currentOfflineUpdateGateway(): NanoKvmOfflineUpdateGateway? {
        val binding = currentOfflineUpdateBinding() ?: return null
        return offlineUpdateLifecycle.resolve(binding)
    }

    override fun createPasswordChangeCoordinator(
        request: NanoKvmPasswordChangeRequest,
    ): NanoKvmPasswordChangeCoordinator? {
        val binding = currentAdministrationBinding() ?: return null
        val gateway = administrationLifecycle.resolve(binding) ?: return null
        if (!request.destination.matches(binding)) return null
        if (
            request.profile.id != binding.profileId ||
            request.profile.authority != binding.authority
        ) {
            return null
        }
        return NanoKvmPasswordChangeCoordinator(
            gateway = gateway,
            profile = request.profile,
            currentBinding = ::currentAdministrationBinding,
            savedCredentials = request.savedCredentials,
            profilesRepository = request.profilesRepository,
            sessionTerminator = request.sessionTerminator,
            onAuthenticationExpired = { expirePasswordChangeAuthentication(binding) },
        )
    }

    override fun setPicoClawSurfaceVisible(visible: Boolean) {
        picoClawSurfaceVisible = visible
        if (visible) {
            startPicoClawSurfaceWorkIfNeeded()
            return
        }

        // Hidden surfaces own no chat, history, model-config, or runtime work. Reinstalling a fresh
        // local gateway preserves discoverability while forcing the broad warning on every entry.
        val replacement = synchronized(stateLock) {
            val active = authenticatedSession
            val generation = mutableSession.value.sessionGeneration
            if (
                active != null && mutableSession.value.connection.isSessionUsable &&
                !closed && foreground && acceptingCommands
            ) active to generation else null
        }
        invalidatePicoClawGateway()
        replacement?.let { (active, generation) ->
            synchronized(stateLock) { installPicoClawGatewayLocked(active, generation) }
        }
    }

    override fun enterPicoClaw(destination: ApprovedPicoClawDestination) {
        beginPicoClawAction(destination, PicoClawNotice.Action.EnterFeature) { gateway, binding ->
            when (val result = gateway.enterFeature()) {
                is NanoKvmPicoClawReadResult.Success -> {
                    publishPicoClawRuntime(binding, result.state)
                    publishPicoClawNotice(
                        binding,
                        PicoClawNotice.ActionOutcome(
                            action = PicoClawNotice.Action.EnterFeature,
                            outcome = PicoClawNotice.Outcome.EnteredAndProbed,
                        ),
                    )
                }
                is NanoKvmPicoClawReadResult.Failure ->
                    publishPicoClawError(binding, result.error)
            }
        }
    }

    override fun refreshPicoClaw(destination: ApprovedPicoClawDestination) {
        beginPicoClawAction(destination, PicoClawNotice.Action.RefreshRuntime) {
                gateway, binding ->
            when (val result = gateway.refreshRuntime()) {
                is NanoKvmPicoClawReadResult.Success -> publishPicoClawRuntime(binding, result.state)
                is NanoKvmPicoClawReadResult.Failure -> publishPicoClawError(binding, result.error)
            }
        }
    }

    override fun installPicoClawRuntime(destination: ApprovedPicoClawDestination) {
        val action = PicoClawNotice.Action.InstallRuntime
        beginPicoClawAction(destination, action) { gateway, binding ->
            publishPicoClawMutation(binding, gateway.installRuntime(), action)
        }
    }

    override fun startPicoClawRuntime(destination: ApprovedPicoClawDestination) {
        val action = PicoClawNotice.Action.StartRuntime
        beginPicoClawAction(destination, action) { gateway, binding ->
            publishPicoClawMutation(binding, gateway.startRuntime(), action)
        }
    }

    override fun stopPicoClawRuntime(destination: ApprovedPicoClawDestination) {
        val action = PicoClawNotice.Action.StopRuntime
        beginPicoClawAction(destination, action) { gateway, binding ->
            publishPicoClawMutation(binding, gateway.stopRuntime(), action)
        }
    }

    override fun uninstallPicoClawRuntime(destination: ApprovedPicoClawDestination) {
        val action = PicoClawNotice.Action.UninstallRuntime
        beginPicoClawAction(destination, action) { gateway, binding ->
            val consent = gateway.recordUninstallConsentAfterWarning()
            publishPicoClawMutation(
                binding,
                gateway.uninstallRuntime(consent),
                action,
            )
        }
    }

    override fun setPicoClawProfile(
        destination: ApprovedPicoClawDestination,
        profile: PicoClawProfile,
    ) {
        val action = PicoClawNotice.Action.SetAgentProfile(profile)
        beginPicoClawAction(destination, action) { gateway, binding ->
            publishPicoClawMutation(
                binding,
                gateway.setAgentProfile(
                    when (profile) {
                        PicoClawProfile.Default -> NanoKvmPicoClawAgentProfile.DEFAULT
                        PicoClawProfile.Kvm -> NanoKvmPicoClawAgentProfile.KVM
                    },
                ),
                action,
            )
        }
    }

    override fun configurePicoClawModel(
        destination: ApprovedPicoClawDestination,
        request: PicoClawModelConfigurationRequest,
    ) {
        val update = runCatching {
            NanoKvmPicoClawModelUpdate.takeOwnership(
                model = request.model,
                apiBase = NanoKvmPicoClawApiBase.parse(request.apiBase),
                apiKey = request.apiKey,
            )
        }.getOrElse {
            request.clear()
            rejectPicoClawUiCommand(
                PicoClawNotice.Guidance(
                    PicoClawNotice.GuidanceReason.EnterValidModelConfiguration,
                ),
            )
            return
        }
        val action = PicoClawNotice.Action.ConfigureModel
        val accepted = beginPicoClawAction(
            destination,
            action,
            onCompletion = request::clear,
        ) {
                gateway, binding ->
            publishPicoClawMutation(
                binding,
                gateway.updateModel(update),
                action,
            )
        }
        if (!accepted) {
            update.clear()
            request.clear()
        }
    }

    override fun refreshPicoClawHistories(destination: ApprovedPicoClawDestination) {
        beginPicoClawAction(destination, PicoClawNotice.Action.RefreshHistories) {
                gateway, binding ->
            when (val result = gateway.refreshHistories()) {
                is NanoKvmPicoClawReadResult.Success -> publishPicoClawHistories(binding, result.state)
                is NanoKvmPicoClawReadResult.Failure -> publishPicoClawError(binding, result.error)
            }
        }
    }

    override fun loadPicoClawHistory(
        destination: ApprovedPicoClawDestination,
        historyId: Long,
    ) {
        val selection = resolvePicoClawHistory(historyId)
        if (selection == null) {
            rejectPicoClawUiCommand(
                PicoClawNotice.Guidance(
                    PicoClawNotice.GuidanceReason.RefreshHistoryBeforeOpening,
                ),
            )
            return
        }
        beginPicoClawAction(destination, PicoClawNotice.Action.OpenHistory) { gateway, binding ->
            when (val result = gateway.historyDetail(selection.first, selection.second)) {
                is NanoKvmPicoClawReadResult.Success -> updatePicoClawIfCurrent(binding) {
                    it.copy(
                        selectedHistoryTitle = selection.second.title,
                        selectedHistoryMessages = result.state.messages.map { message ->
                            PicoClawMessageUiState(
                                PicoClawMessageContent.ApplianceText(
                                    role = when (message.role) {
                                        org.nanokvm.protocol.NanoKvmPicoClawHistoryRole.USER ->
                                            PicoClawMessageRole.User
                                        org.nanokvm.protocol.NanoKvmPicoClawHistoryRole.ASSISTANT ->
                                            PicoClawMessageRole.Assistant
                                    },
                                    value = message.content,
                                ),
                            )
                        },
                    )
                }
                is NanoKvmPicoClawReadResult.Failure -> publishPicoClawError(binding, result.error)
            }
        }
    }

    override fun deletePicoClawHistory(
        destination: ApprovedPicoClawDestination,
        historyId: Long,
    ) {
        val selection = resolvePicoClawHistory(historyId)
        if (selection == null) {
            rejectPicoClawUiCommand(
                PicoClawNotice.Guidance(
                    PicoClawNotice.GuidanceReason.RefreshHistoryBeforeDeleting,
                ),
            )
            return
        }
        val action = PicoClawNotice.Action.DeleteHistory
        beginPicoClawAction(destination, action) { gateway, binding ->
            val consent = gateway.recordHistoryDeletionConsent(selection.first, selection.second)
            when (val result = gateway.deleteHistory(selection.first, selection.second, consent)) {
                is NanoKvmPicoClawHistoryDeleteResult.Applied -> {
                    publishPicoClawHistories(binding, result.catalog)
                    publishPicoClawNotice(
                        binding,
                        PicoClawNotice.ActionOutcome(
                            action = action,
                            outcome = PicoClawNotice.Outcome.HistoryDeleted,
                        ),
                    )
                }
                is NanoKvmPicoClawHistoryDeleteResult.Reconciled -> publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(
                        action = action,
                        outcome = when (result.observation) {
                        NanoKvmPicoClawHistoryDeleteObservation.ABSENT ->
                            PicoClawNotice.Outcome.HistoryAbsentAfterLostResponse
                        NanoKvmPicoClawHistoryDeleteObservation.PRESENT ->
                            PicoClawNotice.Outcome.HistoryPresentAfterLostResponse
                        NanoKvmPicoClawHistoryDeleteObservation.UNKNOWN ->
                            PicoClawNotice.Outcome.HistoryOutcomeUnknown
                        },
                    ),
                )
                is NanoKvmPicoClawHistoryDeleteResult.Accepted -> publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(
                        action = action,
                        outcome = PicoClawNotice.Outcome.HistoryAcceptedWithoutRefresh,
                    ),
                )
                is NanoKvmPicoClawHistoryDeleteResult.Indeterminate -> publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(
                        action = action,
                        outcome = PicoClawNotice.Outcome.HistoryOutcomeUnknown,
                    ),
                )
                is NanoKvmPicoClawHistoryDeleteResult.Rejected ->
                    publishPicoClawError(binding, result.error)
            }
        }
    }

    override fun openPicoClawChat(destination: ApprovedPicoClawDestination) {
        val action = PicoClawNotice.Action.OpenChat
        val selection = resolvePicoClawGateway(destination) ?: return
        val consent = selection.first.recordBroadControlConsentAfterDisclosure()
        val result = selection.first.chat.open(consent)
        publishPicoClawChatAction(selection.second, result, action)
    }

    override fun sendPicoClawChatMessage(
        destination: ApprovedPicoClawDestination,
        content: String,
    ) {
        val selection = resolvePicoClawGateway(destination) ?: return
        val action = PicoClawNotice.Action.SendChatMessage
        val result = selection.first.chat.sendMessage(content)
        if (result is NanoKvmPicoClawChatActionResult.MessageDispatched) {
            updatePicoClawIfCurrent(selection.second) {
                it.copy(chatMessages = appendBoundedPicoClawMessage(
                    it.chatMessages,
                    result.message,
                ))
            }
        }
        publishPicoClawChatAction(selection.second, result, action)
    }

    override fun cancelPicoClawChat(destination: ApprovedPicoClawDestination) {
        val selection = resolvePicoClawGateway(destination) ?: return
        publishPicoClawChatAction(
            selection.second,
            selection.first.chat.cancelRun(),
            PicoClawNotice.Action.CancelChat,
        )
    }

    override fun closeAndReleasePicoClaw(destination: ApprovedPicoClawDestination) {
        val action = PicoClawNotice.Action.CloseAndRelease
        beginPicoClawAction(destination, action) { gateway, binding ->
            when (val result = gateway.chat.closeAndRelease()) {
                NanoKvmPicoClawChatReleaseResult.Released -> publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(action, PicoClawNotice.Outcome.Released),
                )
                NanoKvmPicoClawChatReleaseResult.HeldByOther -> publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(
                        action,
                        PicoClawNotice.Outcome.HeldByOtherSession,
                    ),
                )
                is NanoKvmPicoClawChatReleaseResult.Indeterminate -> publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(
                        action,
                        PicoClawNotice.Outcome.ReleaseUnconfirmed,
                    ),
                )
                is NanoKvmPicoClawChatReleaseResult.Rejected ->
                    publishPicoClawError(binding, result.error)
            }
        }
    }

    override fun power(destination: ApprovedCoreDestination, action: PowerAction) {
        val approvedBinding = synchronized(stateLock) {
            currentSessionBindingLocked()?.takeIf(destination::matches)
        }
        if (approvedBinding == null) {
            publishCoreDestinationChanged()
            return
        }
        if (action is PowerAction.CtrlAltDelete) {
            runControlAfterPaste(
                key = CONTROL_CTRL_ALT_DELETE,
                successMessage = ConsoleMessage.CtrlAltDeleteSent,
                expectedBinding = approvedBinding,
            ) {
                val socket = synchronized(stateLock) { input }
                socket?.sendKeyboardChord(
                    modifiers = setOf(HidModifier.LEFT_CONTROL, HidModifier.LEFT_ALT),
                    keys = listOf(HidUsage.DELETE_FORWARD),
                )
                synchronized(stateLock) {
                    keyboardState.releaseAll()
                }
            }
            return
        }
        runControlAfterPaste(
            key = CONTROL_GPIO,
            successMessage = ConsoleMessage.HostControlSent,
            expectedBinding = approvedBinding,
        ) { activeSession ->
            when (action) {
                PowerAction.ShortPress -> activeSession.console.pressGpio(GpioAction.POWER, 800)
                PowerAction.Reset -> activeSession.console.pressGpio(GpioAction.RESET, 800)
                is PowerAction.LongPress -> activeSession.console.pressGpio(
                    GpioAction.POWER,
                    action.seconds.coerceIn(1, 30) * 1_000L,
                )
                PowerAction.CtrlAltDelete -> Unit
            }
        }
    }

    override fun close() {
        closeCommandAcceptanceBarrier()
        val resources = synchronized(stateLock) {
            if (closed) return
            closed = true
            foreground = false
            val detachedInput = input
            input = null
            surface = null
            CloseResources(
                input = detachedInput,
                connectJob = activeConnectJob.also { activeConnectJob = null },
            )
        }
        controlGate.invalidate()
        cancelReconnectJob()
        resources.connectJob?.cancel(CancellationException("Console backend closed"))
        scope.cancel()
        runCatching { releaseAllInputNow(resources.input) }
        runCatching { resources.input?.close() }
        synchronized(stateLock) {
            mutableSession.value = BackendSession(connection = ConnectionState.Disconnected)
        }
        closeScope.launch {
            try {
                cancelPasteAndJoin(userInitiated = false)
                lifecycleMutex.withLock { cleanupSessionLocked(forgetClient = true) }
            } finally {
                videoCallbacks.shutdownNow()
                closeCompletion.complete(Unit)
                closeScope.cancel()
            }
        }
    }

    override suspend fun closeAndAwait() {
        close()
        closeCompletion.await()
    }

    private fun scheduleReconnect(
        failure: ReconnectFailure,
        immediateFirstAttempt: Boolean,
        expectedBinding: NanoKvmSessionBinding? = null,
    ) {
        if (failure.cause is CancellationException) return
        if (failure.isAuthenticationExpiry()) {
            expectedBinding?.let(::expireAuthenticatedSession)
            return
        }
        if (!closeCommandAcceptanceBarrier(expectedBinding)) return
        if (failure.disposition() == ReconnectFailureDisposition.TERMINAL) {
            if (expectedBinding == null) {
                publishTerminalFailure(failure)
            } else {
                publishTerminalFailureWhenBindingStillCurrent(expectedBinding, failure)
            }
            return
        }
        val created = synchronized(stateLock) {
            if (
                closed || !foreground || client == null || reconnectJob?.isActive == true ||
                (expectedBinding != null &&
                    !matchesAuthenticatedSessionBindingLocked(expectedBinding))
            ) {
                return
            }
            scope.launch(start = CoroutineStart.LAZY) {
                val self = checkNotNull(currentCoroutineContext()[Job])
                try {
                    runReconnectPolicy(immediateFirstAttempt, expectedBinding, self)
                } finally {
                    synchronized(stateLock) {
                        if (reconnectJob === self) reconnectJob = null
                    }
                }
            }.also {
                controlGate.invalidate()
                reconnectJob = it
            }
        }
        created.start()
    }

    private fun cancelReconnectJob() {
        val old = synchronized(stateLock) {
            reconnectJob.also { reconnectJob = null }
        }
        old?.cancel(CancellationException("Reconnect no longer applicable"))
    }

    private fun isReconnectRunCurrent(reconnectRun: Job): Boolean = synchronized(stateLock) {
        isReconnectRunCurrentLocked(reconnectRun)
    }

    private fun isReconnectRunCurrentLocked(reconnectRun: Job): Boolean {
        check(Thread.holdsLock(stateLock))
        return reconnectJob === reconnectRun && reconnectRun.isActive
    }

    private suspend fun runReconnectPolicy(
        immediateFirstAttempt: Boolean,
        expectedBinding: NanoKvmSessionBinding?,
        reconnectRun: Job,
    ) {
        cancelPasteAndJoin(userInitiated = false)
        val prepared = lifecycleMutex.withLock {
            if (
                !isReconnectRunCurrent(reconnectRun) || closed || !foreground || client == null ||
                (expectedBinding != null && !synchronized(stateLock) {
                    matchesAuthenticatedSessionBindingLocked(expectedBinding)
                })
            ) {
                return@withLock false
            }
            // This is serialized with connect/replacement ownership. A late failure cannot clear
            // feature owners installed by a newer binding after its initial callback check.
            invalidateSessionFeatureSet()
            synchronized(stateLock) {
                if (!isReconnectRunCurrentLocked(reconnectRun)) return@withLock false
                mutableSession.value = mutableSession.value.copy(
                    connection = ConnectionState.Reconnecting,
                    status = ConsoleMessage.PreparingToReconnect,
                )
            }
            stopStreamingLocked()
            if (!isReconnectRunCurrent(reconnectRun)) return@withLock false
            forgetVideoSurfaceLocked()
            synchronized(stateLock) {
                if (!isReconnectRunCurrentLocked(reconnectRun)) return@withLock false
                mutableSession.value = mutableSession.value.copy(
                    videoSurfaceGeneration = mutableSession.value.videoSurfaceGeneration + 1,
                )
            }
            true
        }
        if (!prepared) return

        val result = reconnectPolicy.execute(
            immediateFirstAttempt = immediateFirstAttempt,
            onWaiting = { progress ->
                val delaySeconds = ((progress.delayMillis + 999L) / 1_000L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                synchronized(stateLock) {
                    if (isReconnectRunCurrentLocked(reconnectRun)) {
                        mutableSession.value = mutableSession.value.copy(
                            connection = ConnectionState.Reconnecting,
                            status = if (progress.delayMillis == 0L) {
                                ConsoleMessage.ReconnectAttempt(
                                    progress.attempt,
                                    progress.maximumAttempts,
                                )
                            } else {
                                ConsoleMessage.ReconnectAttemptDelayed(
                                    progress.attempt,
                                    progress.maximumAttempts,
                                    delaySeconds,
                                )
                            },
                            reconnectAttempt = progress.attempt,
                            reconnectMaximumAttempts = progress.maximumAttempts,
                            nextReconnectDelayMillis = progress.delayMillis,
                        )
                    }
                }
            },
            attempt = { reconnectInputOnce(reconnectRun) },
        )

        if (!isReconnectRunCurrent(reconnectRun)) return
        when (result) {
            ReconnectRunResult.Connected -> Unit
            is ReconnectRunResult.Terminal -> if (result.failure.isAuthenticationExpiry()) {
                if (isReconnectRunCurrent(reconnectRun)) {
                    expectedBinding?.let(::expireAuthenticatedSession)
                }
            } else {
                synchronized(stateLock) {
                    if (!isReconnectRunCurrentLocked(reconnectRun)) return
                    mutableSession.value = mutableSession.value.copy(
                        connection = ConnectionState.Failed,
                        status = ConsoleMessage.ConnectionFailed(
                            result.failure.toConnectionFailure(),
                        ),
                        reconnectAttempt = null,
                        reconnectMaximumAttempts = null,
                        nextReconnectDelayMillis = null,
                    )
                }
            }
            is ReconnectRunResult.Exhausted -> synchronized(stateLock) {
                if (!isReconnectRunCurrentLocked(reconnectRun)) return
                mutableSession.value = mutableSession.value.copy(
                    connection = ConnectionState.Failed,
                    status = ConsoleMessage.ReconnectStopped(
                        result.attempts,
                        result.failure.toConnectionFailure(),
                    ),
                    reconnectAttempt = null,
                    reconnectMaximumAttempts = null,
                    nextReconnectDelayMillis = null,
                )
            }
        }
    }

    private suspend fun reconnectInputOnce(
        reconnectRun: Job,
    ): ReconnectAttemptResult = lifecycleMutex.withLock {
        if (!isReconnectRunCurrent(reconnectRun) || closed || !foreground) {
            throw CancellationException("Reconnect is no longer applicable")
        }
        val activeClient = client ?: throw CancellationException("Session was disconnected")
        val activeSession = authenticatedSession ?: throw CancellationException("Session was disconnected")
        var pendingInput: NanoKvmInputSocket? = null
        var failureStatus: Int? = null
        try {
            val createdInput = activeClient.newInputSocket()
            pendingInput = createdInput
            createdInput.connect()
            val state = withTimeoutOrNull(INPUT_CONNECT_TIMEOUT_MS) {
                createdInput.state.first {
                    it is InputConnectionState.Connected || it is InputConnectionState.Failed
                }
            } ?: throw SocketTimeoutException("NanoKVM input WebSocket timed out")
            if (state is InputConnectionState.Failed) {
                failureStatus = state.httpStatus
                throw state.cause
            }
            synchronized(stateLock) {
                if (!isReconnectRunCurrentLocked(reconnectRun)) {
                    throw CancellationException("Reconnect is no longer applicable")
                }
                val sessionGeneration = ++sessionGenerationCounter
                input = createdInput
                installSessionFeatureSetLocked(activeSession, sessionGeneration)
                mutableSession.update {
                    it.copy(
                        connection = ConnectionState.Connected,
                        sessionGeneration = sessionGeneration,
                        phase3 = Phase3FeatureUiState(available = true),
                        administration = AdministrationUiState(available = true),
                        pasteProgress = null,
                        status = null,
                        lastActionFeedback = null,
                        reconnectAttempt = null,
                        reconnectMaximumAttempts = null,
                        nextReconnectDelayMillis = null,
                    )
                }
                openCommandAcceptanceLocked()
            }
            pendingInput = null
            activateOfflineUpdateGatewayIfCurrent()
            monitorInput(createdInput, checkNotNull(currentMjpegFrameDetectionBinding()))
            monitorApplianceStatus(activeSession)
            startVideoIfReadyLocked()
            startPhase3SurfaceWorkIfNeeded()
            startAdministrationSurfaceWorkIfNeeded()
            startOperatorSurfaceWorkIfNeeded()
            startPicoClawSurfaceWorkIfNeeded()
            ReconnectAttemptResult.Connected
        } catch (error: Throwable) {
            pendingInput?.close()
            if (error is CancellationException) throw error
            ReconnectAttemptResult.Failed(ReconnectFailure(error, failureStatus))
        }
    }

    private suspend fun startVideoIfReadyLocked(knownToken: SessionToken? = null) {
        if (!foreground || !mutableSession.value.connection.isSessionUsable) return
        val activeClient = client ?: return
        val output = synchronized(stateLock) { surface } ?: return
        if (!output.isValid) return
        val token = knownToken?.token ?: activeClient.tokenStore.read() ?: return
        closeVideoAndAwaitDecoderReleaseLocked()
        if (synchronized(stateLock) { surface } !== output || !output.isValid) return
        mjpegFrameDetectionCoordinator.onVideoSessionStarting()
        val config = videoSettings.toNanoKvmVideoConfig()
        val boundListener = SessionBoundVideoListener(videoListener)
        val created = NanoKvmVideoSession(
            client = activeClient.transport,
            baseUrl = activeClient.endpoint.baseUrl,
            token = token,
            listener = boundListener,
            webRtcRuntime = webRtcRuntimeProvider?.resolve(config.preference),
            callbackExecutor = videoCallbacks,
        )
        val accepted = synchronized(stateLock) {
            if (
                closed || !foreground ||
                !mutableSession.value.connection.isSessionUsable ||
                surface !== output || !output.isValid
            ) {
                false
            } else {
                activeVideoListener = boundListener
                video = created
                if (config.preference == NanoKvmVideoPreference.MJPEG) {
                    created.startMjpeg(config)
                } else {
                    created.start(output, config)
                }
                true
            }
        }
        if (!accepted) {
            boundListener.invalidate()
            created.closeAndAwaitDecoderRelease().awaitCompletion()
        }
    }

    private fun monitorInput(
        socket: NanoKvmInputSocket,
        binding: NanoKvmSessionBinding,
    ) {
        inputMonitor?.cancel()
        inputMonitor = scope.launch {
            socket.state.collect { state ->
                if (synchronized(stateLock) { input !== socket }) return@collect
                // Mount/restore/device toggles intentionally replace this socket themselves after
                // the one-shot REST mutation and authoritative readback. Ignore the appliance's
                // expected gadget-reset close so it cannot race the explicit replacement path.
                if (phase3UsbMutationInFlight) return@collect
                when (state) {
                    is InputConnectionState.Failed -> scheduleReconnect(
                        ReconnectFailure(state.cause, state.httpStatus),
                        immediateFirstAttempt = false,
                        expectedBinding = binding,
                    )
                    InputConnectionState.Disconnected -> if (foreground) {
                        scheduleReconnect(
                            ReconnectFailure(IOException("Input connection closed")),
                            immediateFirstAttempt = false,
                            expectedBinding = binding,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun monitorApplianceStatus(activeSession: AuthenticatedNanoKvmSession) {
        applianceStatusMonitor?.cancel()
        applianceStatusMonitor = scope.launch {
            var backoffState = applianceStatusPollingBackoff.initialState
            while (isActive && authenticatedSession === activeSession) {
                delay(applianceStatusPollingBackoff.delayMillis(backoffState))
                val startedAt = System.nanoTime()
                val gpio = try {
                    activeSession.console.gpioStatus()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    backoffState = applianceStatusPollingBackoff.afterFailure(backoffState)
                    continue
                }
                backoffState = applianceStatusPollingBackoff.afterSuccess()
                if (authenticatedSession !== activeSession) continue
                val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                mutableSession.update { current ->
                    if (!current.connection.isSessionUsable) {
                        current
                    } else {
                        current.copy(
                            roundTripMs = elapsedMs,
                            deviceStatus = current.deviceStatus.copy(
                                powerOn = gpio.powerOn,
                                hardDriveActive = gpio.hardDriveActive,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun performTrustPreflight(
        endpoint: NanoKvmEndpoint,
        savedCertificateSha256: String?,
    ): TrustPreflightOutcome {
        val savedPin = try {
            savedCertificateSha256?.let(CertificateFingerprint::parse)
        } catch (_: IllegalArgumentException) {
            return TrustPreflightOutcome.Failed(
                ConnectionFailure.InvalidSavedCertificate,
                retryable = false,
            )
        }
        return when (val result = EndpointTrustPreflight.inspect(endpoint, savedPin)) {
            is EndpointTrustPreflightResult.Trusted -> TrustPreflightOutcome.Trusted(
                source = when (result.source) {
                    ProtocolCertificateTrustSource.SYSTEM -> CertificateTrustSource.System
                    ProtocolCertificateTrustSource.SAVED_LEAF_PIN -> CertificateTrustSource.SavedLeafPin
                },
                certificate = result.inspection.toCertificateDetails(
                    when (result.source) {
                        ProtocolCertificateTrustSource.SYSTEM ->
                            CertificatePresentationReason.TrustedByAndroid
                        ProtocolCertificateTrustSource.SAVED_LEAF_PIN ->
                            CertificatePresentationReason.MatchesSavedCertificate
                    },
                ),
            )
            is EndpointTrustPreflightResult.ReviewRequired -> {
                TrustPreflightOutcome.CertificateReviewRequired(
                    result.inspection.toCertificateDetails(
                        CertificatePresentationReason.PrivateCertificateNotTrusted,
                    ),
                )
            }
            is EndpointTrustPreflightResult.Rejected -> {
                val rejectedInspection = result.inspection
                if (
                    result.reason == TrustPreflightRejection.PIN_MISMATCH &&
                    rejectedInspection != null
                ) {
                    return TrustPreflightOutcome.CertificateReviewRequired(
                        rejectedInspection.toCertificateDetails(
                            CertificatePresentationReason.DiffersFromSavedCertificate,
                        ),
                    )
                }
                val rejectionCause = result.cause
                val failure = when (result.reason) {
                    TrustPreflightRejection.INSECURE_ENDPOINT -> ConnectionFailure.HttpsRequired
                    TrustPreflightRejection.PIN_MISMATCH -> ConnectionFailure.CertificateChanged
                    TrustPreflightRejection.HOSTNAME_MISMATCH ->
                        ConnectionFailure.CertificateHostnameMismatch(endpoint.baseUrl.host)
                    TrustPreflightRejection.CERTIFICATE_DATE_INVALID ->
                        ConnectionFailure.CertificateDateInvalid
                    TrustPreflightRejection.INSPECTION_FAILED ->
                        ConnectionFailure.CertificateInspectionFailed
                }
                TrustPreflightOutcome.Failed(
                    failure = failure,
                    retryable = result.reason == TrustPreflightRejection.INSPECTION_FAILED &&
                        rejectionCause != null &&
                        ReconnectFailure(rejectionCause).disposition() == ReconnectFailureDisposition.RETRY,
                )
            }
        }
    }

    private suspend fun stopStreamingLocked() {
        applianceStatusMonitor?.cancel()
        applianceStatusMonitor = null
        inputMonitor?.cancel()
        inputMonitor = null
        val oldInput = synchronized(stateLock) {
            input.also { input = null }
        }
        releaseAllInputNow(oldInput)
        oldInput?.close()
        closeVideoAndAwaitDecoderReleaseLocked()
    }

    private suspend fun closeVideoAndAwaitDecoderReleaseLocked() {
        beginVideoCloseLocked()?.awaitCompletion()
    }

    private fun beginVideoCloseLocked(): CompletableFuture<Unit>? {
        activeVideoListener?.invalidate()
        activeVideoListener = null
        val oldVideo = video ?: return null
        video = null
        return oldVideo.closeAndAwaitDecoderRelease()
    }

    /** The caller-owned old Surface will be detached by Compose after the generation changes. */
    private fun forgetVideoSurfaceLocked() {
        synchronized(stateLock) { surface = null }
    }

    private suspend fun cleanupSessionLocked(forgetClient: Boolean) {
        invalidateSessionFeatureSet()
        applianceStatusMonitor?.cancel()
        applianceStatusMonitor = null
        inputMonitor?.cancel()
        inputMonitor = null
        val (oldInput, oldSession) = synchronized(stateLock) {
            val detachedInput = input
            input = null
            val detachedSession = if (forgetClient) authenticatedSession else null
            if (forgetClient) authenticatedSession = null
            detachedInput to detachedSession
        }
        if (oldInput != null) {
            releaseAllInputNow(oldInput)
            oldInput.close()
        }
        val decoderRelease = beginVideoCloseLocked()
        if (forgetClient) {
            oldSession?.close()
        }
        synchronized(stateLock) {
            keyboardState = KeyboardReportState()
            pressedMouseButtons.clear()
            usedAbsoluteMouse = false
        }
        decoderRelease?.awaitCompletion()
    }

    private fun installPhase3GatewayLocked(
        activeSession: AuthenticatedNanoKvmSession,
        sessionGeneration: Long,
    ) {
        check(Thread.holdsLock(stateLock))
        clearPhase3HandlesLocked()
        val binding = NanoKvmSessionBinding(
            profileId = activeSession.profileId,
            authority = activeSession.authority,
            sessionGeneration = sessionGeneration,
        )
        phase3Lifecycle.install(binding) {
            activeSession.createPhase3Gateway(
                sessionGeneration = sessionGeneration,
                currentBinding = ::currentPhase3Binding,
            )
        }
    }

    /**
     * The complete authenticated-session feature inventory. Keeping installation in one place
     * prevents reconnect and USB-input replacement paths from silently omitting a feature owner.
     */
    private fun installSessionFeatureSetLocked(
        activeSession: AuthenticatedNanoKvmSession,
        sessionGeneration: Long,
    ) {
        check(Thread.holdsLock(stateLock))
        installPhase3GatewayLocked(activeSession, sessionGeneration)
        installAdministrationGatewayLocked(activeSession, sessionGeneration)
        installDeviceControlGatewayLocked(activeSession, sessionGeneration)
        installOperatorGatewayLocked(activeSession, sessionGeneration)
        installPicoClawGatewayLocked(activeSession, sessionGeneration)
        installAutomationGatewayLocked(activeSession, sessionGeneration)
        installOfflineUpdateGatewayLocked(activeSession, sessionGeneration)
        mjpegFrameDetectionCoordinator.install(activeSession, sessionGeneration)
    }

    /** Invalidates the same feature inventory installed by [installSessionFeatureSetLocked]. */
    private fun invalidateSessionFeatureSet() {
        mjpegFrameDetectionCoordinator.clear()
        invalidatePhase3Gateway()
        invalidateAdministrationGateway()
        invalidateOperatorGateway()
        invalidatePicoClawGateway()
        invalidateAutomationGateway()
        invalidateOfflineUpdateGateway()
    }

    private fun installAdministrationGatewayLocked(
        activeSession: AuthenticatedNanoKvmSession,
        sessionGeneration: Long,
    ) {
        check(Thread.holdsLock(stateLock))
        val binding = NanoKvmSessionBinding(
            profileId = activeSession.profileId,
            authority = activeSession.authority,
            sessionGeneration = sessionGeneration,
        )
        administrationLifecycle.install(binding) {
            activeSession.createAdministrationGateway(
                sessionGeneration = sessionGeneration,
                currentBinding = ::currentAdministrationBinding,
            )
        }
    }

    private fun installDeviceControlGatewayLocked(
        activeSession: AuthenticatedNanoKvmSession,
        sessionGeneration: Long,
    ) {
        check(Thread.holdsLock(stateLock))
        val binding = NanoKvmSessionBinding(
            profileId = activeSession.profileId,
            authority = activeSession.authority,
            sessionGeneration = sessionGeneration,
        )
        deviceControlLifecycle.install(binding) {
            activeSession.createDeviceControlGateway(
                sessionGeneration = sessionGeneration,
                currentBinding = ::currentDeviceControlBinding,
            )
        }
    }

    private fun currentAdministrationBinding(): NanoKvmSessionBinding? = synchronized(stateLock) {
        if (!administrationSurfaceVisible) return@synchronized null
        currentSessionBindingLocked()
    }

    private fun currentDeviceControlBinding(): NanoKvmSessionBinding? =
        currentAdministrationBinding()

    private fun currentPhase3Binding(): NanoKvmSessionBinding? = synchronized(stateLock) {
        currentPhase3BindingLocked()
    }

    private fun currentPhase3BindingLocked(): NanoKvmSessionBinding? {
        check(Thread.holdsLock(stateLock))
        return currentSessionBindingLocked()
    }

    private fun currentMjpegFrameDetectionBinding(): NanoKvmSessionBinding? =
        synchronized(stateLock) { currentSessionBindingLocked() }

    private fun currentSessionBindingLocked(): NanoKvmSessionBinding? {
        check(Thread.holdsLock(stateLock))
        val activeSession = authenticatedSession ?: return null
        val current = mutableSession.value
        if (
            closed || !foreground || !acceptingCommands ||
            !current.connection.isSessionUsable
        ) {
            return null
        }
        return NanoKvmSessionBinding(
            profileId = activeSession.profileId,
            authority = activeSession.authority,
            sessionGeneration = current.sessionGeneration,
        )
    }

    /** Matches identity/generation after this failure has already closed command acceptance. */
    private fun matchesAuthenticatedSessionBindingLocked(
        binding: NanoKvmSessionBinding,
    ): Boolean {
        check(Thread.holdsLock(stateLock))
        val activeSession = authenticatedSession ?: return false
        return !closed &&
            activeSession.profileId == binding.profileId &&
            activeSession.authority == binding.authority &&
            mutableSession.value.sessionGeneration == binding.sessionGeneration
    }

    private fun isKnownUnsupportedCapability(
        binding: NanoKvmSessionBinding,
        capability: NanoKvmCapability,
    ): Boolean = synchronized(stateLock) {
        if (currentSessionBindingLocked() != binding) return@synchronized false
        authenticatedSession?.capabilities?.get(capability) is NanoKvmCapabilitySupport.Unsupported
    }

    private fun invalidatePhase3Gateway() {
        phase3UsbMutationInFlight = false
        val jobs = synchronized(stateLock) {
            phase3Lifecycle.clear()
            clearPhase3HandlesLocked()
            listOfNotNull(
                phase3RefreshJob.also { phase3RefreshJob = null },
                phase3ActionJob.also { phase3ActionJob = null },
                phase3TransferPollJob.also { phase3TransferPollJob = null },
            )
        }
        jobs.forEach { it.cancel(CancellationException("Phase 3 session invalidated")) }
        mutableSession.update { current ->
            current.copy(phase3 = Phase3FeatureUiState())
        }
    }

    private fun invalidateAdministrationGateway() {
        val jobs = synchronized(stateLock) {
            administrationLifecycle.clear()
            deviceControlLifecycle.clear()
            listOfNotNull(
                administrationRefreshJob.also { administrationRefreshJob = null },
                administrationActionJob.also { administrationActionJob = null },
            )
        }
        jobs.forEach { it.cancel(CancellationException("Administration session invalidated")) }
        mutableSession.update { current ->
            current.copy(administration = AdministrationUiState())
        }
    }

    private fun startAdministrationSurfaceWorkIfNeeded() {
        if (!administrationSurfaceVisible || !foreground) return
        startAdministrationRefreshIfPossible()
    }

    private fun startAdministrationRefreshIfPossible() {
        val binding = currentAdministrationBinding()
        val canLoad = administrationSurfaceCanLoad(
            visible = administrationSurfaceVisible,
            foreground = foreground,
            currentBinding = binding,
            installedBinding = administrationLifecycle.binding(),
        )
        val gateway = if (canLoad) binding?.let(administrationLifecycle::resolve) else null
        val deviceGateway = if (canLoad) binding?.let(deviceControlLifecycle::resolve) else null
        if (!canLoad || binding == null || gateway == null) {
            if (administrationSurfaceVisible) {
                rejectAdministrationUiCommand(
                    AdministrationNotice.Guidance(
                        AdministrationNotice.GuidanceReason
                            .ConnectBeforeOpeningAdministration,
                    ),
                )
            }
            return
        }
        lateinit var created: Job
        val accepted = synchronized(stateLock) {
            if (administrationRefreshJob != null || administrationActionJob != null) {
                false
            } else {
                created = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        refreshAdministrationSnapshot(binding, gateway, deviceGateway)
                    } finally {
                        synchronized(stateLock) {
                            if (administrationRefreshJob === created) {
                                administrationRefreshJob = null
                            }
                        }
                        updateAdministrationIfCurrent(binding) { it.copy(loading = false) }
                    }
                }
                administrationRefreshJob = created
                true
            }
        }
        if (!accepted) return
        updateAdministrationIfCurrent(binding) {
            it.copy(
                loading = true,
                notice = null,
                account = null,
                updates = null,
                oled = null,
                sshEnabled = null,
                hostname = null,
                mdnsEnabled = null,
                webTitle = null,
                webTitleIsDefault = null,
                dns = null,
                hdmiEnabled = null,
                mouseJiggler = null,
                memoryLimit = null,
                swap = null,
                wifi = null,
                tailscale = null,
            )
        }
        created.start()
    }

    private suspend fun refreshAdministrationSnapshot(
        binding: NanoKvmSessionBinding,
        gateway: NanoKvmAdministrationGateway,
        deviceGateway: NanoKvmDeviceControlGateway?,
    ) {
        var failedReads = 0
        when (val result = gateway.refreshAccount()) {
            is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(account = result.state.toUiState())
            }
            is NanoKvmAdministrationReadResult.Failure ->
                failedReads += recordAdministrationReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshUpdates()) {
            is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(updates = result.state.toUiState())
            }
            is NanoKvmAdministrationReadResult.Failure ->
                failedReads += recordAdministrationReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshOled()) {
            is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(oled = result.state.toUiState())
            }
            is NanoKvmAdministrationReadResult.Failure ->
                failedReads += recordAdministrationReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshSsh()) {
            is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(sshEnabled = result.state.enabled)
            }
            is NanoKvmAdministrationReadResult.Failure ->
                failedReads += recordAdministrationReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshHostname()) {
            is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(hostname = result.state.hostname)
            }
            is NanoKvmAdministrationReadResult.Failure ->
                failedReads += recordAdministrationReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshMdns()) {
            is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(mdnsEnabled = result.state.enabled)
            }
            is NanoKvmAdministrationReadResult.Failure ->
                failedReads += recordAdministrationReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshWebTitle()) {
            is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(
                    webTitle = result.state.title,
                    webTitleIsDefault = result.state.isDefault,
                )
            }
            is NanoKvmAdministrationReadResult.Failure ->
                failedReads += recordAdministrationReadFailure(binding, result.error)
        }
        if (!isKnownUnsupportedCapability(binding, NanoKvmCapability.DNS_CONFIGURATION)) {
            when (val result = gateway.refreshDns()) {
                is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                    it.copy(dns = result.state.toUiState())
                }
                is NanoKvmAdministrationReadResult.Failure ->
                    failedReads += recordAdministrationReadFailure(binding, result.error)
            }
        }
        when (val result = gateway.refreshWifi()) {
            is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(wifi = result.state.toUiState())
            }
            is NanoKvmAdministrationReadResult.Failure ->
                failedReads += recordAdministrationReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshTailscale()) {
            is NanoKvmAdministrationReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(tailscale = result.state.toUiState())
            }
            is NanoKvmAdministrationReadResult.Failure ->
                failedReads += recordAdministrationReadFailure(binding, result.error)
        }
        failedReads += if (deviceGateway == null) {
            4
        } else {
            refreshDeviceControlSnapshot(binding, deviceGateway)
        }
        if (failedReads > 0) {
            publishAdministrationNotice(
                binding,
                AdministrationNotice.Guidance(
                    AdministrationNotice.GuidanceReason.SectionsUnavailable(failedReads),
                ),
            )
        }
    }

    private suspend fun refreshDeviceControlSnapshot(
        binding: NanoKvmSessionBinding,
        gateway: NanoKvmDeviceControlGateway,
    ): Int {
        var failedReads = 0
        when (val result = gateway.refreshHdmi()) {
            is NanoKvmDeviceControlReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(hdmiEnabled = result.state.enabled)
            }
            is NanoKvmDeviceControlReadResult.Failure ->
                failedReads += recordDeviceControlReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshMouseJiggler()) {
            is NanoKvmDeviceControlReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(mouseJiggler = result.state.toUiState())
            }
            is NanoKvmDeviceControlReadResult.Failure ->
                failedReads += recordDeviceControlReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshMemoryLimit()) {
            is NanoKvmDeviceControlReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(memoryLimit = result.state.toUiState())
            }
            is NanoKvmDeviceControlReadResult.Failure ->
                failedReads += recordDeviceControlReadFailure(binding, result.error)
        }
        when (val result = gateway.refreshSwap()) {
            is NanoKvmDeviceControlReadResult.Success -> updateAdministrationIfCurrent(binding) {
                it.copy(swap = result.state.toUiState())
            }
            is NanoKvmDeviceControlReadResult.Failure ->
                failedReads += recordDeviceControlReadFailure(binding, result.error)
        }
        return failedReads
    }

    private fun beginDeviceControlAction(
        destination: ApprovedAdministrationDestination,
        action: AdministrationNotice.Action,
        block: suspend (
            NanoKvmDeviceControlGateway,
            NanoKvmSessionBinding,
            AdministrationNotice.Action,
        ) -> Unit,
    ) {
        val binding = currentDeviceControlBinding()
        val gateway = binding?.let(deviceControlLifecycle::resolve)
        if (binding == null || gateway == null || !destination.matches(binding)) {
            rejectAdministrationUiCommand(
                AdministrationNotice.Guidance(
                    AdministrationNotice.GuidanceReason.DestinationChangedReviewAction,
                ),
            )
            return
        }

        lateinit var created: Job
        val accepted = synchronized(stateLock) {
            if (administrationActionJob != null || administrationRefreshJob != null) {
                false
            } else {
                created = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        cancelPasteAndJoin(userInitiated = false)
                        releaseAllInputNow()
                        if (currentDeviceControlBinding() != binding) {
                            publishAdministrationNotice(
                                binding,
                                AdministrationNotice.Guidance(
                                    AdministrationNotice.GuidanceReason
                                        .DestinationChangedBeforeSend,
                                ),
                            )
                            return@launch
                        }
                        block(gateway, binding, action)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        publishAdministrationNotice(
                            binding,
                            AdministrationNotice.ActionOutcome(
                                action = action,
                                outcome = AdministrationNotice.Outcome.Indeterminate,
                                followUp = AdministrationNotice.FollowUp.RefreshBeforeRepeating,
                            ),
                        )
                    } finally {
                        synchronized(stateLock) {
                            if (administrationActionJob === created) {
                                administrationActionJob = null
                            }
                        }
                        updateAdministrationIfCurrent(binding) {
                            it.copy(operationInProgress = false)
                        }
                    }
                }
                administrationActionJob = created
                true
            }
        }
        if (!accepted) {
            rejectAdministrationUiCommand(
                AdministrationNotice.Guidance(
                    AdministrationNotice.GuidanceReason.AnotherOperationRunning,
                ),
            )
            return
        }
        updateAdministrationIfCurrent(binding) {
            it.copy(operationInProgress = true, notice = null)
        }
        created.start()
    }

    private fun <State> publishDeviceControlMutation(
        binding: NanoKvmSessionBinding,
        result: NanoKvmDeviceControlMutationResult<State>,
        action: AdministrationNotice.Action,
        publishState: (AdministrationUiState, State) -> AdministrationUiState,
    ) {
        deviceControlMutationState(result)?.let { state ->
            updateAdministrationIfCurrent(binding) { publishState(it, state) }
        }
        val notice = when (result) {
            is NanoKvmDeviceControlMutationResult.Applied ->
                AdministrationNotice.ActionOutcome(
                    action = action,
                    outcome = AdministrationNotice.Outcome.Applied,
                )
            is NanoKvmDeviceControlMutationResult.AlreadySatisfied ->
                AdministrationNotice.ActionOutcome(
                    action = action,
                    outcome = AdministrationNotice.Outcome.AlreadySatisfied,
                )
            is NanoKvmDeviceControlMutationResult.Accepted ->
                AdministrationNotice.ActionOutcome(
                    action = action,
                    outcome = AdministrationNotice.Outcome.AcceptedWithoutConfirmation,
                )
            is NanoKvmDeviceControlMutationResult.Reconciled -> if (
                result.observation == NanoKvmDeviceControlObservation.DESIRED_STATE
            ) {
                AdministrationNotice.ActionOutcome(
                    action = action,
                    outcome = AdministrationNotice.Outcome.ReconciledToRequestedState,
                )
            } else {
                AdministrationNotice.ActionOutcome(
                    action = action,
                    outcome = AdministrationNotice.Outcome.ReconciledToDifferentState,
                )
            }
            is NanoKvmDeviceControlMutationResult.Indeterminate ->
                AdministrationNotice.ActionOutcome(
                    action = action,
                    outcome = AdministrationNotice.Outcome.Indeterminate,
                    followUp = AdministrationNotice.FollowUp.RefreshBeforeRepeating,
                )
            is NanoKvmDeviceControlMutationResult.Rejected -> AdministrationNotice.Error(
                deviceControlFailure(binding, result.error),
            )
            NanoKvmDeviceControlMutationResult.DisruptiveCommandAccepted ->
                AdministrationNotice.ActionOutcome(
                    action = action,
                    outcome = AdministrationNotice.Outcome.DisruptiveCommandAccepted,
                )
        }
        publishAdministrationNotice(binding, notice)
    }

    private fun <State> deviceControlMutationState(
        result: NanoKvmDeviceControlMutationResult<State>,
    ): State? = when (result) {
        is NanoKvmDeviceControlMutationResult.Applied -> result.state
        is NanoKvmDeviceControlMutationResult.AlreadySatisfied -> result.state
        is NanoKvmDeviceControlMutationResult.Accepted -> result.state
        is NanoKvmDeviceControlMutationResult.Reconciled -> result.state
        is NanoKvmDeviceControlMutationResult.Indeterminate -> result.state
        is NanoKvmDeviceControlMutationResult.Rejected,
        NanoKvmDeviceControlMutationResult.DisruptiveCommandAccepted -> null
    }

    private fun deviceControlFailure(
        binding: NanoKvmSessionBinding,
        error: NanoKvmDeviceControlError,
    ): AdministrationNotice.Failure {
        if (error.kind == NanoKvmDeviceControlError.Kind.AUTHENTICATION_EXPIRED) {
            expireAuthenticatedSession(binding)
        }
        return when (error.kind) {
            NanoKvmDeviceControlError.Kind.SESSION_CHANGED ->
                AdministrationNotice.Failure.SessionChanged
            NanoKvmDeviceControlError.Kind.INVALID_REQUEST ->
                AdministrationNotice.Failure.InvalidPreset
            NanoKvmDeviceControlError.Kind.UNSUPPORTED ->
                AdministrationNotice.Failure.Unsupported
            NanoKvmDeviceControlError.Kind.AUTHENTICATION_EXPIRED ->
                AdministrationNotice.Failure.AuthenticationExpired
            NanoKvmDeviceControlError.Kind.CONNECTION ->
                AdministrationNotice.Failure.Connection
            NanoKvmDeviceControlError.Kind.SERVER_REJECTED ->
                AdministrationNotice.Failure.ServerRejected
            NanoKvmDeviceControlError.Kind.INVALID_RESPONSE ->
                AdministrationNotice.Failure.ControlInvalidResponse
            NanoKvmDeviceControlError.Kind.UNEXPECTED ->
                AdministrationNotice.Failure.Unexpected
        }
    }

    private fun recordDeviceControlReadFailure(
        binding: NanoKvmSessionBinding,
        error: NanoKvmDeviceControlError,
    ): Int {
        if (error.kind == NanoKvmDeviceControlError.Kind.AUTHENTICATION_EXPIRED) {
            expireAuthenticatedSession(binding)
        }
        return if (error.kind == NanoKvmDeviceControlError.Kind.UNSUPPORTED) 0 else 1
    }

    private fun beginAdministrationAction(
        destination: ApprovedAdministrationDestination,
        action: AdministrationNotice.Action,
        onCompletion: () -> Unit = {},
        block: suspend (
            NanoKvmAdministrationGateway,
            NanoKvmSessionBinding,
            AdministrationNotice.Action,
        ) -> AdministrationReconnectMode?,
    ) {
        val binding = currentAdministrationBinding()
        val gateway = binding?.let(administrationLifecycle::resolve)
        if (binding == null || gateway == null || !destination.matches(binding)) {
            onCompletion()
            rejectAdministrationUiCommand(
                AdministrationNotice.Guidance(
                    AdministrationNotice.GuidanceReason.DestinationChangedReviewAction,
                ),
            )
            return
        }

        lateinit var created: Job
        val accepted = synchronized(stateLock) {
            if (administrationActionJob != null || administrationRefreshJob != null) {
                false
            } else {
                created = scope.launch(start = CoroutineStart.LAZY) {
                    var reconnectMode: AdministrationReconnectMode? = null
                    try {
                        cancelPasteAndJoin(userInitiated = false)
                        releaseAllInputNow()
                        if (currentAdministrationBinding() != binding) {
                            publishAdministrationNotice(
                                binding,
                                AdministrationNotice.Guidance(
                                    AdministrationNotice.GuidanceReason
                                        .DestinationChangedBeforeSend,
                                ),
                            )
                            return@launch
                        }
                        reconnectMode = block(gateway, binding, action)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        publishAdministrationNotice(
                            binding,
                            AdministrationNotice.ActionOutcome(
                                action = action,
                                outcome = AdministrationNotice.Outcome.Indeterminate,
                                followUp = AdministrationNotice.FollowUp
                                    .ReconnectAndVerifyBeforeRepeating,
                            ),
                        )
                    } finally {
                        synchronized(stateLock) {
                            if (administrationActionJob === created) {
                                administrationActionJob = null
                            }
                        }
                        updateAdministrationIfCurrent(binding) {
                            it.copy(operationInProgress = false)
                        }
                    }
                    when (reconnectMode) {
                        AdministrationReconnectMode.Immediate -> scheduleReconnect(
                            ReconnectFailure(IOException("Administration change requires reconnect")),
                            immediateFirstAttempt = true,
                        )
                        AdministrationReconnectMode.WaitForService -> scheduleReconnect(
                            ReconnectFailure(IOException("NanoKVM service is restarting")),
                            immediateFirstAttempt = false,
                        )
                        null -> Unit
                    }
                }
                created.invokeOnCompletion { onCompletion() }
                administrationActionJob = created
                true
            }
        }
        if (!accepted) {
            onCompletion()
            rejectAdministrationUiCommand(
                AdministrationNotice.Guidance(
                    AdministrationNotice.GuidanceReason.AnotherOperationRunning,
                ),
            )
            return
        }
        updateAdministrationIfCurrent(binding) {
            it.copy(operationInProgress = true, notice = null)
        }
        created.start()
    }

    private fun <State> publishAdministrationMutation(
        binding: NanoKvmSessionBinding,
        result: NanoKvmAdministrationMutationResult<State>,
        action: AdministrationNotice.Action,
        publishState: (AdministrationUiState, State) -> AdministrationUiState,
    ): AdministrationReconnectMode? {
        administrationMutationState(result)?.let { state ->
            updateAdministrationIfCurrent(binding) { publishState(it, state) }
        }
        publishAdministrationNotice(
            binding,
            administrationMutationNotice(binding, result, action),
        )
        return result.reconnectMode()
    }

    private fun <State> administrationMutationState(
        result: NanoKvmAdministrationMutationResult<State>,
    ): State? = when (result) {
        is NanoKvmAdministrationMutationResult.Applied -> result.state
        is NanoKvmAdministrationMutationResult.AlreadySatisfied -> result.state
        is NanoKvmAdministrationMutationResult.Accepted -> result.state
        is NanoKvmAdministrationMutationResult.Reconciled -> result.state
        is NanoKvmAdministrationMutationResult.Indeterminate -> result.state
        is NanoKvmAdministrationMutationResult.Rejected,
        NanoKvmAdministrationMutationResult.CredentialsChanged,
        is NanoKvmAdministrationMutationResult.DisruptiveCommandAccepted -> null
    }

    private fun administrationMutationNotice(
        binding: NanoKvmSessionBinding,
        result: NanoKvmAdministrationMutationResult<*>,
        action: AdministrationNotice.Action,
    ): AdministrationNotice = when (result) {
        is NanoKvmAdministrationMutationResult.Applied ->
            AdministrationNotice.ActionOutcome(
                action = action,
                outcome = AdministrationNotice.Outcome.Applied,
                followUp = result.guidance.toNoticeFollowUp(),
            )
        is NanoKvmAdministrationMutationResult.AlreadySatisfied ->
            AdministrationNotice.ActionOutcome(
                action = action,
                outcome = AdministrationNotice.Outcome.AlreadySatisfied,
            )
        is NanoKvmAdministrationMutationResult.Accepted ->
            AdministrationNotice.ActionOutcome(
                action = action,
                outcome = AdministrationNotice.Outcome.AcceptedWithoutConfirmation,
                followUp = result.guidance.toNoticeFollowUp(),
            )
        is NanoKvmAdministrationMutationResult.Reconciled -> if (
            result.observation == NanoKvmAdministrationObservation.DESIRED_STATE
        ) {
            AdministrationNotice.ActionOutcome(
                action = action,
                outcome = AdministrationNotice.Outcome.ReconciledToRequestedState,
                followUp = result.guidance.toNoticeFollowUp(),
            )
        } else {
            AdministrationNotice.ActionOutcome(
                action = action,
                outcome = AdministrationNotice.Outcome.ReconciledToDifferentState,
                followUp = result.guidance.toNoticeFollowUp(),
            )
        }
        is NanoKvmAdministrationMutationResult.Indeterminate ->
            AdministrationNotice.ActionOutcome(
                action = action,
                outcome = AdministrationNotice.Outcome.Indeterminate,
                followUp = result.guidance.toNoticeFollowUp(),
            )
        is NanoKvmAdministrationMutationResult.Rejected ->
            AdministrationNotice.Error(administrationFailure(binding, result.error))
        NanoKvmAdministrationMutationResult.CredentialsChanged ->
            AdministrationNotice.ActionOutcome(
                action = action,
                outcome = AdministrationNotice.Outcome.CredentialsChanged,
            )
        is NanoKvmAdministrationMutationResult.DisruptiveCommandAccepted ->
            AdministrationNotice.ActionOutcome(
                action = action,
                outcome = AdministrationNotice.Outcome.DisruptiveCommandAccepted,
                followUp = result.guidance.toNoticeFollowUp(),
            )
    }

    private fun NanoKvmAdministrationGuidance.toNoticeFollowUp():
        AdministrationNotice.FollowUp = when (this) {
        NanoKvmAdministrationGuidance.NONE -> AdministrationNotice.FollowUp.None
        NanoKvmAdministrationGuidance.REFRESH_AUTHORITATIVE_STATE ->
            AdministrationNotice.FollowUp.RefreshAuthoritativeState
        NanoKvmAdministrationGuidance.REVIEW_AUTHORITATIVE_STATE ->
            AdministrationNotice.FollowUp.ReviewAuthoritativeState
        NanoKvmAdministrationGuidance.RECONNECT_AND_REFRESH ->
            AdministrationNotice.FollowUp.ReconnectAndRefresh
        NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT ->
            AdministrationNotice.FollowUp.RediscoverAndReconnect
        NanoKvmAdministrationGuidance.WAIT_FOR_REBOOT_AND_RECONNECT ->
            AdministrationNotice.FollowUp.WaitForRebootAndReconnect
        NanoKvmAdministrationGuidance.CLEAR_SAVED_CREDENTIAL_AND_END_SESSION ->
            AdministrationNotice.FollowUp.ClearSavedCredentialAndEndSession
        NanoKvmAdministrationGuidance.VERIFY_NEW_CREDENTIALS_AFTER_RECONNECT ->
            AdministrationNotice.FollowUp.VerifyNewCredentialsAfterReconnect
    }

    private fun NanoKvmAdministrationMutationResult<*>.reconnectMode():
        AdministrationReconnectMode? {
        if (this is NanoKvmAdministrationMutationResult.Rejected ||
            this is NanoKvmAdministrationMutationResult.AlreadySatisfied
        ) {
            return null
        }
        return when (guidance) {
            NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT ->
                AdministrationReconnectMode.Immediate
            NanoKvmAdministrationGuidance.RECONNECT_AND_REFRESH,
            NanoKvmAdministrationGuidance.WAIT_FOR_REBOOT_AND_RECONNECT ->
                AdministrationReconnectMode.WaitForService
            else -> null
        }
    }

    private fun administrationFailure(
        binding: NanoKvmSessionBinding,
        error: NanoKvmAdministrationError,
    ): AdministrationNotice.Failure {
        if (error.kind == NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED) {
            expireAuthenticatedSession(binding)
        }
        return when (error.kind) {
            NanoKvmAdministrationError.Kind.SESSION_CHANGED ->
                AdministrationNotice.Failure.SessionChanged
            NanoKvmAdministrationError.Kind.INVALID_REQUEST ->
                AdministrationNotice.Failure.InvalidRequest
            NanoKvmAdministrationError.Kind.UNSUPPORTED ->
                AdministrationNotice.Failure.Unsupported
            NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED ->
                AdministrationNotice.Failure.AuthenticationExpired
            NanoKvmAdministrationError.Kind.CONNECTION ->
                AdministrationNotice.Failure.Connection
            NanoKvmAdministrationError.Kind.SERVER_REJECTED ->
                AdministrationNotice.Failure.ServerRejected
            NanoKvmAdministrationError.Kind.INVALID_RESPONSE ->
                AdministrationNotice.Failure.InvalidResponse
            NanoKvmAdministrationError.Kind.UNEXPECTED ->
                AdministrationNotice.Failure.Unexpected
        }
    }

    private fun recordAdministrationReadFailure(
        binding: NanoKvmSessionBinding,
        error: NanoKvmAdministrationError,
    ): Int {
        if (error.kind == NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED) {
            expireAuthenticatedSession(binding)
        }
        return if (error.kind == NanoKvmAdministrationError.Kind.UNSUPPORTED) 0 else 1
    }

    private fun publishAdministrationNotice(
        binding: NanoKvmSessionBinding,
        notice: AdministrationNotice,
    ) {
        updateAdministrationIfCurrent(binding) {
            it.copy(notice = notice)
        }
    }

    private fun newAdministrationHttpsNavigationRequest(
        binding: NanoKvmSessionBinding,
        value: String,
    ): PendingAdministrationHttpsNavigationRequest {
        val requestId = synchronized(stateLock) {
            administrationNavigationRequestCounter =
                if (administrationNavigationRequestCounter == Long.MAX_VALUE) {
                    1L
                } else {
                    administrationNavigationRequestCounter + 1L
                }
            administrationNavigationRequestCounter
        }
        return PendingAdministrationHttpsNavigationRequest(
            requestId = requestId,
            profileId = binding.profileId,
            authority = binding.authority,
            sessionGeneration = binding.sessionGeneration,
            value = value,
        )
    }

    private fun rejectAdministrationUiCommand(notice: AdministrationNotice) {
        mutableSession.update { current ->
            current.copy(
                administration = current.administration.copy(notice = notice),
            )
        }
    }

    private inline fun updateAdministrationIfCurrent(
        binding: NanoKvmSessionBinding,
        transform: (AdministrationUiState) -> AdministrationUiState,
    ) {
        mutableSession.update { current ->
            if (currentAdministrationBinding() == binding) {
                current.copy(administration = transform(current.administration))
            } else {
                current
            }
        }
    }

    private fun installOperatorGatewayLocked(
        activeSession: AuthenticatedNanoKvmSession,
        sessionGeneration: Long,
    ) {
        check(Thread.holdsLock(stateLock))
        val oldBinding = operatorLifecycle.binding()
        val oldGateway = oldBinding?.let(operatorLifecycle::resolve)
        operatorLifecycle.clear()
        oldGateway?.close()
        clearOperatorScriptHandlesLocked()
        val binding = NanoKvmSessionBinding(
            profileId = activeSession.profileId,
            authority = activeSession.authority,
            sessionGeneration = sessionGeneration,
        )
        operatorLifecycle.install(binding) {
            activeSession.createOperatorGateway(
                sessionGeneration = sessionGeneration,
                currentBinding = ::currentOperatorBinding,
                scope = scope,
            )
        }
    }

    private fun currentOperatorBinding(): NanoKvmSessionBinding? = synchronized(stateLock) {
        if (!operatorSurfaceVisible) return@synchronized null
        currentSessionBindingLocked()
    }

    private fun invalidateOperatorGateway() {
        val detached = synchronized(stateLock) {
            val binding = operatorLifecycle.binding()
            val gateway = binding?.let(operatorLifecycle::resolve)
            operatorLifecycle.clear()
            clearOperatorScriptHandlesLocked()
            val jobs = listOfNotNull(
                operatorScriptRefreshJob.also { operatorScriptRefreshJob = null },
                operatorActionJob.also { operatorActionJob = null },
                operatorTerminalStateJob.also { operatorTerminalStateJob = null },
                operatorTerminalOutputJob.also { operatorTerminalOutputJob = null },
            )
            gateway to jobs
        }
        detached.second.forEach { it.cancel(CancellationException("Operator session invalidated")) }
        detached.first?.close()
        mutableOperatorState.value = OperatorUiState()
    }

    private fun startOperatorSurfaceWorkIfNeeded() {
        val binding = currentOperatorBinding()
        val canLoad = operatorSurfaceCanLoad(
            visible = operatorSurfaceVisible,
            foreground = foreground,
            currentBinding = binding,
            installedBinding = operatorLifecycle.binding(),
        )
        val gateway = if (canLoad) binding?.let(operatorLifecycle::resolve) else null
        if (!canLoad || binding == null || gateway == null) {
            if (operatorSurfaceVisible) {
                rejectOperatorUiCommand(
                    OperatorNotice.Guidance(
                        OperatorNotice.GuidanceReason.ConnectBeforeOpeningTools,
                    ),
                )
            }
            return
        }
        gateway.terminal.onForeground()
        startOperatorTerminalCollectors(binding, gateway)
        startOperatorScriptRefreshIfPossible()
    }

    private fun startOperatorTerminalCollectors(
        binding: NanoKvmSessionBinding,
        gateway: NanoKvmOperatorGateway,
    ) {
        synchronized(stateLock) {
            if (operatorTerminalStateJob == null) {
                operatorTerminalStateJob = scope.launch {
                    gateway.terminal.state.collect { terminalState ->
                        updateOperatorIfCurrent(binding) { current ->
                            when (terminalState) {
                                NanoKvmOperatorTerminalState.Inactive -> current.copy(
                                    terminalPhase = OperatorTerminalUiPhase.Inactive,
                                    serialActive = false,
                                )
                                NanoKvmOperatorTerminalState.Connecting -> current.copy(
                                    terminalPhase = OperatorTerminalUiPhase.Connecting,
                                    serialActive = false,
                                )
                                is NanoKvmOperatorTerminalState.Connected -> current.copy(
                                    terminalPhase = OperatorTerminalUiPhase.Connected,
                                    serialActive = terminalState.serialActive,
                                )
                                NanoKvmOperatorTerminalState.Closing -> current.copy(
                                    terminalPhase = OperatorTerminalUiPhase.Closing,
                                )
                                is NanoKvmOperatorTerminalState.Failed -> current.copy(
                                    terminalPhase = OperatorTerminalUiPhase.Failed,
                                    serialActive = false,
                                    notice = OperatorNotice.Error(
                                        operatorFailure(binding, terminalState.error),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            if (operatorTerminalOutputJob == null) {
                operatorTerminalOutputJob = scope.launch {
                    gateway.terminal.output.collect { output ->
                        if (currentOperatorBinding() != binding) return@collect
                        val bytes = output.copyBytes()
                        try {
                            val text = bytes.toString(Charsets.UTF_8)
                            mutableOperatorOutput.tryEmit(
                                OperatorEphemeralOutput(OperatorOutputKind.Terminal, text),
                            )
                        } finally {
                            bytes.fill(0)
                        }
                    }
                }
            }
        }
    }

    private fun startOperatorScriptRefreshIfPossible() {
        val binding = currentOperatorBinding()
        val gateway = binding?.let(operatorLifecycle::resolve)
        if (binding == null || gateway == null) {
            if (operatorSurfaceVisible) {
                rejectOperatorUiCommand(
                    OperatorNotice.Guidance(
                        OperatorNotice.GuidanceReason.SessionNoLongerCurrent,
                    ),
                )
            }
            return
        }
        lateinit var created: Job
        val accepted = synchronized(stateLock) {
            if (operatorScriptRefreshJob != null || operatorActionJob != null) {
                false
            } else {
                created = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        when (val result = gateway.refreshScripts()) {
                            is NanoKvmOperatorScriptReadResult.Success ->
                                publishOperatorScriptCatalog(binding, result.catalog)
                            is NanoKvmOperatorScriptReadResult.Failure ->
                                publishOperatorNotice(
                                    binding,
                                    OperatorNotice.Error(
                                        operatorFailure(binding, result.error),
                                    ),
                                )
                        }
                    } finally {
                        synchronized(stateLock) {
                            if (operatorScriptRefreshJob === created) {
                                operatorScriptRefreshJob = null
                            }
                        }
                        updateOperatorIfCurrent(binding) { it.copy(loadingScripts = false) }
                    }
                }
                operatorScriptRefreshJob = created
                true
            }
        }
        if (!accepted) return
        updateOperatorIfCurrent(binding) { it.copy(loadingScripts = true, notice = null) }
        created.start()
    }

    private fun beginOperatorAction(
        destination: ApprovedOperatorDestination,
        action: OperatorNotice.Action,
        onCompletion: () -> Unit = {},
        block: suspend (
            NanoKvmOperatorGateway,
            NanoKvmSessionBinding,
            OperatorNotice.Action,
        ) -> Unit,
    ): Boolean {
        val selection = resolveOperatorGateway(destination) ?: return false
        val gateway = selection.first
        val binding = selection.second
        lateinit var created: Job
        val accepted = synchronized(stateLock) {
            if (operatorActionJob != null || operatorScriptRefreshJob != null) {
                false
            } else {
                created = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        cancelPasteAndJoin(userInitiated = false)
                        releaseAllInputNow()
                        if (currentOperatorBinding() != binding) {
                            publishOperatorNotice(
                                binding,
                                OperatorNotice.Guidance(
                                    OperatorNotice.GuidanceReason.DestinationChangedBeforeSend,
                                ),
                            )
                            return@launch
                        }
                        block(gateway, binding, action)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        publishOperatorNotice(
                            binding,
                            OperatorNotice.ActionOutcome(
                                action = action,
                                outcome = OperatorNotice.Outcome.Indeterminate,
                            ),
                        )
                    } finally {
                        synchronized(stateLock) {
                            if (operatorActionJob === created) operatorActionJob = null
                        }
                        updateOperatorIfCurrent(binding) {
                            it.copy(operationInProgress = false)
                        }
                    }
                }
                created.invokeOnCompletion { onCompletion() }
                operatorActionJob = created
                true
            }
        }
        if (!accepted) {
            rejectOperatorUiCommand(
                OperatorNotice.Guidance(OperatorNotice.GuidanceReason.AnotherActionRunning),
            )
            return false
        }
        updateOperatorIfCurrent(binding) {
            it.copy(operationInProgress = true, notice = null)
        }
        created.start()
        return true
    }

    private fun resolveOperatorGateway(
        destination: ApprovedOperatorDestination,
    ): Pair<NanoKvmOperatorGateway, NanoKvmSessionBinding>? {
        val binding = currentOperatorBinding()
        val gateway = binding?.let(operatorLifecycle::resolve)
        if (binding == null || gateway == null || !destination.matches(binding)) {
            rejectOperatorUiCommand(
                OperatorNotice.Guidance(
                    OperatorNotice.GuidanceReason.DestinationChangedReviewAction,
                ),
            )
            return null
        }
        return gateway to binding
    }

    private fun resolveOperatorScript(
        scriptId: Long,
    ): Pair<NanoKvmOperatorScriptCatalog, NanoKvmOperatorScript>? = synchronized(stateLock) {
        val catalog = operatorScriptCatalog ?: return@synchronized null
        val script = operatorScriptHandles[scriptId] ?: return@synchronized null
        catalog to script
    }

    private fun publishOperatorScriptCatalog(
        binding: NanoKvmSessionBinding,
        catalog: NanoKvmOperatorScriptCatalog,
    ) {
        val entries = synchronized(stateLock) {
            if (currentOperatorBindingLocked() != binding) return
            operatorScriptCatalog = catalog
            catalog.scripts.map { script ->
                val id = ++operatorScriptHandleCounter
                operatorScriptHandles = operatorScriptHandles + (id to script)
                OperatorScriptUiState(id, script.displayName)
            }
        }
        updateOperatorIfCurrent(binding) {
            it.copy(scriptsLoaded = true, scripts = entries)
        }
    }

    private suspend fun refreshOperatorScriptsAfterMutation(
        binding: NanoKvmSessionBinding,
        gateway: NanoKvmOperatorGateway,
    ) {
        when (val refreshed = gateway.refreshScripts()) {
            is NanoKvmOperatorScriptReadResult.Success ->
                publishOperatorScriptCatalog(binding, refreshed.catalog)
            is NanoKvmOperatorScriptReadResult.Failure -> updateOperatorIfCurrent(binding) {
                it.copy(scriptsLoaded = false, scripts = emptyList())
            }
        }
    }

    private fun clearOperatorScriptHandles() = synchronized(stateLock) {
        clearOperatorScriptHandlesLocked()
    }

    private fun clearOperatorScriptHandlesLocked() {
        check(Thread.holdsLock(stateLock))
        operatorScriptCatalog = null
        operatorScriptHandles = emptyMap()
        mutableOperatorState.update {
            it.copy(scriptsLoaded = false, scripts = emptyList())
        }
    }

    private fun publishOperatorTerminalResult(
        binding: NanoKvmSessionBinding,
        result: NanoKvmOperatorActionResult,
        action: OperatorNotice.Action,
        publishSuccessNotice: Boolean = true,
    ) {
        when (result) {
            NanoKvmOperatorActionResult.Dispatched -> if (publishSuccessNotice) {
                publishOperatorNotice(
                    binding,
                    OperatorNotice.ActionOutcome(
                        action = action,
                        outcome = OperatorNotice.Outcome.Dispatched,
                    ),
                )
            }
            is NanoKvmOperatorActionResult.Rejected -> publishOperatorNotice(
                binding,
                OperatorNotice.Error(operatorFailure(binding, result.error)),
            )
        }
    }

    private fun publishOperatorScriptResult(
        binding: NanoKvmSessionBinding,
        result: NanoKvmOperatorScriptCommandResult<*>,
        action: OperatorNotice.Action,
    ) {
        val warnings = result.warnings.toNoticeWarnings()
        val notice = when (result) {
            is NanoKvmOperatorScriptCommandResult.Completed ->
                OperatorNotice.ActionOutcome(
                    action = action,
                    outcome = OperatorNotice.Outcome.Completed,
                    warnings = warnings,
                )
            is NanoKvmOperatorScriptCommandResult.Reconciled -> if (
                result.observation == NanoKvmScriptDeleteObservation.ABSENT
            ) {
                OperatorNotice.ActionOutcome(
                    action = action,
                    outcome = OperatorNotice.Outcome.ReconciledAbsent,
                    warnings = warnings,
                )
            } else {
                OperatorNotice.ActionOutcome(
                    action = action,
                    outcome = OperatorNotice.Outcome.ReconciledPresent,
                    warnings = warnings,
                )
            }
            is NanoKvmOperatorScriptCommandResult.Indeterminate ->
                OperatorNotice.ActionOutcome(
                    action = action,
                    outcome = OperatorNotice.Outcome.Indeterminate,
                    warnings = warnings,
                )
            is NanoKvmOperatorScriptCommandResult.Rejected -> OperatorNotice.Error(
                failure = operatorFailure(binding, result.error),
                warnings = warnings,
            )
        }
        publishOperatorNotice(binding, notice)
    }

    private fun Set<NanoKvmScriptRunWarning>.toNoticeWarnings(): Set<OperatorNotice.Warning> =
        mapTo(mutableSetOf()) { warning ->
            when (warning) {
                NanoKvmScriptRunWarning.FOREGROUND_REQUEST_CANCELLATION_DOES_NOT_STOP_PROCESS ->
                    OperatorNotice.Warning.ForegroundCancellationDoesNotStopProcess
                NanoKvmScriptRunWarning.BACKGROUND_HAS_NO_STATUS_OR_CANCELLATION ->
                    OperatorNotice.Warning.BackgroundHasNoStatusOrCancellation
            }
        }

    private fun operatorFailure(
        binding: NanoKvmSessionBinding,
        error: NanoKvmOperatorError,
    ): OperatorNotice.Failure {
        if (error.kind == NanoKvmOperatorError.Kind.AUTHENTICATION_EXPIRED) {
            expireAuthenticatedSession(binding)
        }
        return when (error.kind) {
            NanoKvmOperatorError.Kind.SESSION_CHANGED -> OperatorNotice.Failure.SessionChanged
            NanoKvmOperatorError.Kind.NOT_FOREGROUND -> OperatorNotice.Failure.NotForeground
            NanoKvmOperatorError.Kind.ELEVATED_APPROVAL_REQUIRED ->
                OperatorNotice.Failure.ElevatedApprovalRequired
            NanoKvmOperatorError.Kind.ALREADY_ACTIVE -> OperatorNotice.Failure.AlreadyActive
            NanoKvmOperatorError.Kind.NOT_CONNECTED -> OperatorNotice.Failure.NotConnected
            NanoKvmOperatorError.Kind.FOREIGN_OR_STALE_STATE ->
                OperatorNotice.Failure.ForeignOrStaleState
            NanoKvmOperatorError.Kind.INVALID_REQUEST -> OperatorNotice.Failure.InvalidRequest
            NanoKvmOperatorError.Kind.AUTHENTICATION_EXPIRED ->
                OperatorNotice.Failure.AuthenticationExpired
            NanoKvmOperatorError.Kind.CONNECTION -> OperatorNotice.Failure.Connection
            NanoKvmOperatorError.Kind.SERVER_REJECTED -> OperatorNotice.Failure.ServerRejected
            NanoKvmOperatorError.Kind.INVALID_RESPONSE -> OperatorNotice.Failure.InvalidResponse
            NanoKvmOperatorError.Kind.UNEXPECTED -> OperatorNotice.Failure.Unexpected
        }
    }

    private fun publishOperatorNotice(
        binding: NanoKvmSessionBinding,
        notice: OperatorNotice,
    ) {
        updateOperatorIfCurrent(binding) {
            it.copy(notice = notice)
        }
    }

    private fun rejectOperatorUiCommand(notice: OperatorNotice) {
        mutableOperatorState.update {
            it.copy(notice = notice)
        }
    }

    private fun currentOperatorBindingLocked(): NanoKvmSessionBinding? {
        check(Thread.holdsLock(stateLock))
        if (!operatorSurfaceVisible) return null
        return currentSessionBindingLocked()
    }

    private inline fun updateOperatorIfCurrent(
        binding: NanoKvmSessionBinding,
        transform: (OperatorUiState) -> OperatorUiState,
    ) {
        mutableOperatorState.update { current ->
            if (currentOperatorBinding() == binding) transform(current) else current
        }
    }

    private fun installPicoClawGatewayLocked(
        activeSession: AuthenticatedNanoKvmSession,
        sessionGeneration: Long,
    ) {
        check(Thread.holdsLock(stateLock))
        val priorBinding = picoClawLifecycle.binding()
        val prior = priorBinding?.let(picoClawLifecycle::resolve)
        picoClawLifecycle.clear()
        prior?.close()
        clearPicoClawHistoryHandlesLocked()
        val version = activeSession.capabilities.applicationVersion
        if (version == null || version < MINIMUM_PICOCLAW_VERSION) {
            mutablePicoClawState.value = PicoClawUiState(support = PicoClawSupport.Unsupported)
            return
        }
        val binding = NanoKvmSessionBinding(
            activeSession.profileId,
            activeSession.authority,
            sessionGeneration,
        )
        picoClawLifecycle.install(binding) {
            activeSession.createPicoClawFeatureGateway(
                sessionGeneration = sessionGeneration,
                currentBinding = ::currentPicoClawBinding,
                scope = scope,
            )
        }
        mutablePicoClawState.value = PicoClawUiState(support = PicoClawSupport.Supported)
    }

    private fun currentPicoClawBinding(): NanoKvmSessionBinding? = synchronized(stateLock) {
        if (!picoClawSurfaceVisible) return@synchronized null
        currentSessionBindingLocked()
    }

    private fun invalidatePicoClawGateway() {
        val detached = synchronized(stateLock) {
            val binding = picoClawLifecycle.binding()
            val gateway = binding?.let(picoClawLifecycle::resolve)
            picoClawLifecycle.clear()
            clearPicoClawHistoryHandlesLocked()
            val jobs = listOfNotNull(
                picoClawActionJob.also { picoClawActionJob = null },
                picoClawChatStateJob.also { picoClawChatStateJob = null },
                picoClawChatLockJob.also { picoClawChatLockJob = null },
                picoClawChatEventJob.also { picoClawChatEventJob = null },
            )
            gateway to jobs
        }
        detached.second.forEach { it.cancel(CancellationException("PicoClaw session invalidated")) }
        detached.first?.close()
        mutablePicoClawState.value = PicoClawUiState()
    }

    private fun installAutomationGatewayLocked(
        activeSession: AuthenticatedNanoKvmSession,
        sessionGeneration: Long,
    ) {
        check(Thread.holdsLock(stateLock))
        val priorBinding = automationLifecycle.binding()
        val prior = priorBinding?.let(automationLifecycle::resolve)
        automationLifecycle.clear()
        prior?.close()
        val binding = NanoKvmSessionBinding(
            activeSession.profileId,
            activeSession.authority,
            sessionGeneration,
        )
        val gateway = automationLifecycle.install(binding) {
            activeSession.createAutomationGateway(
                sessionGeneration = sessionGeneration,
                currentBinding = ::currentAutomationBinding,
                releaseAllInput = {
                    val connectedInput = synchronized(stateLock) {
                        input.takeIf { acceptingCommands && foreground && !closed }
                    }
                    if (connectedInput == null) {
                        false
                    } else {
                        releaseAllInputNow(connectedInput)
                        true
                    }
                },
                currentInput = {
                    synchronized(stateLock) {
                        input.takeIf { acceptingCommands && foreground && !closed }
                    }
                },
                onAuthenticationExpired = { expireAuthenticatedSession(binding) },
            )
        }
        if (automationSurfaceVisible && foreground) gateway.onForeground()
    }

    private fun currentAutomationBinding(): NanoKvmSessionBinding? = synchronized(stateLock) {
        if (!automationSurfaceVisible) return@synchronized null
        currentSessionBindingLocked()
    }

    private fun invalidateAutomationGateway() {
        val detached = synchronized(stateLock) {
            val binding = automationLifecycle.binding()
            val gateway = binding?.let(automationLifecycle::resolve)
            automationLifecycle.clear()
            gateway
        }
        detached?.onBackground()
        detached?.close()
    }

    private fun installOfflineUpdateGatewayLocked(
        activeSession: AuthenticatedNanoKvmSession,
        sessionGeneration: Long,
    ) {
        check(Thread.holdsLock(stateLock))
        val priorBinding = offlineUpdateLifecycle.binding()
        val prior = priorBinding?.let(offlineUpdateLifecycle::resolve)
        offlineUpdateLifecycle.clear()
        prior?.close()
        val binding = NanoKvmSessionBinding(
            activeSession.profileId,
            activeSession.authority,
            sessionGeneration,
        )
        offlineUpdateLifecycle.install(binding) {
            activeSession.createOfflineUpdateGateway(
                sessionGeneration = sessionGeneration,
                scope = scope,
                currentBinding = ::currentOfflineUpdateBinding,
                onAuthenticationExpired = { expireAuthenticatedSession(binding) },
            )
        }
    }

    /**
     * Activates a newly installed gateway only after [mutableSession] publishes its binding.
     * Calling its availability methods from the install transaction observes the prior generation
     * and can leave an already-visible dialog stuck in SESSION_CHANGED.
     */
    private fun activateOfflineUpdateGatewayIfCurrent() {
        val activation = synchronized(stateLock) {
            val installedBinding = offlineUpdateLifecycle.binding() ?: return
            if (currentSessionBindingLocked() != installedBinding) return
            val gateway = offlineUpdateLifecycle.resolve(installedBinding) ?: return
            OfflineUpdateActivation(gateway, foreground, offlineUpdateSurfaceVisible)
        }
        activation.gateway.setForeground(activation.foreground)
        activation.gateway.setSurfaceVisible(activation.surfaceVisible)
    }

    private fun currentOfflineUpdateBinding(): NanoKvmSessionBinding? = synchronized(stateLock) {
        if (!offlineUpdateSurfaceVisible) return@synchronized null
        currentSessionBindingLocked()
    }

    private fun invalidateOfflineUpdateGateway() {
        val detached = synchronized(stateLock) {
            val binding = offlineUpdateLifecycle.binding()
            val gateway = binding?.let(offlineUpdateLifecycle::resolve)
            offlineUpdateLifecycle.clear()
            gateway
        }
        detached?.invalidateSession()
        detached?.close()
    }

    private fun startPicoClawSurfaceWorkIfNeeded() {
        val binding = currentPicoClawBinding()
        val canLoad = picoClawSurfaceCanLoad(
            visible = picoClawSurfaceVisible,
            foreground = foreground,
            currentBinding = binding,
            installedBinding = picoClawLifecycle.binding(),
        )
        val gateway = if (canLoad) binding?.let(picoClawLifecycle::resolve) else null
        if (binding == null || gateway == null) {
            if (
                picoClawSurfaceVisible &&
                mutablePicoClawState.value.support != PicoClawSupport.Unsupported
            ) {
                rejectPicoClawUiCommand(
                    PicoClawNotice.Guidance(
                        PicoClawNotice.GuidanceReason.ConnectBeforeOpeningFeature,
                    ),
                )
            }
            return
        }
        startPicoClawCollectors(binding, gateway)
        // Deliberately no status call here. Only enterPicoClaw crosses the feature-entry boundary.
    }

    private fun startPicoClawCollectors(
        binding: NanoKvmSessionBinding,
        gateway: NanoKvmPicoClawFeatureGateway,
    ) {
        synchronized(stateLock) {
            if (picoClawChatStateJob == null) {
                picoClawChatStateJob = scope.launch {
                    gateway.chat.state.collect { state ->
                        updatePicoClawIfCurrent(binding) { current ->
                            current.copy(
                                chatPhase = when (state) {
                                    NanoKvmPicoClawChatConnectionState.Inactive ->
                                        PicoClawChatUiPhase.Inactive
                                    NanoKvmPicoClawChatConnectionState.Connecting ->
                                        PicoClawChatUiPhase.Connecting
                                    NanoKvmPicoClawChatConnectionState.Open ->
                                        PicoClawChatUiPhase.Open
                                    NanoKvmPicoClawChatConnectionState.Closing ->
                                        PicoClawChatUiPhase.Closing
                                    NanoKvmPicoClawChatConnectionState.Closed ->
                                        PicoClawChatUiPhase.Closed
                                    is NanoKvmPicoClawChatConnectionState.Failed ->
                                        PicoClawChatUiPhase.Failed
                                },
                            )
                        }
                    }
                }
            }
            if (picoClawChatLockJob == null) {
                picoClawChatLockJob = scope.launch {
                    gateway.chat.manualInput.collect { state ->
                        updatePicoClawIfCurrent(binding) {
                            it.copy(
                                manualInput = when (state) {
                                    NanoKvmPicoClawManualInputState.Released ->
                                        PicoClawManualInputUiState.Released
                                    NanoKvmPicoClawManualInputState.Acquiring ->
                                        PicoClawManualInputUiState.Acquiring
                                    NanoKvmPicoClawManualInputState.Held ->
                                        PicoClawManualInputUiState.Held
                                    NanoKvmPicoClawManualInputState.HeldByOther ->
                                        PicoClawManualInputUiState.HeldByOther
                                    NanoKvmPicoClawManualInputState.Releasing ->
                                        PicoClawManualInputUiState.Releasing
                                    NanoKvmPicoClawManualInputState.ReleaseUncertain ->
                                        PicoClawManualInputUiState.Uncertain
                                },
                            )
                        }
                    }
                }
            }
            if (picoClawChatEventJob == null) {
                picoClawChatEventJob = scope.launch {
                    gateway.chat.events.collect { event ->
                        when (event) {
                            is NanoKvmPicoClawChatEvent.AssistantMessage ->
                                appendPicoClawChatMessage(
                                    binding,
                                    PicoClawMessageUiState(
                                        PicoClawMessageContent.ApplianceText(
                                            PicoClawMessageRole.Assistant,
                                            event.text,
                                        ),
                                    ),
                                )
                            is NanoKvmPicoClawChatEvent.Observation ->
                                appendPicoClawChatMessage(
                                    binding,
                                    PicoClawMessageUiState(
                                        event.text?.let { text ->
                                            PicoClawMessageContent.ApplianceText(
                                                PicoClawMessageRole.Observation,
                                                text,
                                            )
                                        } ?: PicoClawMessageContent.ScreenObservationCaptured,
                                    ),
                                )
                            is NanoKvmPicoClawChatEvent.ToolAction ->
                                appendPicoClawChatMessage(
                                    binding,
                                    PicoClawMessageUiState(
                                        PicoClawMessageContent.ToolAction(event.action),
                                    ),
                                )
                            NanoKvmPicoClawChatEvent.RemoteError -> publishPicoClawNotice(
                                binding,
                                PicoClawNotice.Error(PicoClawNotice.Failure.ProviderOrRuntime),
                            )
                            NanoKvmPicoClawChatEvent.TypingStarted,
                            NanoKvmPicoClawChatEvent.TypingStopped,
                            NanoKvmPicoClawChatEvent.FutureMessage -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun beginPicoClawAction(
        destination: ApprovedPicoClawDestination,
        action: PicoClawNotice.Action,
        onCompletion: () -> Unit = {},
        block: suspend (NanoKvmPicoClawFeatureGateway, NanoKvmSessionBinding) -> Unit,
    ): Boolean {
        val selection = resolvePicoClawGateway(destination) ?: return false
        lateinit var created: Job
        val accepted = synchronized(stateLock) {
            if (picoClawActionJob != null) {
                false
            } else {
                created = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        cancelPasteAndJoin(userInitiated = false)
                        releaseAllInputNow()
                        if (currentPicoClawBinding() != selection.second) {
                            rejectPicoClawUiCommand(
                                PicoClawNotice.Guidance(
                                    PicoClawNotice.GuidanceReason.DestinationChangedBeforeAction,
                                ),
                            )
                            return@launch
                        }
                        block(selection.first, selection.second)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        publishPicoClawNotice(
                            selection.second,
                            PicoClawNotice.ActionOutcome(
                                action = action,
                                outcome = PicoClawNotice.Outcome.Indeterminate,
                            ),
                        )
                    } finally {
                        synchronized(stateLock) {
                            if (picoClawActionJob === created) picoClawActionJob = null
                        }
                        updatePicoClawIfCurrent(selection.second) {
                            it.copy(operationInProgress = false, loading = false)
                        }
                    }
                }
                created.invokeOnCompletion { onCompletion() }
                picoClawActionJob = created
                true
            }
        }
        if (!accepted) {
            rejectPicoClawUiCommand(
                PicoClawNotice.Guidance(PicoClawNotice.GuidanceReason.AnotherActionRunning),
            )
            return false
        }
        updatePicoClawIfCurrent(selection.second) {
            it.copy(operationInProgress = true, loading = true, notice = null)
        }
        created.start()
        return true
    }

    private fun resolvePicoClawGateway(
        destination: ApprovedPicoClawDestination,
    ): Pair<NanoKvmPicoClawFeatureGateway, NanoKvmSessionBinding>? {
        val binding = currentPicoClawBinding()
        val gateway = binding?.let(picoClawLifecycle::resolve)
        if (binding == null || gateway == null || !destination.matches(binding)) {
            rejectPicoClawUiCommand(
                if (mutablePicoClawState.value.support == PicoClawSupport.Unsupported) {
                    PicoClawNotice.Guidance(
                        PicoClawNotice.GuidanceReason.UnsupportedApplicationVersion,
                    )
                } else {
                    PicoClawNotice.Guidance(
                        PicoClawNotice.GuidanceReason.DestinationChangedReviewAction,
                    )
                },
            )
            return null
        }
        return gateway to binding
    }

    private fun resolvePicoClawHistory(
        historyId: Long,
    ): Pair<NanoKvmPicoClawHistoryCatalogSnapshot, NanoKvmPicoClawHistoryItem>? =
        synchronized(stateLock) {
            val catalog = picoClawHistoryCatalog ?: return@synchronized null
            val item = picoClawHistoryHandles[historyId] ?: return@synchronized null
            catalog to item
        }

    private fun clearPicoClawHistoryHandlesLocked() {
        check(Thread.holdsLock(stateLock))
        picoClawHistoryCatalog = null
        picoClawHistoryHandles = emptyMap()
    }

    private fun publishPicoClawRuntime(
        binding: NanoKvmSessionBinding,
        snapshot: NanoKvmPicoClawRuntimeSnapshot,
    ) {
        updatePicoClawIfCurrent(binding) { current ->
            current.copy(
                entered = true,
                installed = snapshot.installed,
                ready = snapshot.ready,
                installing = snapshot.installing,
                installProgress = snapshot.installProgress,
                runtimePhase = snapshot.phase.toConsolePhase(),
                profile = when (snapshot.agentProfile) {
                    NanoKvmPicoClawAgentProfile.DEFAULT -> PicoClawProfile.Default
                    NanoKvmPicoClawAgentProfile.KVM -> PicoClawProfile.Kvm
                    null -> null
                },
                modelConfigured = snapshot.modelConfigured,
                modelName = snapshot.modelName,
                loading = false,
            )
        }
    }

    private fun publishPicoClawMutation(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPicoClawMutationResult<NanoKvmPicoClawRuntimeSnapshot>,
        action: PicoClawNotice.Action,
    ) {
        when (result) {
            is NanoKvmPicoClawMutationResult.Applied -> {
                publishPicoClawRuntime(binding, result.state)
                publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(action, PicoClawNotice.Outcome.Applied),
                )
            }
            is NanoKvmPicoClawMutationResult.AlreadySatisfied -> {
                publishPicoClawRuntime(binding, result.state)
                publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(
                        action,
                        PicoClawNotice.Outcome.AlreadySatisfied,
                    ),
                )
            }
            is NanoKvmPicoClawMutationResult.Accepted -> {
                result.state?.let { publishPicoClawRuntime(binding, it) }
                publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(
                        action,
                        PicoClawNotice.Outcome.AcceptedWithoutConfirmation,
                    ),
                )
            }
            is NanoKvmPicoClawMutationResult.Reconciled -> {
                publishPicoClawRuntime(binding, result.state)
                publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(
                        action = action,
                        outcome = if (
                            result.observation ==
                            NanoKvmPicoClawRuntimeObservation.DESIRED_STATE
                        ) {
                            PicoClawNotice.Outcome.ReconciledToRequestedState
                        } else {
                            PicoClawNotice.Outcome.ReconciledToDifferentState
                        },
                    ),
                )
            }
            is NanoKvmPicoClawMutationResult.Indeterminate -> {
                result.state?.let { publishPicoClawRuntime(binding, it) }
                publishPicoClawNotice(
                    binding,
                    PicoClawNotice.ActionOutcome(action, PicoClawNotice.Outcome.Indeterminate),
                )
            }
            is NanoKvmPicoClawMutationResult.Rejected ->
                publishPicoClawError(binding, result.error)
        }
    }

    private fun publishPicoClawHistories(
        binding: NanoKvmSessionBinding,
        catalog: NanoKvmPicoClawHistoryCatalogSnapshot,
    ) {
        val entries = synchronized(stateLock) {
            if (currentPicoClawBinding() != binding) return
            picoClawHistoryCatalog = catalog
            picoClawHistoryHandles = emptyMap()
            catalog.items.map { item ->
                val id = ++picoClawHistoryHandleCounter
                picoClawHistoryHandles = picoClawHistoryHandles + (id to item)
                PicoClawHistoryUiState(id, item.title, item.preview, item.messageCount)
            }
        }
        updatePicoClawIfCurrent(binding) {
            it.copy(
                historiesLoaded = true,
                histories = entries,
                selectedHistoryTitle = null,
                selectedHistoryMessages = emptyList(),
            )
        }
    }

    private fun publishPicoClawChatAction(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPicoClawChatActionResult,
        action: PicoClawNotice.Action,
    ) {
        when (result) {
            NanoKvmPicoClawChatActionResult.Dispatched,
            is NanoKvmPicoClawChatActionResult.MessageDispatched -> publishPicoClawNotice(
                binding,
                PicoClawNotice.ActionOutcome(action, PicoClawNotice.Outcome.Dispatched),
            )
            is NanoKvmPicoClawChatActionResult.Rejected ->
                publishPicoClawError(binding, result.error)
        }
    }

    private fun publishPicoClawError(
        binding: NanoKvmSessionBinding,
        error: NanoKvmPicoClawError,
    ) {
        if (error.kind == NanoKvmPicoClawError.Kind.AUTHENTICATION_EXPIRED) {
            expireAuthenticatedSession(binding)
        }
        publishPicoClawNotice(
            binding,
            PicoClawNotice.Error(picoClawFailure(error)),
        )
    }

    private fun picoClawFailure(error: NanoKvmPicoClawError): PicoClawNotice.Failure =
        when (error.kind) {
            NanoKvmPicoClawError.Kind.SESSION_CHANGED -> PicoClawNotice.Failure.SessionChanged
            NanoKvmPicoClawError.Kind.FEATURE_ENTRY_REQUIRED ->
                PicoClawNotice.Failure.FeatureEntryRequired
            NanoKvmPicoClawError.Kind.APPROVAL_REQUIRED ->
                PicoClawNotice.Failure.ApprovalRequired
            NanoKvmPicoClawError.Kind.ALREADY_ACTIVE -> PicoClawNotice.Failure.AlreadyActive
            NanoKvmPicoClawError.Kind.NOT_CONNECTED -> PicoClawNotice.Failure.NotConnected
            NanoKvmPicoClawError.Kind.FOREIGN_OR_STALE_STATE ->
                PicoClawNotice.Failure.ForeignOrStaleState
            NanoKvmPicoClawError.Kind.INVALID_REQUEST -> PicoClawNotice.Failure.InvalidRequest
            NanoKvmPicoClawError.Kind.AUTHENTICATION_EXPIRED ->
                PicoClawNotice.Failure.AuthenticationExpired
            NanoKvmPicoClawError.Kind.CONNECTION -> PicoClawNotice.Failure.Connection
            NanoKvmPicoClawError.Kind.SERVER_REJECTED -> PicoClawNotice.Failure.ServerRejected
            NanoKvmPicoClawError.Kind.INVALID_RESPONSE -> PicoClawNotice.Failure.InvalidResponse
            NanoKvmPicoClawError.Kind.UNEXPECTED -> PicoClawNotice.Failure.Unexpected
        }

    private fun publishPicoClawNotice(
        binding: NanoKvmSessionBinding,
        notice: PicoClawNotice,
    ) {
        updatePicoClawIfCurrent(binding) {
            it.copy(notice = notice)
        }
    }

    private fun rejectPicoClawUiCommand(notice: PicoClawNotice) {
        mutablePicoClawState.update {
            it.copy(notice = notice)
        }
    }

    private fun appendPicoClawChatMessage(
        binding: NanoKvmSessionBinding,
        message: PicoClawMessageUiState,
    ) {
        updatePicoClawIfCurrent(binding) {
            it.copy(chatMessages = appendBoundedPicoClawMessage(it.chatMessages, message))
        }
    }

    private inline fun updatePicoClawIfCurrent(
        binding: NanoKvmSessionBinding,
        transform: (PicoClawUiState) -> PicoClawUiState,
    ) {
        mutablePicoClawState.update { current ->
            if (currentPicoClawBinding() == binding) transform(current) else current
        }
    }

    private fun clearPhase3HandlesLocked() {
        check(Thread.holdsLock(stateLock))
        phase3MediaCatalog = null
        phase3MediaHandles = emptyMap()
        phase3WakeSnapshot = null
        phase3WakeHandles = emptyMap()
    }

    private fun startPhase3SurfaceWorkIfNeeded() {
        if (!phase3SurfaceVisible || !foreground) return
        startPhase3RefreshIfPossible()
    }

    private fun startPhase3RefreshIfPossible() {
        val binding = currentPhase3Binding()
        val gateway = binding?.let(phase3Lifecycle::resolve)
        if (!phase3SurfaceVisible || binding == null || gateway == null) {
            if (phase3SurfaceVisible) {
                rejectPhase3UiCommand(
                    Phase3Notice.Guidance(
                        Phase3Notice.GuidanceReason.ConnectBeforeOpeningFeatures,
                    ),
                )
            }
            return
        }
        lateinit var created: Job
        val accepted = synchronized(stateLock) {
            if (phase3RefreshJob != null || phase3ActionJob != null) {
                false
            } else {
                clearPhase3HandlesLocked()
                created = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        refreshPhase3Snapshot(binding, gateway)
                    } finally {
                        synchronized(stateLock) {
                            if (phase3RefreshJob === created) phase3RefreshJob = null
                        }
                        updatePhase3IfCurrent(binding) { it.copy(loading = false) }
                    }
                }
                phase3RefreshJob = created
                true
            }
        }
        if (!accepted) return
        updatePhase3IfCurrent(binding) {
            it.copy(
                loading = true,
                notice = null,
                virtualMediaNotice = null,
                wakeOnLanNotice = null,
                hidMode = null,
                virtualMedia = Phase3VirtualMediaUiState(),
                wakeOnLanLoaded = false,
                wakeOnLanTargets = emptyList(),
            )
        }
        created.start()
    }

    private suspend fun refreshPhase3Snapshot(
        binding: NanoKvmSessionBinding,
        gateway: NanoKvmPhase3FeatureGateway,
    ) {
        when (val hidMode = gateway.refreshHidMode()) {
            is NanoKvmPhase3ReadResult.Success -> publishHidMode(binding, hidMode.state)
            is NanoKvmPhase3ReadResult.Failure -> publishPhase3ReadFailure(
                binding,
                hidMode.error,
                Phase3NoticeScope.VirtualMedia,
            )
        }
        when (val media = gateway.refreshMedia()) {
            is NanoKvmPhase3ReadResult.Success -> publishMedia(binding, media.state)
            is NanoKvmPhase3ReadResult.Failure -> publishPhase3ReadFailure(
                binding,
                media.error,
                Phase3NoticeScope.VirtualMedia,
            )
        }
        when (val devices = gateway.refreshVirtualDevices()) {
            is NanoKvmPhase3ReadResult.Success -> publishVirtualDevices(binding, devices.state)
            is NanoKvmPhase3ReadResult.Failure -> publishPhase3ReadFailure(
                binding,
                devices.error,
                Phase3NoticeScope.VirtualMedia,
            )
        }
        when (val transfer = gateway.refreshImageTransfer()) {
            is NanoKvmPhase3ReadResult.Success -> {
                publishTransfer(binding, transfer.state)
                startPhase3TransferPollingIfNeeded(binding, gateway, transfer.state)
            }
            is NanoKvmPhase3ReadResult.Failure -> publishPhase3ReadFailure(
                binding,
                transfer.error,
                Phase3NoticeScope.VirtualMedia,
            )
        }
        when (val wakeOnLan = gateway.refreshWakeOnLan()) {
            is NanoKvmPhase3ReadResult.Success -> publishWakeOnLan(binding, wakeOnLan.state)
            is NanoKvmPhase3ReadResult.Failure -> publishPhase3ReadFailure(
                binding,
                wakeOnLan.error,
                Phase3NoticeScope.WakeOnLan,
            )
        }
    }

    private fun beginPhase3Action(
        destination: ApprovedPhase3Destination,
        action: Phase3Notice.Action,
        releaseInput: Boolean,
        noticeScope: Phase3NoticeScope,
        block: suspend (NanoKvmPhase3FeatureGateway, NanoKvmSessionBinding) -> Unit,
    ) {
        if (
            releaseInput &&
            mutableSession.value.phase3.virtualMedia.transferPhase ==
            Phase3TransferPhase.InProgress
        ) {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(Phase3Notice.GuidanceReason.WaitForImageTransfer),
                noticeScope,
            )
            return
        }
        val binding = currentPhase3Binding()
        val gateway = binding?.let(phase3Lifecycle::resolve)
        if (binding == null || gateway == null || !destination.matches(binding)) {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(
                    Phase3Notice.GuidanceReason.ReviewActionAfterSessionChange,
                ),
                noticeScope,
            )
            return
        }

        lateinit var created: Job
        val accepted = synchronized(stateLock) {
            if (phase3ActionJob != null || phase3RefreshJob != null) {
                false
            } else {
                created = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        cancelPasteAndJoin(userInitiated = false)
                        if (releaseInput) releaseAllInputNow()
                        if (currentPhase3Binding() != binding) {
                            publishPhase3Notice(
                                binding,
                                Phase3Notice.Guidance(
                                    Phase3Notice.GuidanceReason.SessionChangedBeforeSend,
                                ),
                                noticeScope,
                            )
                            return@launch
                        }
                        block(gateway, binding)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        publishPhase3Notice(
                            binding,
                            Phase3Notice.ActionOutcome(
                                action = action,
                                outcome = Phase3Notice.Outcome.Indeterminate,
                            ),
                            noticeScope,
                        )
                    } finally {
                        synchronized(stateLock) {
                            if (phase3ActionJob === created) phase3ActionJob = null
                        }
                        updatePhase3IfCurrent(binding) { it.copy(operationInProgress = false) }
                    }
                }
                phase3ActionJob = created
                true
            }
        }
        if (!accepted) {
            rejectPhase3UiCommand(
                Phase3Notice.Guidance(Phase3Notice.GuidanceReason.ActionAlreadyRunning),
                noticeScope,
            )
            return
        }
        updatePhase3IfCurrent(binding) {
            when (noticeScope) {
                Phase3NoticeScope.General ->
                    it.copy(operationInProgress = true, notice = null)
                Phase3NoticeScope.VirtualMedia ->
                    it.copy(operationInProgress = true, virtualMediaNotice = null)
                Phase3NoticeScope.WakeOnLan ->
                    it.copy(operationInProgress = true, wakeOnLanNotice = null)
            }
        }
        created.start()
    }

    private fun startPhase3TransferPollingIfNeeded(
        binding: NanoKvmSessionBinding,
        gateway: NanoKvmPhase3FeatureGateway,
        snapshot: NanoKvmImageTransferSnapshot,
    ) {
        if (
            snapshot.phase != NanoKvmTransferPhase.IN_PROGRESS ||
            !phase3SurfaceVisible || !foreground || currentPhase3Binding() != binding
        ) {
            return
        }
        lateinit var created: Job
        val accepted = synchronized(stateLock) {
            if (phase3TransferPollJob != null) {
                false
            } else {
                created = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        while (
                            isActive && phase3SurfaceVisible && foreground &&
                            currentPhase3Binding() == binding
                        ) {
                            delay(PHASE3_TRANSFER_POLL_MILLIS)
                            when (val result = gateway.refreshImageTransfer()) {
                                is NanoKvmPhase3ReadResult.Success -> {
                                    publishTransfer(binding, result.state)
                                    if (result.state.phase != NanoKvmTransferPhase.IN_PROGRESS) {
                                        if (result.state.phase == NanoKvmTransferPhase.IDLE) {
                                            when (val media = gateway.refreshMedia()) {
                                                is NanoKvmPhase3ReadResult.Success -> {
                                                    publishMedia(binding, media.state)
                                                    publishPhase3Notice(
                                                        binding,
                                                        Phase3Notice.Guidance(
                                                            Phase3Notice.GuidanceReason
                                                                .TransferFinishedWithoutChecksum,
                                                        ),
                                                        Phase3NoticeScope.VirtualMedia,
                                                    )
                                                }
                                                is NanoKvmPhase3ReadResult.Failure ->
                                                    publishPhase3ReadFailure(
                                                        binding,
                                                        media.error,
                                                        Phase3NoticeScope.VirtualMedia,
                                                    )
                                            }
                                        } else {
                                            publishPhase3Notice(
                                                binding,
                                                Phase3Notice.Guidance(
                                                    Phase3Notice.GuidanceReason
                                                        .UnexpectedTransferState,
                                                ),
                                                Phase3NoticeScope.VirtualMedia,
                                            )
                                        }
                                        break
                                    }
                                }
                                is NanoKvmPhase3ReadResult.Failure -> {
                                    publishPhase3ReadFailure(
                                        binding,
                                        result.error,
                                        Phase3NoticeScope.VirtualMedia,
                                    )
                                    break
                                }
                            }
                        }
                    } finally {
                        synchronized(stateLock) {
                            if (phase3TransferPollJob === created) phase3TransferPollJob = null
                        }
                    }
                }
                phase3TransferPollJob = created
                true
            }
        }
        if (accepted) created.start()
    }

    private fun publishMedia(
        binding: NanoKvmSessionBinding,
        catalog: NanoKvmMediaCatalog,
    ) {
        val images = synchronized(stateLock) {
            if (currentPhase3BindingLocked() != binding) return
            phase3MediaCatalog = catalog
            catalog.images.associate { image -> (++phase3HandleCounter) to image }
                .also { phase3MediaHandles = it }
                .map { (id, image) ->
                    Phase3MediaImageUiState(
                        id = id,
                        displayName = image.displayName,
                        mounted = catalog.mountedImage === image,
                    )
                }
        }
        updatePhase3IfCurrent(binding) { current ->
            current.copy(
                virtualMedia = current.virtualMedia.copy(
                    loaded = true,
                    images = images,
                    mountedDisplayName = catalog.mountedImage?.displayName,
                    hasUnlistedMountedImage = catalog.hasUnlistedMountedImage,
                    cdRomEnabled = catalog.cdRomEnabled,
                ),
            )
        }
    }

    private fun publishVirtualDevices(
        binding: NanoKvmSessionBinding,
        snapshot: NanoKvmVirtualDeviceSnapshot,
    ) {
        updatePhase3IfCurrent(binding) { current ->
            current.copy(
                virtualMedia = current.virtualMedia.copy(
                    networkEnabled = snapshot.networkEnabled,
                    mediaEnabled = snapshot.mediaEnabled,
                    diskEnabled = snapshot.diskEnabled,
                ),
            )
        }
    }

    private fun publishHidMode(
        binding: NanoKvmSessionBinding,
        snapshot: NanoKvmHidModeSnapshot,
    ) {
        updatePhase3IfCurrent(binding) { current ->
            current.copy(
                hidMode = Phase3HidModeUiState(
                    selection = when (snapshot.selection) {
                        NanoKvmHidModeSelection.NORMAL -> Phase3HidModeSelection.Normal
                        NanoKvmHidModeSelection.HID_ONLY -> Phase3HidModeSelection.HidOnly
                        NanoKvmHidModeSelection.OTHER -> Phase3HidModeSelection.Other
                    },
                    reportedMode = snapshot.reportedMode,
                ),
            )
        }
    }

    private fun publishTransfer(
        binding: NanoKvmSessionBinding,
        snapshot: NanoKvmImageTransferSnapshot,
    ) {
        updatePhase3IfCurrent(binding) { current ->
            current.copy(
                virtualMedia = current.virtualMedia.copy(
                    remoteTransferEnabled = snapshot.enabled,
                    transferPhase = when (snapshot.phase) {
                        NanoKvmTransferPhase.IDLE -> Phase3TransferPhase.Idle
                        NanoKvmTransferPhase.IN_PROGRESS -> Phase3TransferPhase.InProgress
                        NanoKvmTransferPhase.OTHER -> Phase3TransferPhase.Other
                    },
                    transferPercentage = snapshot.percentage,
                ),
            )
        }
    }

    private fun publishWakeOnLan(
        binding: NanoKvmSessionBinding,
        snapshot: NanoKvmWakeOnLanSnapshot,
    ) {
        val targets = synchronized(stateLock) {
            if (currentPhase3BindingLocked() != binding) return
            phase3WakeSnapshot = snapshot
            snapshot.targets.associate { target -> (++phase3HandleCounter) to target }
                .also { phase3WakeHandles = it }
                .map { (id, target) ->
                    Phase3WakeOnLanTargetUiState(
                        id = id,
                        macAddress = target.macAddress.value,
                        name = target.name,
                    )
                }
        }
        updatePhase3IfCurrent(binding) { current ->
            current.copy(wakeOnLanLoaded = true, wakeOnLanTargets = targets)
        }
    }

    private suspend fun publishUsbMediaResult(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPhase3MutationResult<NanoKvmMediaCatalog>,
        action: Phase3Notice.Action,
    ) {
        if (!result.requiresInputRecycleAfterUsbMutation()) {
            publishMediaMutation(binding, result, action)
            return
        }
        val notice = phase3MutationNotice(binding, result, action)
        val rebound = recyclePhase3InputAfterUsbMutation(binding) ?: return
        var readbackConfirmed = true
        when (val refreshed = rebound.gateway.refreshMedia()) {
            is NanoKvmPhase3ReadResult.Success -> publishMedia(rebound.binding, refreshed.state)
            is NanoKvmPhase3ReadResult.Failure -> {
                readbackConfirmed = false
                publishPhase3ReadFailure(
                    rebound.binding,
                    refreshed.error,
                    Phase3NoticeScope.VirtualMedia,
                )
            }
        }
        when (val devices = rebound.gateway.refreshVirtualDevices()) {
            is NanoKvmPhase3ReadResult.Success -> publishVirtualDevices(rebound.binding, devices.state)
            is NanoKvmPhase3ReadResult.Failure -> {
                readbackConfirmed = false
                publishPhase3ReadFailure(
                    rebound.binding,
                    devices.error,
                    Phase3NoticeScope.VirtualMedia,
                )
            }
        }
        publishPhase3Notice(
            rebound.binding,
            notice.withInputRecovery(
                if (readbackConfirmed) {
                    Phase3Notice.InputRecovery.Reconnected
                } else {
                    Phase3Notice.InputRecovery.ReconnectedWithPartialReadback
                },
            ),
            Phase3NoticeScope.VirtualMedia,
        )
        updatePhase3IfCurrent(rebound.binding) { it.copy(operationInProgress = false) }
    }

    private suspend fun publishUsbHidModeResult(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPhase3MutationResult<NanoKvmHidModeSnapshot>,
        action: Phase3Notice.Action,
    ) {
        if (!result.requiresInputRecycleAfterUsbMutation()) {
            publishHidModeMutation(binding, result, action)
            return
        }
        val notice = phase3MutationNotice(binding, result, action)
        val rebound = recyclePhase3InputAfterUsbMutation(binding) ?: return
        var readbackConfirmed = true
        when (val hidMode = rebound.gateway.refreshHidMode()) {
            is NanoKvmPhase3ReadResult.Success -> publishHidMode(rebound.binding, hidMode.state)
            is NanoKvmPhase3ReadResult.Failure -> {
                readbackConfirmed = false
                publishPhase3ReadFailure(
                    rebound.binding,
                    hidMode.error,
                    Phase3NoticeScope.VirtualMedia,
                )
            }
        }
        when (val devices = rebound.gateway.refreshVirtualDevices()) {
            is NanoKvmPhase3ReadResult.Success -> publishVirtualDevices(rebound.binding, devices.state)
            is NanoKvmPhase3ReadResult.Failure -> {
                readbackConfirmed = false
                publishPhase3ReadFailure(
                    rebound.binding,
                    devices.error,
                    Phase3NoticeScope.VirtualMedia,
                )
            }
        }
        publishPhase3Notice(
            rebound.binding,
            notice.withInputRecovery(
                if (readbackConfirmed) {
                    Phase3Notice.InputRecovery.Reconnected
                } else {
                    Phase3Notice.InputRecovery.ReconnectedWithPartialReadback
                },
            ),
            Phase3NoticeScope.VirtualMedia,
        )
        updatePhase3IfCurrent(rebound.binding) { it.copy(operationInProgress = false) }
    }

    private suspend fun publishUsbDeviceResult(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPhase3MutationResult<NanoKvmVirtualDeviceSnapshot>,
        action: Phase3Notice.Action,
    ) {
        if (!result.requiresInputRecycleAfterUsbMutation()) {
            publishVirtualDeviceMutation(binding, result, action)
            return
        }
        val notice = phase3MutationNotice(binding, result, action)
        val rebound = recyclePhase3InputAfterUsbMutation(binding) ?: return
        var readbackConfirmed = true
        when (val devices = rebound.gateway.refreshVirtualDevices()) {
            is NanoKvmPhase3ReadResult.Success -> publishVirtualDevices(rebound.binding, devices.state)
            is NanoKvmPhase3ReadResult.Failure -> {
                readbackConfirmed = false
                publishPhase3ReadFailure(
                    rebound.binding,
                    devices.error,
                    Phase3NoticeScope.VirtualMedia,
                )
            }
        }
        publishPhase3Notice(
            rebound.binding,
            notice.withInputRecovery(
                if (readbackConfirmed) {
                    Phase3Notice.InputRecovery.Reconnected
                } else {
                    Phase3Notice.InputRecovery.ReconnectedWithoutReadback
                },
            ),
            Phase3NoticeScope.VirtualMedia,
        )
        updatePhase3IfCurrent(rebound.binding) { it.copy(operationInProgress = false) }
    }

    private fun Phase3Notice.withInputRecovery(
        recovery: Phase3Notice.InputRecovery,
    ): Phase3Notice = when (this) {
        is Phase3Notice.ActionOutcome -> copy(inputRecovery = recovery)
        is Phase3Notice.Error -> copy(inputRecovery = recovery)
        is Phase3Notice.Guidance -> this
    }

    private suspend fun recyclePhase3InputAfterUsbMutation(
        expectedBinding: NanoKvmSessionBinding,
    ): ReboundPhase3Session? {
        var reconnectFailure: ReconnectFailure? = null
        val rebound = lifecycleMutex.withLock {
            if (currentPhase3Binding() != expectedBinding || closed || !foreground) {
                return@withLock null
            }
            val activeSession = authenticatedSession ?: return@withLock null
            val activeClient = activeSession.client
            inputMonitor?.cancel()
            inputMonitor = null
            val oldInput = synchronized(stateLock) {
                acceptingCommands = false
                commandAcceptanceEpoch++
                input.also { input = null }
            }
            releaseAllInputNow(oldInput)
            oldInput?.close()

            var pendingInput: NanoKvmInputSocket? = null
            var failureStatus: Int? = null
            try {
                val replacement = activeClient.newInputSocket()
                pendingInput = replacement
                replacement.connect()
                val state = withTimeoutOrNull(INPUT_CONNECT_TIMEOUT_MS) {
                    replacement.state.first {
                        it is InputConnectionState.Connected || it is InputConnectionState.Failed
                    }
                } ?: throw SocketTimeoutException("NanoKVM input WebSocket timed out")
                if (state is InputConnectionState.Failed) {
                    failureStatus = state.httpStatus
                    throw state.cause
                }
                val newBinding: NanoKvmSessionBinding
                val newGateway: NanoKvmPhase3FeatureGateway
                synchronized(stateLock) {
                    val generation = ++sessionGenerationCounter
                    input = replacement
                    installSessionFeatureSetLocked(activeSession, generation)
                    newBinding = checkNotNull(phase3Lifecycle.binding())
                    newGateway = checkNotNull(phase3Lifecycle.resolve(newBinding))
                    mutableSession.update { current ->
                        current.copy(
                            connection = if (current.connection == ConnectionState.Degraded) {
                                ConnectionState.Degraded
                            } else {
                                ConnectionState.Connected
                            },
                            sessionGeneration = generation,
                            phase3 = Phase3FeatureUiState(
                                available = true,
                                operationInProgress = true,
                            ),
                            administration = AdministrationUiState(available = true),
                        )
                    }
                    openCommandAcceptanceLocked()
                }
                pendingInput = null
                activateOfflineUpdateGatewayIfCurrent()
                monitorInput(replacement, newBinding)
                startPicoClawSurfaceWorkIfNeeded()
                ReboundPhase3Session(newBinding, newGateway)
            } catch (cancelled: CancellationException) {
                pendingInput?.close()
                throw cancelled
            } catch (error: Throwable) {
                pendingInput?.close()
                reconnectFailure = ReconnectFailure(error, failureStatus)
                null
            }
        }
        reconnectFailure?.let { failure ->
            scheduleReconnect(
                failure,
                immediateFirstAttempt = false,
                expectedBinding = expectedBinding,
            )
        }
        return rebound
    }

    private fun resumeInputMonitoringAfterPhase3UsbMutation(
        expectedBinding: NanoKvmSessionBinding,
    ) {
        phase3UsbMutationInFlight = false
        val current = synchronized(stateLock) { input }?.state?.value
        when (current) {
            is InputConnectionState.Failed -> scheduleReconnect(
                ReconnectFailure(current.cause, current.httpStatus),
                immediateFirstAttempt = false,
                expectedBinding = expectedBinding,
            )
            InputConnectionState.Disconnected -> if (foreground) {
                scheduleReconnect(
                    ReconnectFailure(IOException("Input connection closed")),
                    immediateFirstAttempt = false,
                    expectedBinding = expectedBinding,
                )
            }
            else -> Unit
        }
    }

    private fun publishMediaMutation(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPhase3MutationResult<NanoKvmMediaCatalog>,
        action: Phase3Notice.Action,
    ) {
        phase3MutationState(result)?.let { publishMedia(binding, it) }
        publishPhase3Notice(
            binding,
            phase3MutationNotice(binding, result, action),
            Phase3NoticeScope.VirtualMedia,
        )
    }

    private fun publishHidModeMutation(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPhase3MutationResult<NanoKvmHidModeSnapshot>,
        action: Phase3Notice.Action,
    ) {
        phase3MutationState(result)?.let { publishHidMode(binding, it) }
        publishPhase3Notice(
            binding,
            phase3MutationNotice(binding, result, action),
            Phase3NoticeScope.VirtualMedia,
        )
    }

    private fun publishVirtualDeviceMutation(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPhase3MutationResult<NanoKvmVirtualDeviceSnapshot>,
        action: Phase3Notice.Action,
    ) {
        phase3MutationState(result)?.let { publishVirtualDevices(binding, it) }
        publishPhase3Notice(
            binding,
            phase3MutationNotice(binding, result, action),
            Phase3NoticeScope.VirtualMedia,
        )
    }

    private fun publishTransferMutation(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPhase3MutationResult<NanoKvmImageTransferSnapshot>,
        action: Phase3Notice.Action,
    ) {
        phase3MutationState(result)?.let { publishTransfer(binding, it) }
        publishPhase3Notice(
            binding,
            phase3MutationNotice(binding, result, action),
            Phase3NoticeScope.VirtualMedia,
        )
    }

    private fun publishWakeOnLanMutation(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPhase3MutationResult<NanoKvmWakeOnLanSnapshot>,
        action: Phase3Notice.Action,
    ) {
        phase3MutationState(result)?.let { publishWakeOnLan(binding, it) }
        publishPhase3Notice(
            binding,
            phase3MutationNotice(binding, result, action),
            Phase3NoticeScope.WakeOnLan,
        )
    }

    private fun <State> phase3MutationState(
        result: NanoKvmPhase3MutationResult<State>,
    ): State? = when (result) {
        is NanoKvmPhase3MutationResult.Applied -> result.state
        is NanoKvmPhase3MutationResult.AlreadySatisfied -> result.state
        is NanoKvmPhase3MutationResult.Accepted -> result.state
        is NanoKvmPhase3MutationResult.Reconciled -> result.state
        is NanoKvmPhase3MutationResult.Indeterminate -> result.state
        is NanoKvmPhase3MutationResult.Rejected -> null
    }

    private fun phase3TransferSnapshot(
        result: NanoKvmPhase3MutationResult<NanoKvmImageTransferSnapshot>,
    ): NanoKvmImageTransferSnapshot? = phase3MutationState(result)

    private fun phase3MutationNotice(
        binding: NanoKvmSessionBinding,
        result: NanoKvmPhase3MutationResult<*>,
        action: Phase3Notice.Action,
    ): Phase3Notice = when (result) {
        is NanoKvmPhase3MutationResult.Applied -> Phase3Notice.ActionOutcome(
            action = action,
            outcome = Phase3Notice.Outcome.Applied,
        )
        is NanoKvmPhase3MutationResult.AlreadySatisfied -> Phase3Notice.ActionOutcome(
            action = action,
            outcome = Phase3Notice.Outcome.AlreadySatisfied,
        )
        is NanoKvmPhase3MutationResult.Accepted -> Phase3Notice.ActionOutcome(
            action = action,
            outcome = Phase3Notice.Outcome.AcceptedWithoutConfirmation,
        )
        is NanoKvmPhase3MutationResult.Reconciled -> if (
            result.observation == NanoKvmPhase3Observation.DESIRED_STATE
        ) {
            Phase3Notice.ActionOutcome(
                action = action,
                outcome = Phase3Notice.Outcome.ReconciledToRequestedState,
            )
        } else {
            Phase3Notice.ActionOutcome(
                action = action,
                outcome = Phase3Notice.Outcome.ReconciledToDifferentState,
            )
        }
        is NanoKvmPhase3MutationResult.Indeterminate -> Phase3Notice.ActionOutcome(
            action = action,
            outcome = Phase3Notice.Outcome.Indeterminate,
        )
        is NanoKvmPhase3MutationResult.Rejected -> Phase3Notice.Error(
            phase3Failure(binding, result.error),
        )
    }

    private fun publishPhase3ReadFailure(
        binding: NanoKvmSessionBinding,
        error: NanoKvmPhase3Error,
        noticeScope: Phase3NoticeScope = Phase3NoticeScope.General,
    ) {
        if (error.kind == NanoKvmPhase3Error.Kind.UNSUPPORTED) return
        publishPhase3Notice(
            binding,
            Phase3Notice.Error(phase3Failure(binding, error)),
            noticeScope,
        )
    }

    private fun phase3Failure(
        binding: NanoKvmSessionBinding,
        error: NanoKvmPhase3Error,
    ): Phase3Notice.Failure {
        if (error.kind == NanoKvmPhase3Error.Kind.AUTHENTICATION_EXPIRED) {
            expireAuthenticatedSession(binding)
        }
        return when (error.kind) {
            NanoKvmPhase3Error.Kind.SESSION_CHANGED -> Phase3Notice.Failure.SessionChanged
            NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE ->
                Phase3Notice.Failure.ForeignOrStaleState
            NanoKvmPhase3Error.Kind.IMAGE_IS_MOUNTED -> Phase3Notice.Failure.ImageIsMounted
            NanoKvmPhase3Error.Kind.IMAGE_TRANSFER_DISABLED ->
                Phase3Notice.Failure.ImageTransferDisabled
            NanoKvmPhase3Error.Kind.INVALID_REQUEST -> Phase3Notice.Failure.InvalidRequest
            NanoKvmPhase3Error.Kind.UNSUPPORTED -> Phase3Notice.Failure.Unsupported
            NanoKvmPhase3Error.Kind.AUTHENTICATION_EXPIRED ->
                Phase3Notice.Failure.AuthenticationExpired
            NanoKvmPhase3Error.Kind.CONNECTION -> Phase3Notice.Failure.Connection
            NanoKvmPhase3Error.Kind.SERVER_REJECTED -> Phase3Notice.Failure.ServerRejected
            NanoKvmPhase3Error.Kind.INVALID_RESPONSE -> Phase3Notice.Failure.InvalidResponse
            NanoKvmPhase3Error.Kind.UNEXPECTED -> Phase3Notice.Failure.Unexpected
        }
    }

    private fun publishPhase3Notice(
        binding: NanoKvmSessionBinding,
        notice: Phase3Notice,
        noticeScope: Phase3NoticeScope = Phase3NoticeScope.General,
    ) {
        updatePhase3IfCurrent(binding) {
            when (noticeScope) {
                Phase3NoticeScope.General -> it.copy(notice = notice)
                Phase3NoticeScope.VirtualMedia -> it.copy(virtualMediaNotice = notice)
                Phase3NoticeScope.WakeOnLan -> it.copy(wakeOnLanNotice = notice)
            }
        }
    }

    private fun rejectPhase3UiCommand(
        notice: Phase3Notice,
        noticeScope: Phase3NoticeScope = Phase3NoticeScope.General,
    ) {
        mutableSession.update { current ->
            current.copy(
                phase3 = when (noticeScope) {
                    Phase3NoticeScope.General -> current.phase3.copy(notice = notice)
                    Phase3NoticeScope.VirtualMedia ->
                        current.phase3.copy(virtualMediaNotice = notice)
                    Phase3NoticeScope.WakeOnLan ->
                        current.phase3.copy(wakeOnLanNotice = notice)
                },
            )
        }
    }

    private inline fun updatePhase3IfCurrent(
        binding: NanoKvmSessionBinding,
        transform: (Phase3FeatureUiState) -> Phase3FeatureUiState,
    ) {
        mutableSession.update { current ->
            if (currentPhase3Binding() == binding) {
                current.copy(phase3 = transform(current.phase3))
            } else {
                current
            }
        }
    }

    private fun installApprovedPaste(request: ApprovedPasteRequest) {
        var rejectionMessage: ConsoleMessage.ActionFeedback =
            ConsoleMessage.ClipboardSessionChanged
        val operation = synchronized(stateLock) {
            val activeSession = authenticatedSession
            val activeInput = input
            val current = mutableSession.value
            val destinationMatches = activeSession != null && request.matchesDestination(
                profileId = activeSession.profileId,
                authority = activeSession.authority,
                sessionGeneration = current.sessionGeneration,
            )
            if (
                closed ||
                !foreground ||
                !acceptingCommands ||
                !current.connection.isSessionUsable ||
                activeSession == null ||
                activeInput == null ||
                !destinationMatches
            ) {
                null
            } else {
                val ownership = pasteOperations.start(
                    request.content.codePointCount(0, request.content.length),
                )
                if (ownership == null) {
                    rejectionMessage = ConsoleMessage.ClipboardTypingAlreadyActive
                    null
                } else {
                    val created = ActivePasteOperation(
                        token = ownership.token,
                        request = request,
                        activeSession = activeSession,
                        activeInput = activeInput,
                        commandEpoch = commandAcceptanceEpoch,
                        previousActionFeedback = current.lastActionFeedback,
                        priorKeyboardCommand = queuedKeyboardTail,
                    )
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        created.priorKeyboardCommand?.join()
                        pasteExecutionMutex.withLock { runPacedPaste(created) }
                    }
                    created.job = job
                    job.invokeOnCompletion { completePasteOperation(created) }
                    activePaste = created
                    mutableSession.value = current.copy(
                        pasteProgress = ownership.toRemoteProgress(),
                    ).withActionFeedback(ConsoleMessage.TypingApprovedClipboardText)
                    created
                }
            }
        }

        if (operation == null) {
            mutableSession.update { it.withActionFeedback(rejectionMessage) }
        } else {
            operation.job.start()
        }
    }

    private suspend fun runPacedPaste(operation: ActivePasteOperation) {
        val mayStart = synchronized(stateLock) {
            if (!isCurrentPasteLocked(operation)) {
                false
            } else {
                // Keep the destination check and neutral release behind the same lifecycle
                // barrier. A disconnect/reconnect/background transition cannot slip between them.
                releaseAllInputNow(operation.activeInput)
                true
            }
        }
        if (!mayStart) {
            operation.completionMessage = ConsoleMessage.ClipboardSessionChanged
            return
        }

        // Cancellation is checked before the first character and between every atomic key pair.
        val result = operation.activeInput.sendPacedCommittedText(
            text = operation.request.content,
            layout = operation.request.keyboardLayout.toProtocolLayout(),
            heldModifiers = emptySet(),
            onProgress = { progress -> updatePacedPasteProgress(operation, progress) },
        )
        val total = synchronized(stateLock) {
            pasteOperations.snapshot(operation.token)?.totalKeystrokes
                ?: operation.request.content.codePointCount(0, operation.request.content.length)
        }
        operation.completionMessage = when (result) {
            is PacedCommittedTextResult.Completed -> ConsoleMessage.ClipboardTextTyped
            is PacedCommittedTextResult.Unsupported ->
                ConsoleMessage.ClipboardUnsupportedCharacters(result.unsupported.size)
            is PacedCommittedTextResult.ConnectionLost ->
                ConsoleMessage.ClipboardInputConnectionLost(result.sentKeystrokes, total)
        }
    }

    private fun updatePacedPasteProgress(
        operation: ActivePasteOperation,
        progress: PacedCommittedTextProgress,
    ) {
        synchronized(stateLock) {
            if (activePaste !== operation) return
            val snapshot = pasteOperations.progress(
                token = operation.token,
                sentKeystrokes = progress.sentKeystrokes,
                totalKeystrokes = progress.totalKeystrokes,
            ) ?: return
            mutableSession.update { current ->
                if (current.pasteProgress?.operationToken != operation.token) current else {
                    current.copy(pasteProgress = snapshot.toRemoteProgress())
                }
            }
        }
    }

    private fun completePasteOperation(operation: ActivePasteOperation) {
        synchronized(stateLock) {
            if (activePaste !== operation) return
            val final = pasteOperations.finish(operation.token) ?: return
            activePaste = null
            mutableSession.update { current ->
                if (current.pasteProgress?.operationToken != operation.token) {
                    current
                } else {
                    when {
                        final.userInitiatedCancellation -> current.copy(
                            pasteProgress = null,
                        ).withActionFeedback(
                            ConsoleMessage.ClipboardTypingCancelled(
                                final.sentKeystrokes,
                                final.totalKeystrokes,
                            ),
                        )
                        final.phase == RemotePastePhase.Cancelling -> current.copy(
                            pasteProgress = null,
                            lastActionFeedback = operation.previousActionFeedback,
                        )
                        operation.completionMessage != null -> current.copy(
                            pasteProgress = null,
                        ).withActionFeedback(checkNotNull(operation.completionMessage))
                        else -> current.copy(
                            pasteProgress = null,
                            lastActionFeedback = operation.previousActionFeedback,
                        )
                    }
                }
            }
        }
    }

    private fun requestPasteCancellation(userInitiated: Boolean): Job? {
        val job = synchronized(stateLock) {
            markPasteCancellingLocked(userInitiated)
        }
        job?.cancel(CancellationException("Clipboard typing cancelled"))
        return job
    }

    private fun markPasteCancellingLocked(userInitiated: Boolean): Job? {
        check(Thread.holdsLock(stateLock))
        val operation = activePaste ?: return null
        val snapshot = pasteOperations.cancel(operation.token, userInitiated) ?: return null
        mutableSession.update { current ->
            if (current.pasteProgress?.operationToken != operation.token) current else {
                current.copy(pasteProgress = snapshot.toRemoteProgress())
            }
        }
        return operation.job
    }

    private suspend fun cancelPasteAndJoin(userInitiated: Boolean) {
        requestPasteCancellation(userInitiated)?.join()
    }

    private fun queueKeyboardCommandAfterPaste(
        onEpochRejected: (() -> Unit)? = null,
        block: suspend () -> Unit,
    ): Job? {
        var cancelledPaste: Job? = null
        val queued = synchronized(stateLock) {
            if (closed || !foreground || !acceptingCommands) return@synchronized null
            cancelledPaste = markPasteCancellingLocked(userInitiated = false)
            val epoch = commandAcceptanceEpoch
            val previous = queuedKeyboardTail
            val created = scope.launch(start = CoroutineStart.LAZY) {
                cancelledPaste?.join()
                previous?.join()
                pasteExecutionMutex.withLock {
                    if (isCommandEpochCurrent(epoch)) {
                        block()
                    } else {
                        onEpochRejected?.invoke()
                    }
                }
            }
            queuedKeyboardTail = created
            created.invokeOnCompletion {
                synchronized(stateLock) {
                    if (queuedKeyboardTail === created) queuedKeyboardTail = null
                }
            }
            created
        }
        cancelledPaste?.cancel(CancellationException("Clipboard typing cancelled"))
        queued?.start()
        return queued
    }

    private fun releaseAllInputNow(socket: NanoKvmInputSocket? = synchronized(stateLock) { input }) {
        val keyboardRelease: HidKeyboardReport
        val absolute: Boolean
        val x: Int
        val y: Int
        synchronized(stateLock) {
            keyboardRelease = keyboardState.releaseAll()
            pressedMouseButtons.clear()
            absolute = usedAbsoluteMouse
            x = lastAbsoluteX
            y = lastAbsoluteY
        }
        socket?.sendKeyboard(keyboardRelease)
        socket?.sendMouse(RelativeMouseReport.create())
        if (absolute) socket?.sendMouse(AbsoluteMouseReport.create(x = x, y = y))
        mutableSession.update {
            it.copy(inputReleaseGeneration = it.inputReleaseGeneration + 1)
        }
    }

    private fun closeCommandAcceptanceBarrier(
        expectedBinding: NanoKvmSessionBinding? = null,
    ): Boolean {
        val claimed = synchronized(stateLock) {
            if (
                expectedBinding != null &&
                !matchesAuthenticatedSessionBindingLocked(expectedBinding)
            ) {
                return@synchronized false
            }
            acceptingCommands = false
            commandAcceptanceEpoch++
            true
        }
        if (!claimed) return false
        if (expectedBinding == null) invalidateSessionFeatureSet()
        requestPasteCancellation(userInitiated = false)
        return true
    }

    private fun publishTerminalFailureWhenBindingStillCurrent(
        expectedBinding: NanoKvmSessionBinding,
        failure: ReconnectFailure,
    ) {
        scope.launch {
            lifecycleMutex.withLock {
                val current = synchronized(stateLock) {
                    matchesAuthenticatedSessionBindingLocked(expectedBinding)
                }
                if (!current) return@withLock
                invalidateSessionFeatureSet()
                publishTerminalFailure(failure)
            }
        }
    }

    private fun publishTerminalFailure(failure: ReconnectFailure) {
        controlGate.invalidate()
        mutableSession.update {
            it.copy(
                connection = ConnectionState.Failed,
                status = ConsoleMessage.ConnectionFailed(failure.toConnectionFailure()),
                reconnectAttempt = null,
                reconnectMaximumAttempts = null,
                nextReconnectDelayMillis = null,
            )
        }
    }

    /**
     * Global, exactly-once boundary for a classified authenticated 401.
     *
     * [expectedBinding] is checked against the authenticated-session identity and generation in the
     * same critical section that detaches the session. This deliberately does not depend on the
     * command-acceptance barrier: an input-WebSocket failure closes that barrier before some
     * terminal failure paths are delivered, but the current generation still must forget its token.
     */
    private fun expireAuthenticatedSession(expectedBinding: NanoKvmSessionBinding): Boolean {
        val expiredSession = synchronized(stateLock) {
            val active = authenticatedSession
            val current = mutableSession.value
            if (
                active == null ||
                active.profileId != expectedBinding.profileId ||
                active.authority != expectedBinding.authority ||
                current.sessionGeneration != expectedBinding.sessionGeneration
            ) {
                return false
            }
            acceptingCommands = false
            commandAcceptanceEpoch++
            authenticatedSession = null
            active
        }
        controlGate.invalidate()
        cancelReconnectJob()
        releaseAllInputNow()
        invalidateSessionFeatureSet()
        requestPasteCancellation(userInitiated = false)
        // AuthenticatedNanoKvmSession.close clears the in-memory token before returning.
        expiredSession.close()
        val generation = mutableSession.value.sessionGeneration
        mutableSession.value = BackendSession(
            connection = ConnectionState.Failed,
            sessionGeneration = generation,
            status = ConsoleMessage.AuthenticationExpired,
        )
        closeScope.launch {
            lifecycleMutex.withLock {
                cleanupSessionLocked(forgetClient = false)
                mutableSession.update { current ->
                    if (
                        current.sessionGeneration == generation &&
                        current.connection == ConnectionState.Failed
                    ) {
                        current.copy(status = ConsoleMessage.AuthenticationExpired)
                    } else {
                        current
                    }
                }
            }
        }
        return true
    }

    /** Password-change coordinators use the same exactly-once F-04 teardown as every feature. */
    internal fun expirePasswordChangeAuthentication(
        expectedBinding: NanoKvmSessionBinding,
    ): Boolean = expireAuthenticatedSession(expectedBinding)

    private fun openCommandAcceptanceLocked() {
        check(Thread.holdsLock(stateLock))
        commandAcceptanceEpoch++
        acceptingCommands = true
    }

    private fun isCommandEpochCurrent(epoch: Long): Boolean = synchronized(stateLock) {
        !closed && foreground && acceptingCommands && commandAcceptanceEpoch == epoch &&
            mutableSession.value.connection.isSessionUsable
    }

    private fun isCurrentPasteLocked(operation: ActivePasteOperation): Boolean {
        check(Thread.holdsLock(stateLock))
        val current = mutableSession.value
        return activePaste === operation &&
            pasteOperations.snapshot(operation.token) != null &&
            acceptingCommands &&
            commandAcceptanceEpoch == operation.commandEpoch &&
            authenticatedSession === operation.activeSession &&
            input === operation.activeInput &&
            current.connection.isSessionUsable &&
            operation.request.matchesDestination(
                profileId = operation.activeSession.profileId,
                authority = operation.activeSession.authority,
                sessionGeneration = current.sessionGeneration,
            )
    }

    private fun runControlAfterPaste(
        key: String,
        successMessage: ConsoleMessage.ActionFeedback,
        expectedBinding: NanoKvmSessionBinding? = null,
        block: suspend (AuthenticatedNanoKvmSession) -> Unit,
    ) {
        val lease = controlGate.claim(key) ?: return
        val queued = queueKeyboardCommandAfterPaste(
            onEpochRejected = if (expectedBinding == null) {
                null
            } else {
                ::publishCoreDestinationChanged
            },
        ) {
            lifecycleMutex.withLock {
                val activeSession = authenticatedSession ?: return@withLock
                if (!mutableSession.value.connection.isSessionUsable) return@withLock
                if (
                    expectedBinding != null && synchronized(stateLock) {
                        currentSessionBindingLocked() != expectedBinding
                    }
                ) {
                    publishCoreDestinationChanged()
                    return@withLock
                }
                try {
                    var destinationChanged = false
                    val executed = controlGate.executeIfCurrent(lease) {
                        val destinationStillMatches = synchronized(stateLock) {
                            authenticatedSession === activeSession &&
                                (expectedBinding == null ||
                                    currentSessionBindingLocked() == expectedBinding)
                        }
                        if (destinationStillMatches) {
                            block(activeSession)
                        } else {
                            destinationChanged = expectedBinding != null
                        }
                    }
                    if (destinationChanged) {
                        publishCoreDestinationChanged()
                        return@withLock
                    }
                    if (executed && authenticatedSession === activeSession) mutableSession.update {
                        if (it.connection.isSessionUsable) {
                            it.withActionFeedback(successMessage)
                        } else {
                            it
                        }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error is AuthenticationExpiredException) {
                        val binding = synchronized(stateLock) {
                            if (authenticatedSession !== activeSession) return@synchronized null
                            NanoKvmSessionBinding(
                                activeSession.profileId,
                                activeSession.authority,
                                mutableSession.value.sessionGeneration,
                            )
                        }
                        binding?.let(::expireAuthenticatedSession)
                        return@withLock
                    }
                    if (authenticatedSession === activeSession) mutableSession.update {
                        if (it.connection.isSessionUsable) {
                            it.withActionFeedback(
                                ConsoleMessage.CommandFailed(
                                    error.toConnectionFailure(),
                                ),
                            )
                        } else {
                            it
                        }
                    }
                }
            }
        }
        if (queued == null) {
            controlGate.release(lease)
            if (expectedBinding != null) publishCoreDestinationChanged()
        } else {
            // The command can be rejected by a newer lifecycle epoch before [block] runs, or the
            // owning scope can be cancelled. Completion is therefore the only reliable release
            // point for the control lease.
            queued.invokeOnCompletion { controlGate.release(lease) }
        }
    }

    private fun publishCoreDestinationChanged() {
        mutableSession.update { current ->
            if (current.connection.isSessionUsable) {
                current.withActionFeedback(ConsoleMessage.HostControlSessionChanged)
            } else {
                current
            }
        }
    }

    private val videoListener = object : NanoKvmVideoListener {
        override fun onStatusChanged(status: NanoKvmVideoStatus) {
            if (closed) return
            when (status) {
                is NanoKvmVideoStatus.Connecting -> {
                    if (status.transport == NanoKvmVideoTransport.MJPEG) {
                        mjpegFrameDetectionCoordinator.onMjpegActivation()
                    } else {
                        mjpegFrameDetectionCoordinator.onMjpegInactive()
                    }
                    updateSessionFromVideo {
                        if (it.connection == ConnectionState.Degraded && it.streamLabel.isFallback) {
                            it
                        } else {
                            it.copy(
                                streamLabel = status.transport.toVideoStreamDescriptor(),
                                status = if (it.connection.isSessionUsable) {
                                    ConsoleMessage.ConnectingVideo(
                                        status.transport.toVideoTransportDescriptor(),
                                    )
                                } else it.status,
                            )
                        }
                    }
                }
                is NanoKvmVideoStatus.Streaming -> {
                    if (status.transport == NanoKvmVideoTransport.MJPEG) {
                        mjpegFrameDetectionCoordinator.onMjpegActivation()
                    } else {
                        mjpegFrameDetectionCoordinator.onMjpegInactive()
                    }
                    updateSessionFromVideo { current ->
                        if (!current.connection.isSessionUsable) return@updateSessionFromVideo current
                        val transport = status.transport.toVideoTransportDescriptor()
                        val remainsOnFallback = current.connection == ConnectionState.Degraded &&
                            current.streamLabel.isFallback &&
                            current.streamLabel.transport == transport
                        if (remainsOnFallback) {
                            current
                        } else {
                            current.copy(
                                connection = ConnectionState.Connected,
                                streamLabel = status.transport.toVideoStreamDescriptor(),
                                status = null,
                            )
                        }
                    }
                }
                is NanoKvmVideoStatus.FallingBack -> {
                    if (status.to == NanoKvmVideoTransport.MJPEG) {
                        mjpegFrameDetectionCoordinator.onMjpegActivation()
                    } else {
                        mjpegFrameDetectionCoordinator.onMjpegInactive()
                    }
                    updateSessionFromVideo { current ->
                        if (!current.connection.isSessionUsable) return@updateSessionFromVideo current
                        current.copy(
                            connection = ConnectionState.Degraded,
                            streamLabel = status.to.toVideoStreamDescriptor(fallback = true),
                            status = ConsoleMessage.VideoFallback(
                                from = status.from.toVideoTransportDescriptor(),
                                to = status.to.toVideoTransportDescriptor(),
                            ),
                        )
                    }
                }
                is NanoKvmVideoStatus.Error -> {
                    mjpegFrameDetectionCoordinator.onMjpegInactive()
                    val shouldReconnect = synchronized(stateLock) {
                        !closed && mutableSession.value.connection.isSessionUsable
                    }
                    if (shouldReconnect) {
                        scheduleReconnect(
                            ReconnectFailure(
                                IOException(
                                    "Video transport failed",
                                    status.cause,
                                ),
                            ),
                            immediateFirstAttempt = false,
                        )
                    }
                }
                NanoKvmVideoStatus.Stopped -> {
                    mjpegFrameDetectionCoordinator.onMjpegInactive()
                }
            }
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            if (closed) return
            if (width > 0 && height > 0) updateSessionFromVideo {
                it.copy(remoteWidth = width, remoteHeight = height)
            }
        }

        override fun onH264FrameRendered(timestampUs: Long) {
            updateFrameRate()
        }

        override fun onWebRtcFrameRendered(timestampNs: Long) {
            updateFrameRate()
        }

        override fun onMjpegBitmapFrame(bitmap: Bitmap): Boolean {
            if (closed) {
                bitmap.recycle()
                return false
            }
            return try {
                val rendered = try {
                    onVideoSizeChanged(bitmap.width, bitmap.height)
                    drawMjpegFrame(bitmap).also { success ->
                        if (success) updateFrameRate()
                    }
                } finally {
                    bitmap.recycle()
                }
                rendered
            } catch (_: Throwable) {
                false
            }
        }

        override fun onFramesDropped(count: Int, reason: H264FrameDropReason) {
            if (closed) return
            updateSessionFromVideo { it.recordDroppedFrames(count) }
        }

        override fun onVideoStalled(transport: NanoKvmVideoTransport) {
            if (closed) return
            updateSessionFromVideo { it.recordVideoStall() }
        }
    }

    private fun drawMjpegFrame(bitmap: Bitmap): Boolean {
        val output = synchronized(stateLock) { surface } ?: return false
        if (!output.isValid) return false
        var canvas: android.graphics.Canvas? = null
        return try {
            canvas = output.lockCanvas(null)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(
                bitmap,
                null,
                Rect(0, 0, canvas.width, canvas.height),
                MJPEG_PAINT,
            )
            val completed = canvas
            canvas = null
            output.unlockCanvasAndPost(completed)
            true
        } catch (_: Throwable) {
            // A Surface can become invalid between the validity check and lockCanvas.
            false
        } finally {
            if (canvas != null) runCatching { output.unlockCanvasAndPost(canvas) }
        }
    }

    private fun updateFrameRate() {
        synchronized(stateLock) {
            if (closed) return
            val now = System.nanoTime()
            if (renderedFrameWindowStartNanos == 0L) renderedFrameWindowStartNanos = now
            renderedFramesInWindow++
            val elapsed = now - renderedFrameWindowStartNanos
            if (elapsed >= 1_000_000_000L) {
                val fps = (renderedFramesInWindow * 1_000_000_000L / elapsed).toInt()
                renderedFrameWindowStartNanos = now
                renderedFramesInWindow = 0
                mutableSession.value = mutableSession.value.copy(framesPerSecond = fps)
            }
        }
    }

    private fun updateSessionFromVideo(update: (BackendSession) -> BackendSession) {
        synchronized(stateLock) {
            if (closed) return
            mutableSession.value = update(mutableSession.value)
        }
    }

    private fun Set<MouseButton>.toProtocolButtons(): Set<org.nanokvm.protocol.MouseButton> = mapTo(linkedSetOf()) {
        when (it) {
            MouseButton.Left -> org.nanokvm.protocol.MouseButton.LEFT
            MouseButton.Right -> org.nanokvm.protocol.MouseButton.RIGHT
            MouseButton.Middle -> org.nanokvm.protocol.MouseButton.MIDDLE
            MouseButton.Back -> org.nanokvm.protocol.MouseButton.BACK
            MouseButton.Forward -> org.nanokvm.protocol.MouseButton.FORWARD
        }
    }

    private fun KeyboardLayout.toProtocolLayout(): ProtocolKeyboardLayout = when (this) {
        KeyboardLayout.Us -> ProtocolKeyboardLayout.US
        KeyboardLayout.Uk -> ProtocolKeyboardLayout.UK
    }

    private fun PasteOperationTracker.Snapshot.toRemoteProgress(): RemotePasteProgress =
        RemotePasteProgress(
            operationToken = token,
            sentKeystrokes = sentKeystrokes,
            totalKeystrokes = totalKeystrokes,
            phase = phase,
        )

    private fun RemoteKey.toModifier(): HidModifier? = when (this) {
        RemoteKey.Control -> HidModifier.LEFT_CONTROL
        RemoteKey.Shift -> HidModifier.LEFT_SHIFT
        RemoteKey.Alt -> HidModifier.LEFT_ALT
        RemoteKey.Super -> HidModifier.LEFT_SUPER
        RemoteKey.RightControl -> HidModifier.RIGHT_CONTROL
        RemoteKey.RightShift -> HidModifier.RIGHT_SHIFT
        RemoteKey.RightAlt -> HidModifier.RIGHT_ALT
        RemoteKey.RightSuper -> HidModifier.RIGHT_SUPER
        else -> null
    }

    private fun RemoteKey.toUsage(): HidUsage? = when (this) {
        RemoteKey.A -> HidUsage.A
        RemoteKey.B -> HidUsage.B
        RemoteKey.C -> HidUsage.C
        RemoteKey.D -> HidUsage.D
        RemoteKey.E -> HidUsage.E
        RemoteKey.F -> HidUsage.F
        RemoteKey.G -> HidUsage.G
        RemoteKey.H -> HidUsage.H
        RemoteKey.I -> HidUsage.I
        RemoteKey.J -> HidUsage.J
        RemoteKey.K -> HidUsage.K
        RemoteKey.L -> HidUsage.L
        RemoteKey.M -> HidUsage.M
        RemoteKey.N -> HidUsage.N
        RemoteKey.O -> HidUsage.O
        RemoteKey.P -> HidUsage.P
        RemoteKey.Q -> HidUsage.Q
        RemoteKey.R -> HidUsage.R
        RemoteKey.S -> HidUsage.S
        RemoteKey.T -> HidUsage.T
        RemoteKey.U -> HidUsage.U
        RemoteKey.V -> HidUsage.V
        RemoteKey.W -> HidUsage.W
        RemoteKey.X -> HidUsage.X
        RemoteKey.Y -> HidUsage.Y
        RemoteKey.Z -> HidUsage.Z
        RemoteKey.Digit1 -> HidUsage.DIGIT_1
        RemoteKey.Digit2 -> HidUsage.DIGIT_2
        RemoteKey.Digit3 -> HidUsage.DIGIT_3
        RemoteKey.Digit4 -> HidUsage.DIGIT_4
        RemoteKey.Digit5 -> HidUsage.DIGIT_5
        RemoteKey.Digit6 -> HidUsage.DIGIT_6
        RemoteKey.Digit7 -> HidUsage.DIGIT_7
        RemoteKey.Digit8 -> HidUsage.DIGIT_8
        RemoteKey.Digit9 -> HidUsage.DIGIT_9
        RemoteKey.Digit0 -> HidUsage.DIGIT_0
        RemoteKey.Escape -> HidUsage.ESCAPE
        RemoteKey.Tab -> HidUsage.TAB
        RemoteKey.Enter -> HidUsage.ENTER
        RemoteKey.Backspace -> HidUsage.BACKSPACE
        RemoteKey.Space -> HidUsage.SPACE
        RemoteKey.Minus -> HidUsage.MINUS
        RemoteKey.Equal -> HidUsage.EQUAL
        RemoteKey.LeftBracket -> HidUsage.LEFT_BRACKET
        RemoteKey.RightBracket -> HidUsage.RIGHT_BRACKET
        RemoteKey.Backslash -> HidUsage.BACKSLASH
        RemoteKey.NonUsHash -> HidUsage.NON_US_HASH
        RemoteKey.Semicolon -> HidUsage.SEMICOLON
        RemoteKey.Apostrophe -> HidUsage.APOSTROPHE
        RemoteKey.Grave -> HidUsage.GRAVE
        RemoteKey.Comma -> HidUsage.COMMA
        RemoteKey.Period -> HidUsage.PERIOD
        RemoteKey.Slash -> HidUsage.SLASH
        RemoteKey.CapsLock -> HidUsage.CAPS_LOCK
        RemoteKey.ArrowUp -> HidUsage.ARROW_UP
        RemoteKey.ArrowDown -> HidUsage.ARROW_DOWN
        RemoteKey.ArrowLeft -> HidUsage.ARROW_LEFT
        RemoteKey.ArrowRight -> HidUsage.ARROW_RIGHT
        RemoteKey.F1 -> HidUsage.F1
        RemoteKey.F2 -> HidUsage.F2
        RemoteKey.F3 -> HidUsage.F3
        RemoteKey.F4 -> HidUsage.F4
        RemoteKey.F5 -> HidUsage.F5
        RemoteKey.F6 -> HidUsage.F6
        RemoteKey.F7 -> HidUsage.F7
        RemoteKey.F8 -> HidUsage.F8
        RemoteKey.F9 -> HidUsage.F9
        RemoteKey.F10 -> HidUsage.F10
        RemoteKey.F11 -> HidUsage.F11
        RemoteKey.F12 -> HidUsage.F12
        RemoteKey.PrintScreen -> HidUsage.PRINT_SCREEN
        RemoteKey.ScrollLock -> HidUsage.SCROLL_LOCK
        RemoteKey.Pause -> HidUsage.PAUSE
        RemoteKey.Insert -> HidUsage.INSERT
        RemoteKey.Home -> HidUsage.HOME
        RemoteKey.PageUp -> HidUsage.PAGE_UP
        RemoteKey.Delete -> HidUsage.DELETE_FORWARD
        RemoteKey.End -> HidUsage.END
        RemoteKey.PageDown -> HidUsage.PAGE_DOWN
        RemoteKey.NumLock -> HidUsage.NUM_LOCK
        RemoteKey.NumpadDivide -> HidUsage.NUMPAD_DIVIDE
        RemoteKey.NumpadMultiply -> HidUsage.NUMPAD_MULTIPLY
        RemoteKey.NumpadSubtract -> HidUsage.NUMPAD_SUBTRACT
        RemoteKey.NumpadAdd -> HidUsage.NUMPAD_ADD
        RemoteKey.NumpadEnter -> HidUsage.NUMPAD_ENTER
        RemoteKey.Numpad1 -> HidUsage.NUMPAD_1
        RemoteKey.Numpad2 -> HidUsage.NUMPAD_2
        RemoteKey.Numpad3 -> HidUsage.NUMPAD_3
        RemoteKey.Numpad4 -> HidUsage.NUMPAD_4
        RemoteKey.Numpad5 -> HidUsage.NUMPAD_5
        RemoteKey.Numpad6 -> HidUsage.NUMPAD_6
        RemoteKey.Numpad7 -> HidUsage.NUMPAD_7
        RemoteKey.Numpad8 -> HidUsage.NUMPAD_8
        RemoteKey.Numpad9 -> HidUsage.NUMPAD_9
        RemoteKey.Numpad0 -> HidUsage.NUMPAD_0
        RemoteKey.NumpadDecimal -> HidUsage.NUMPAD_DECIMAL
        RemoteKey.NumpadComma -> HidUsage.NUMPAD_COMMA
        RemoteKey.NumpadLeftParen -> HidUsage.NUMPAD_LEFT_PAREN
        RemoteKey.NumpadRightParen -> HidUsage.NUMPAD_RIGHT_PAREN
        RemoteKey.NonUsBackslash -> HidUsage.NON_US_BACKSLASH
        RemoteKey.ContextMenu -> HidUsage.CONTEXT_MENU
        RemoteKey.NumpadEqual -> HidUsage.NUMPAD_EQUAL
        RemoteKey.Help -> HidUsage.HELP
        RemoteKey.Cut -> HidUsage.CUT
        RemoteKey.Copy -> HidUsage.COPY
        RemoteKey.Paste -> HidUsage.PASTE
        RemoteKey.VolumeMute -> HidUsage.VOLUME_MUTE
        RemoteKey.VolumeUp -> HidUsage.VOLUME_UP
        RemoteKey.VolumeDown -> HidUsage.VOLUME_DOWN
        RemoteKey.Control, RemoteKey.Shift, RemoteKey.Alt, RemoteKey.Super,
        RemoteKey.RightControl, RemoteKey.RightShift, RemoteKey.RightAlt, RemoteKey.RightSuper -> null
    }

    private fun CertificateInspection.toCertificateDetails(
        reason: CertificatePresentationReason,
    ): CertificateDetails = CertificateDetails(
        sha256 = fingerprint.colonSeparated(),
        subject = subject,
        issuer = issuer,
        subjectAlternativeNames = subjectAlternativeNames.dnsNames + subjectAlternativeNames.ipAddresses,
        validFrom = DateTimeFormatter.ISO_INSTANT.format(validFrom),
        validUntil = DateTimeFormatter.ISO_INSTANT.format(validUntil),
        reason = reason,
        metadataTruncated = metadataTruncated,
    )

    private fun NanoKvmVideoTransport.toVideoStreamDescriptor(
        fallback: Boolean = false,
    ): VideoStreamDescriptor = VideoStreamDescriptor(
        transport = toVideoTransportDescriptor(),
        isFallback = fallback,
    )

    private fun NanoKvmVideoTransport.toVideoTransportDescriptor(): VideoTransportDescriptor =
        when (this) {
            NanoKvmVideoTransport.WEBRTC -> VideoTransportDescriptor.WebRtc
            NanoKvmVideoTransport.H264 -> VideoTransportDescriptor.DirectH264
            NanoKvmVideoTransport.MJPEG -> VideoTransportDescriptor.Mjpeg
        }

    private companion object {
        const val INPUT_CONNECT_TIMEOUT_MS = 15_000L
        const val CONTROL_RESET_HID = "reset-hid"
        const val CONTROL_GPIO = "gpio"
        const val CONTROL_CTRL_ALT_DELETE = "ctrl-alt-delete"
        const val MAX_PASTE_BYTES = 1_024
        const val PHASE3_TRANSFER_POLL_MILLIS = 2_500L
        val MJPEG_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}

/**
 * Records accepted encoder settings without claiming that a degraded video path recovered.
 * Recovery is published only by a subsequent streaming callback for the newly validated transport.
 */
internal fun BackendSession.withAppliedVideoSettings(settings: VideoSettings): BackendSession {
    val unresolvedFallback =
        connection == ConnectionState.Degraded && streamLabel.isFallback
    val adoptsCurrentTransportAsPrimary = unresolvedFallback &&
        settings.transportPreference.explicitTransport() == streamLabel.transport
    return copy(
        videoSettings = settings,
        streamLabel = if (adoptsCurrentTransportAsPrimary) {
            streamLabel.copy(isFallback = false)
        } else {
            streamLabel
        },
        videoSurfaceGeneration = videoSurfaceGeneration + 1,
        framesPerSecond = null,
        status = if (unresolvedFallback) status else null,
    ).withActionFeedback(ConsoleMessage.VideoSettingsApplied)
}

private fun VideoTransportPreference.explicitTransport(): VideoTransportDescriptor? = when (this) {
    VideoTransportPreference.Auto -> null
    VideoTransportPreference.WEBRTC -> VideoTransportDescriptor.WebRtc
    VideoTransportPreference.H264 -> VideoTransportDescriptor.DirectH264
    VideoTransportPreference.MJPEG -> VideoTransportDescriptor.Mjpeg
}

/** Maps transport/protocol failures to a bounded presentation contract without exposing details. */
internal fun Throwable.toConnectionFailure(responseCode: Int? = null): ConnectionFailure {
    responseCode?.let { return ConnectionFailure.RequestRejected(it) }
    findCause<AuthenticationExpiredException>()?.let {
        return ConnectionFailure.RequestRejected(it.statusCode)
    }
    findCause<ApiResponseException>()?.let {
        return ConnectionFailure.RequestRejected(it.code)
    }
    findCause<HttpResponseException>()?.let {
        return ConnectionFailure.RequestRejected(it.statusCode)
    }
    return when {
        findCause<SocketTimeoutException>() != null ||
            findCause<kotlinx.coroutines.TimeoutCancellationException>() != null ->
            ConnectionFailure.TimedOut
        findCause<CertificateException>() != null ->
            ConnectionFailure.CertificateInspectionFailed
        findCause<SSLException>() != null -> ConnectionFailure.ProtocolError
        findCause<NanoKvmException>() != null || findCause<IllegalArgumentException>() != null ->
            ConnectionFailure.ProtocolError
        findCause<IOException>() != null -> ConnectionFailure.Unreachable
        else -> ConnectionFailure.Unexpected
    }
}

private fun ReconnectFailure.toConnectionFailure(): ConnectionFailure =
    cause.toConnectionFailure(httpStatus)

private inline fun <reified T : Throwable> Throwable.findCause(): T? =
    generateSequence(this) { it.cause }.filterIsInstance<T>().firstOrNull()

/**
 * Serializes invalidation with every callback from one concrete video session.
 *
 * A decoder or callback executor may already be inside user code when cancellation/close begins.
 * [invalidate] waits for that callback and prevents every queued or cancellation-ignoring callback
 * from entering the backend after a replacement session becomes eligible to publish state.
 */
internal class SessionBoundVideoListener(
    private val delegate: NanoKvmVideoListener,
) : NanoKvmVideoListener {
    private val lock = Any()
    private var active = true

    fun invalidate() {
        synchronized(lock) { active = false }
    }

    override fun onStatusChanged(status: NanoKvmVideoStatus) = ifActive {
        delegate.onStatusChanged(status)
    }

    override fun onVideoSizeChanged(width: Int, height: Int) = ifActive {
        delegate.onVideoSizeChanged(width, height)
    }

    override fun onWebRtcFrameRendered(timestampNs: Long) = ifActive {
        delegate.onWebRtcFrameRendered(timestampNs)
    }

    override fun onH264FrameRendered(timestampUs: Long) = ifActive {
        delegate.onH264FrameRendered(timestampUs)
    }

    override fun onMjpegJpegFrame(jpeg: ByteArray) = ifActive {
        delegate.onMjpegJpegFrame(jpeg)
    }

    override fun onMjpegBitmapFrame(bitmap: Bitmap): Boolean = synchronized(lock) {
        if (active) delegate.onMjpegBitmapFrame(bitmap) else false
    }

    override fun onFramesDropped(count: Int, reason: H264FrameDropReason) = ifActive {
        delegate.onFramesDropped(count, reason)
    }

    override fun onVideoStalled(transport: NanoKvmVideoTransport) = ifActive {
        delegate.onVideoStalled(transport)
    }

    private inline fun ifActive(block: () -> Unit) {
        synchronized(lock) {
            if (active) block()
        }
    }
}

internal fun NanoKvmPhase3MutationResult<*>.requiresInputRecycleAfterUsbMutation(): Boolean =
    when (this) {
        is NanoKvmPhase3MutationResult.Applied,
        is NanoKvmPhase3MutationResult.Accepted,
        is NanoKvmPhase3MutationResult.Reconciled,
        is NanoKvmPhase3MutationResult.Indeterminate -> true
        is NanoKvmPhase3MutationResult.AlreadySatisfied,
        is NanoKvmPhase3MutationResult.Rejected -> false
    }

private fun Phase3HidModeSelection.toGatewaySelection(): NanoKvmHidModeSelection = when (this) {
    Phase3HidModeSelection.Normal -> NanoKvmHidModeSelection.NORMAL
    Phase3HidModeSelection.HidOnly -> NanoKvmHidModeSelection.HID_ONLY
    Phase3HidModeSelection.Other -> error("Unknown HID mode is read-only")
}

private enum class AdministrationReconnectMode { Immediate, WaitForService }

private enum class Phase3NoticeScope { General, VirtualMedia, WakeOnLan }

private fun OperatorSerialConfiguration.toProtocolConfiguration() = NanoKvmSerialConfiguration(
    port = when (port) {
        OperatorSerialPort.TtyS1 -> NanoKvmSerialPort.TTY_S1
        OperatorSerialPort.TtyS2 -> NanoKvmSerialPort.TTY_S2
    },
    baud = NanoKvmSerialBaud.valueOf(baud.name),
    parity = when (parity) {
        OperatorSerialParity.None -> NanoKvmSerialParity.NONE
        OperatorSerialParity.Even -> NanoKvmSerialParity.EVEN
        OperatorSerialParity.Odd -> NanoKvmSerialParity.ODD
    },
    flowControl = when (flowControl) {
        OperatorSerialFlowControl.None -> NanoKvmSerialFlowControl.NONE
        OperatorSerialFlowControl.Software -> NanoKvmSerialFlowControl.SOFTWARE
        OperatorSerialFlowControl.Hardware -> NanoKvmSerialFlowControl.HARDWARE
    },
    dataBits = when (dataBits) {
        OperatorSerialDataBits.Seven -> NanoKvmSerialDataBits.SEVEN
        OperatorSerialDataBits.Eight -> NanoKvmSerialDataBits.EIGHT
    },
    stopBits = when (stopBits) {
        OperatorSerialStopBits.One -> NanoKvmSerialStopBits.ONE
        OperatorSerialStopBits.Two -> NanoKvmSerialStopBits.TWO
    },
)

private fun AdministrationOledPreset.toProtocolPreset(): NanoKvmOledSleepPreset = when (this) {
    AdministrationOledPreset.Never -> NanoKvmOledSleepPreset.NEVER
    AdministrationOledPreset.Seconds15 -> NanoKvmOledSleepPreset.SECONDS_15
    AdministrationOledPreset.Seconds30 -> NanoKvmOledSleepPreset.SECONDS_30
    AdministrationOledPreset.Minute1 -> NanoKvmOledSleepPreset.MINUTE_1
    AdministrationOledPreset.Minutes3 -> NanoKvmOledSleepPreset.MINUTES_3
    AdministrationOledPreset.Minutes5 -> NanoKvmOledSleepPreset.MINUTES_5
    AdministrationOledPreset.Minutes10 -> NanoKvmOledSleepPreset.MINUTES_10
    AdministrationOledPreset.Minutes30 -> NanoKvmOledSleepPreset.MINUTES_30
    AdministrationOledPreset.Hour1 -> NanoKvmOledSleepPreset.HOUR_1
}

private fun NanoKvmPicoClawUiRuntimePhase.toConsolePhase(): PicoClawRuntimeUiPhase = when (this) {
    NanoKvmPicoClawUiRuntimePhase.CHECKING -> PicoClawRuntimeUiPhase.Checking
    NanoKvmPicoClawUiRuntimePhase.INSTALLING -> PicoClawRuntimeUiPhase.Installing
    NanoKvmPicoClawUiRuntimePhase.INSTALLED -> PicoClawRuntimeUiPhase.Installed
    NanoKvmPicoClawUiRuntimePhase.READY -> PicoClawRuntimeUiPhase.Ready
    NanoKvmPicoClawUiRuntimePhase.STOPPED -> PicoClawRuntimeUiPhase.Stopped
    NanoKvmPicoClawUiRuntimePhase.NOT_INSTALLED -> PicoClawRuntimeUiPhase.NotInstalled
    NanoKvmPicoClawUiRuntimePhase.MODEL_NOT_CONFIGURED ->
        PicoClawRuntimeUiPhase.ModelNotConfigured
    NanoKvmPicoClawUiRuntimePhase.CONFIG_ERROR -> PicoClawRuntimeUiPhase.ConfigError
    NanoKvmPicoClawUiRuntimePhase.UNAVAILABLE -> PicoClawRuntimeUiPhase.Unavailable
    NanoKvmPicoClawUiRuntimePhase.ERROR -> PicoClawRuntimeUiPhase.Error
    NanoKvmPicoClawUiRuntimePhase.OTHER -> PicoClawRuntimeUiPhase.Other
}

internal fun appendBoundedPicoClawMessage(
    current: List<PicoClawMessageUiState>,
    incoming: PicoClawMessageUiState,
    maximumMessages: Int = 64,
): List<PicoClawMessageUiState> {
    require(maximumMessages > 0) { "PicoClaw message limit must be positive" }
    return (current + incoming).takeLast(maximumMessages)
}

private fun NanoKvmOledSleepPreset.toUiPreset(): AdministrationOledPreset = when (this) {
    NanoKvmOledSleepPreset.NEVER -> AdministrationOledPreset.Never
    NanoKvmOledSleepPreset.SECONDS_15 -> AdministrationOledPreset.Seconds15
    NanoKvmOledSleepPreset.SECONDS_30 -> AdministrationOledPreset.Seconds30
    NanoKvmOledSleepPreset.MINUTE_1 -> AdministrationOledPreset.Minute1
    NanoKvmOledSleepPreset.MINUTES_3 -> AdministrationOledPreset.Minutes3
    NanoKvmOledSleepPreset.MINUTES_5 -> AdministrationOledPreset.Minutes5
    NanoKvmOledSleepPreset.MINUTES_10 -> AdministrationOledPreset.Minutes10
    NanoKvmOledSleepPreset.MINUTES_30 -> AdministrationOledPreset.Minutes30
    NanoKvmOledSleepPreset.HOUR_1 -> AdministrationOledPreset.Hour1
}

private fun NanoKvmAdministrationAccountSnapshot.toUiState() = AdministrationAccountUiState(
    username = username,
    passwordUpdated = passwordUpdated,
)

private fun NanoKvmAdministrationUpdateSnapshot.toUiState() = AdministrationUpdateUiState(
    currentVersion = currentVersion,
    latestVersion = latestVersion,
    previewUpdatesEnabled = previewUpdatesEnabled,
)

private fun NanoKvmAdministrationOledSnapshot.toUiState() = AdministrationOledUiState(
    exists = exists,
    sleepSeconds = sleepSeconds,
    preset = sleepPreset?.toUiPreset(),
)

private fun NanoKvmAdministrationDnsSnapshot.toUiState() = AdministrationDnsUiState(
    mode = when (selection) {
        NanoKvmAdministrationDnsSelection.DHCP -> AdministrationDnsMode.Dhcp
        NanoKvmAdministrationDnsSelection.MANUAL -> AdministrationDnsMode.Manual
        NanoKvmAdministrationDnsSelection.OTHER -> AdministrationDnsMode.Other
    },
    configuredServers = configuredServers,
    effectiveServers = effectiveServers,
    dhcpServers = dhcpServers,
)

private fun NanoKvmAdministrationWifiSnapshot.toUiState() = AdministrationWifiUiState(
    supported = supported,
    accessPointMode = accessPointMode,
    connected = connected,
    ssid = ssid,
)

private fun NanoKvmAdministrationTailscaleSnapshot.toUiState() =
    AdministrationTailscaleUiState(
        selection = when (selection) {
            NanoKvmAdministrationTailscaleSelection.NOT_INSTALLED ->
                AdministrationTailscaleSelection.NotInstalled
            NanoKvmAdministrationTailscaleSelection.NOT_RUNNING ->
                AdministrationTailscaleSelection.NotRunning
            NanoKvmAdministrationTailscaleSelection.NOT_LOGGED_IN ->
                AdministrationTailscaleSelection.NotLoggedIn
            NanoKvmAdministrationTailscaleSelection.STOPPED ->
                AdministrationTailscaleSelection.Stopped
            NanoKvmAdministrationTailscaleSelection.RUNNING ->
                AdministrationTailscaleSelection.Running
            NanoKvmAdministrationTailscaleSelection.OTHER ->
                AdministrationTailscaleSelection.Other
        },
        reportedState = reportedState,
        deviceName = deviceName,
        ipv4 = ipv4,
        account = account,
    )

private fun AdministrationTailscaleCommand.toProtocolCommand(): NanoKvmTailscaleCommand =
    when (this) {
        AdministrationTailscaleCommand.Install -> NanoKvmTailscaleCommand.INSTALL
        AdministrationTailscaleCommand.Uninstall -> NanoKvmTailscaleCommand.UNINSTALL
        AdministrationTailscaleCommand.Start -> NanoKvmTailscaleCommand.START
        AdministrationTailscaleCommand.Stop -> NanoKvmTailscaleCommand.STOP
        AdministrationTailscaleCommand.Restart -> NanoKvmTailscaleCommand.RESTART
        AdministrationTailscaleCommand.Up -> NanoKvmTailscaleCommand.UP
        AdministrationTailscaleCommand.Down -> NanoKvmTailscaleCommand.DOWN
        AdministrationTailscaleCommand.Login -> NanoKvmTailscaleCommand.LOGIN
        AdministrationTailscaleCommand.Logout -> NanoKvmTailscaleCommand.LOGOUT
    }

private fun NanoKvmMouseJigglerState.toUiState() = AdministrationMouseJigglerUiState(
    selection = when {
        mode is NanoKvmMouseJigglerMode.Other -> AdministrationMouseJigglerSelection.Other
        !enabled -> AdministrationMouseJigglerSelection.Off
        mode == NanoKvmMouseJigglerMode.Relative ->
            AdministrationMouseJigglerSelection.Relative
        else -> AdministrationMouseJigglerSelection.Absolute
    },
    reportedMode = (mode as? NanoKvmMouseJigglerMode.Other)?.wireValue,
)

private fun NanoKvmMemoryLimitState.toUiState() = AdministrationMemoryLimitUiState(
    enabled = enabled,
    limitMegabytes = limitMegabytes,
    writable = preset != null || (!enabled && limitMegabytes == 0L),
)

private fun NanoKvmSwapState.toUiState() = AdministrationSwapUiState(
    sizeMegabytes = sizeMegabytes,
    preset = preset?.toUiPreset(),
)

private fun NanoKvmSwapSizePreset.toUiPreset(): AdministrationSwapPreset = when (this) {
    NanoKvmSwapSizePreset.DISABLED -> AdministrationSwapPreset.Disabled
    NanoKvmSwapSizePreset.MB_64 -> AdministrationSwapPreset.Mb64
    NanoKvmSwapSizePreset.MB_128 -> AdministrationSwapPreset.Mb128
    NanoKvmSwapSizePreset.MB_256 -> AdministrationSwapPreset.Mb256
    NanoKvmSwapSizePreset.MB_512 -> AdministrationSwapPreset.Mb512
}

private fun AdministrationSwapPreset.toProtocolPreset(): NanoKvmSwapSizePreset = when (this) {
    AdministrationSwapPreset.Disabled -> NanoKvmSwapSizePreset.DISABLED
    AdministrationSwapPreset.Mb64 -> NanoKvmSwapSizePreset.MB_64
    AdministrationSwapPreset.Mb128 -> NanoKvmSwapSizePreset.MB_128
    AdministrationSwapPreset.Mb256 -> NanoKvmSwapSizePreset.MB_256
    AdministrationSwapPreset.Mb512 -> NanoKvmSwapSizePreset.MB_512
}

private data class ReboundPhase3Session(
    val binding: NanoKvmSessionBinding,
    val gateway: NanoKvmPhase3FeatureGateway,
)

private data class OfflineUpdateActivation(
    val gateway: NanoKvmOfflineUpdateGateway,
    val foreground: Boolean,
    val surfaceVisible: Boolean,
)

private fun ReconnectFailure.isAuthenticationExpiry(): Boolean =
    httpStatus == 401 || cause is AuthenticationExpiredException

private class ActivePasteOperation(
    val token: Long,
    val request: ApprovedPasteRequest,
    val activeSession: AuthenticatedNanoKvmSession,
    val activeInput: NanoKvmInputSocket,
    val commandEpoch: Long,
    val previousActionFeedback: SequencedConsoleActionFeedback?,
    val priorKeyboardCommand: Job?,
) {
    lateinit var job: Job

    @Volatile
    var completionMessage: ConsoleMessage.ActionFeedback? = null
}

private suspend fun CompletableFuture<Unit>.awaitCompletion() {
    suspendCancellableCoroutine { continuation ->
        whenComplete { _, error ->
            if (continuation.isActive) {
                continuation.resumeWith(
                    if (error == null) Result.success(Unit) else Result.failure(error),
                )
            }
        }
    }
}

internal fun VideoSettings.toNanoKvmVideoConfig(): NanoKvmVideoConfig {
    val (width, height) = when (resolutionHeight) {
        0 -> 1920 to 1080
        480 -> 640 to 480
        600 -> 800 to 600
        720 -> 1280 to 720
        1080 -> 1920 to 1080
        else -> error("Unsupported video height: $resolutionHeight")
    }
    val preference = when (transportPreference) {
        VideoTransportPreference.Auto -> NanoKvmVideoPreference.AUTO
        VideoTransportPreference.WEBRTC -> NanoKvmVideoPreference.WEBRTC
        VideoTransportPreference.H264 -> NanoKvmVideoPreference.H264
        VideoTransportPreference.MJPEG -> NanoKvmVideoPreference.MJPEG
    }
    return NanoKvmVideoConfig(
        preference = preference,
        decoder = H264DecoderConfig(expectedWidth = width, expectedHeight = height),
    )
}

internal fun isSupportedNanoKvmApplication(value: String): Boolean {
    val actual = NanoKvmApplicationVersion.parse(value) ?: return false
    return actual >= MINIMUM_NANO_KVM_VERSION
}

private val MINIMUM_NANO_KVM_VERSION = checkNotNull(NanoKvmApplicationVersion.parse("2.3.2"))
private val MINIMUM_PICOCLAW_VERSION = checkNotNull(NanoKvmApplicationVersion.parse("2.4.0"))

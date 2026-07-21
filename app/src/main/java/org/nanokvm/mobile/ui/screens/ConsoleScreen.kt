package org.nanokvm.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nanokvm.mobile.R
import org.nanokvm.mobile.clipboard.ClipboardGateway
import org.nanokvm.mobile.clipboard.ClipboardPayload
import org.nanokvm.mobile.clipboard.ClipboardReadResult
import org.nanokvm.mobile.clipboard.ClipboardRejectionReason
import org.nanokvm.mobile.clipboard.ClipboardTextWarning
import org.nanokvm.mobile.clipboard.PasteConfirmationRequest
import org.nanokvm.mobile.clipboard.PasteTargetBinding
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.data.DEFAULT_SCROLL_SENSITIVITY
import org.nanokvm.mobile.data.MAX_SCROLL_SENSITIVITY
import org.nanokvm.mobile.data.MIN_SCROLL_SENSITIVITY
import org.nanokvm.mobile.data.normalizeScrollSensitivity
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.ApprovedAdministrationDestination
import org.nanokvm.mobile.runtime.ApprovedCoreDestination
import org.nanokvm.mobile.runtime.ApprovedOperatorDestination
import org.nanokvm.mobile.runtime.ApprovedPasteRequest
import org.nanokvm.mobile.runtime.ApprovedPhase3Destination
import org.nanokvm.mobile.runtime.ApprovedPicoClawDestination
import org.nanokvm.mobile.runtime.ConnectionState
import org.nanokvm.mobile.runtime.isSessionUsable
import org.nanokvm.mobile.runtime.ConsoleFeatureBundle
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateGateway
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdatePhase
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateSource
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateUiState
import org.nanokvm.mobile.platform.NanoKvmOfflineUpdateDocumentSelectionResult
import org.nanokvm.mobile.platform.NanoKvmOfflineUpdateDocumentSource
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.RemotePastePhase
import org.nanokvm.mobile.runtime.VideoSurfaceSink
import org.nanokvm.mobile.runtime.PowerAction
import org.nanokvm.mobile.runtime.VideoSettings
import org.nanokvm.mobile.runtime.VideoTransportPreference
import org.nanokvm.mobile.ui.displayText
import org.nanokvm.mobile.ui.components.ConsoleKeyboard
import org.nanokvm.mobile.ui.components.ImmersiveModeEffect
import org.nanokvm.mobile.ui.components.LocalEscapeKeyInterceptor
import org.nanokvm.mobile.ui.components.PointerMode
import org.nanokvm.mobile.ui.components.RemoteViewport
import org.nanokvm.mobile.ui.components.ViewportAction
import org.nanokvm.mobile.ui.components.ViewportCommand
import org.nanokvm.mobile.ui.input.PointerCaptureController
import org.nanokvm.mobile.ui.input.PointerCaptureReleaseReason
import org.nanokvm.mobile.ui.input.PointerCaptureState
import org.nanokvm.mobile.ui.theme.ConsoleMaterialTheme
import org.nanokvm.mobile.ui.theme.LocalConsoleColorScheme
import org.nanokvm.protocol.NanoKvmCapability
import org.nanokvm.protocol.NanoKvmCapabilitySupport

private sealed interface ConsoleOverlay {
    data object None : ConsoleOverlay
    data object VideoSettings : ConsoleOverlay
    data object ScrollSettings : ConsoleOverlay
    data object PowerMenu : ConsoleOverlay
    data object MoreMenu : ConsoleOverlay
    data object DeviceInfo : ConsoleOverlay
    data object VirtualMedia : ConsoleOverlay
    data object WakeOnLan : ConsoleOverlay
    data object Administration : ConsoleOverlay
    data object OfflineUpdate : ConsoleOverlay
    data object Automation : ConsoleOverlay
    data object OperatorTools : ConsoleOverlay
    data object PicoClaw : ConsoleOverlay
}

private val consoleOverlaySaver = Saver<ConsoleOverlay, Int>(
    save = { overlay -> overlay.saveableCode },
    restore = { code -> code.toConsoleOverlay() },
)

private val pointerModeSaver = Saver<PointerMode, String>(
    save = { mode ->
        if (mode == PointerMode.Captured) PointerMode.Trackpad.name else mode.name
    },
    restore = { saved ->
        PointerMode.entries.firstOrNull { it.name == saved }
            ?.takeUnless { it == PointerMode.Captured }
            ?: PointerMode.Direct
    },
)

private val ConsoleOverlay.saveableCode: Int
    get() = when (this) {
        ConsoleOverlay.None -> 0
        ConsoleOverlay.VideoSettings -> 1
        ConsoleOverlay.ScrollSettings -> 2
        ConsoleOverlay.PowerMenu -> 3
        ConsoleOverlay.MoreMenu -> 4
        ConsoleOverlay.DeviceInfo -> 5
        ConsoleOverlay.VirtualMedia -> 6
        ConsoleOverlay.WakeOnLan -> 7
        ConsoleOverlay.Administration -> 8
        ConsoleOverlay.OfflineUpdate -> 9
        ConsoleOverlay.Automation -> 10
        ConsoleOverlay.OperatorTools -> 11
        ConsoleOverlay.PicoClaw -> 12
    }

private fun Int.toConsoleOverlay(): ConsoleOverlay = when (this) {
    1 -> ConsoleOverlay.VideoSettings
    2 -> ConsoleOverlay.ScrollSettings
    3 -> ConsoleOverlay.PowerMenu
    4 -> ConsoleOverlay.MoreMenu
    5 -> ConsoleOverlay.DeviceInfo
    6 -> ConsoleOverlay.VirtualMedia
    7 -> ConsoleOverlay.WakeOnLan
    8 -> ConsoleOverlay.Administration
    9 -> ConsoleOverlay.OfflineUpdate
    10 -> ConsoleOverlay.Automation
    11 -> ConsoleOverlay.OperatorTools
    12 -> ConsoleOverlay.PicoClaw
    else -> ConsoleOverlay.None
}

private fun ConsoleOverlay.dismissSessionBoundOverlay(): ConsoleOverlay = when (this) {
    ConsoleOverlay.VirtualMedia,
    ConsoleOverlay.WakeOnLan,
    ConsoleOverlay.DeviceInfo,
    ConsoleOverlay.Administration,
    ConsoleOverlay.OperatorTools,
    ConsoleOverlay.PicoClaw,
    ConsoleOverlay.Automation,
    -> ConsoleOverlay.None
    else -> this
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun ConsoleScreen(
    profile: HostProfile,
    session: BackendSession,
    input: RemoteInputSink,
    videoSurface: VideoSurfaceSink,
    features: ConsoleFeatureBundle,
    onDisconnect: () -> Unit,
    onReauthenticate: () -> Unit = onDisconnect,
    sessionDraftOwner: ConsoleSessionDraftOwner,
    clipboardGateway: ClipboardGateway,
    pendingSharedPaste: ClipboardPayload? = null,
    onSharedPasteConsumed: (ClipboardPayload) -> Unit,
    sensitiveWorkGeneration: Long = 0,
    scrollSensitivity: Float = DEFAULT_SCROLL_SENSITIVITY,
    onScrollSensitivityChange: (Float) -> Unit,
    mjpegFrameDetectionEnabled: Boolean = false,
    onMjpegFrameDetectionEnabledChange: (Boolean) -> Unit,
    passwordChangeInProgress: Boolean = false,
    canProtectPassword: Boolean = false,
    onPasswordChange: (
        destination: ApprovedAdministrationDestination,
        username: String,
        password: CharArray,
        saveProtectedCredential: Boolean,
    ) -> Unit,
) {
    val drafts = sessionDraftOwner
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val coreControls = features.core
    val phase3Controls = features.phase3
    val administrationControls = features.administration
    val operatorControls = features.operator
    val picoClawControls = features.picoClaw
    val operatorState = operatorControls?.operatorState?.collectAsStateWithLifecycle()
    val picoClawState = picoClawControls?.picoClawState?.collectAsStateWithLifecycle()
    val automationOwner = features.automation
    val offlineUpdateOwner = features.offlineUpdate
    var pointerMode by rememberSaveable(stateSaver = pointerModeSaver) {
        mutableStateOf(PointerMode.Direct)
    }
    val pointerCaptureController = remember { PointerCaptureController() }
    val pointerCaptureState = pointerCaptureController.state
    var keyboardVisible by rememberSaveable { mutableStateOf(false) }
    var keyboardLayout by rememberSaveable { mutableStateOf(KeyboardLayout.Us) }
    var viewNavigationVisible by rememberSaveable { mutableStateOf(true) }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var fitRequest by rememberSaveable(
        profile.id,
        profile.authority,
        session.sessionGeneration,
    ) { mutableIntStateOf(0) }
    var viewportCommandSequence by remember { mutableIntStateOf(0) }
    var viewportCommand by remember { mutableStateOf<ViewportCommand?>(null) }
    var overlay by rememberSaveable(stateSaver = consoleOverlaySaver) {
        mutableStateOf<ConsoleOverlay>(ConsoleOverlay.None)
    }
    val offlineDocumentHandoff = remember { OfflineUpdateDocumentHandoff() }
    var offlineDocumentSequence by remember { mutableLongStateOf(0L) }
    var offlineDocumentPending by remember { mutableStateOf(false) }
    var offlinePickerTarget by remember { mutableStateOf<OfflineUpdatePickerTarget?>(null) }
    var offlineResultSensitiveGeneration by remember { mutableLongStateOf(-1L) }
    val currentSensitiveWorkGeneration by rememberUpdatedState(sensitiveWorkGeneration)
    val currentOfflinePickerTarget by rememberUpdatedState(
        OfflineUpdatePickerTarget(profile.id, profile.authority),
    )
    val context = LocalContext.current
    val offlineDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = offlinePickerTarget
        offlinePickerTarget = null
        if (uri == null || target != currentOfflinePickerTarget) {
            offlineDocumentHandoff.clear()
            offlineDocumentPending = false
            if (overlay == ConsoleOverlay.OfflineUpdate) overlay = ConsoleOverlay.None
            return@rememberLauncherForActivityResult
        }
        val selection = NanoKvmOfflineUpdateDocumentSource.select(
            contentResolver = context.contentResolver,
            uri = uri,
        )
        offlineDocumentHandoff.replace(selection)
        offlineDocumentPending = true
        offlineResultSensitiveGeneration = currentSensitiveWorkGeneration
        offlineDocumentSequence++
        offlineUpdateOwner?.setOfflineUpdateSurfaceVisible(true)
        overlay = if (offlineUpdateOwner != null) {
            ConsoleOverlay.OfflineUpdate
        } else {
            ConsoleOverlay.None
        }
        if (offlineUpdateOwner == null) {
            offlineDocumentHandoff.clear()
            offlineDocumentPending = false
        }
    }
    val closeOfflineUpdate = {
        offlinePickerTarget = null
        offlineDocumentHandoff.clear()
        offlineDocumentPending = false
        if (overlay == ConsoleOverlay.OfflineUpdate) overlay = ConsoleOverlay.None
        offlineUpdateOwner?.setOfflineUpdateSurfaceVisible(false)
        Unit
    }
    val chooseOfflineUpdateDocument = {
        offlinePickerTarget = currentOfflinePickerTarget
        try {
            offlineDocumentLauncher.launch(OFFLINE_UPDATE_MIME_TYPES)
        } catch (_: RuntimeException) {
            offlinePickerTarget = null
            offlineUpdateOwner?.currentOfflineUpdateGateway()?.rejectDocumentSelection()
        }
        Unit
    }
    var immersiveMode by rememberSaveable { mutableStateOf(false) }
    var immersiveEscapeInProgress by remember { mutableStateOf(false) }
    val consoleFocusRequester = remember { FocusRequester() }
    val immersiveEscape = remember { LocalEscapeKeyInterceptor() }
    var pendingPhase3Action by remember { mutableStateOf<PendingPhase3Action?>(null) }
    var pasteRequest by remember { mutableStateOf<PendingClipboardPaste?>(null) }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var revealSensitivePaste by remember { mutableStateOf(false) }
    var observedSensitiveWorkGeneration by remember {
        mutableLongStateOf(sensitiveWorkGeneration)
    }
    // A destructive confirmation is deliberately ephemeral. It must not be restored into a new
    // Activity instance or survive a connection-generation transition where its destination could
    // otherwise silently change underneath the dialog.
    var pendingPowerAction by remember { mutableStateOf<PendingPowerAction?>(null) }
    var disconnectConfirmationVisible by remember { mutableStateOf(false) }
    val pasteUnavailableMessage = stringResource(R.string.console_clipboard_unavailable)
    val pasteEmptyMessage = stringResource(R.string.console_clipboard_empty)
    val pasteRejectedMessage = stringResource(R.string.console_clipboard_rejected)
    val pasteTooLargeMessage = stringResource(R.string.console_clipboard_too_long)
    val pasteDisconnectedMessage = stringResource(R.string.console_clipboard_connect_first)
    val pasteSessionChangedMessage = stringResource(R.string.console_clipboard_session_changed)
    val currentPasteTarget = PasteTargetBinding(
        profileId = profile.id,
        destinationLabel = profile.name.ifBlank { profile.authority },
        authority = profile.authority,
        sessionGeneration = session.sessionGeneration,
    )
    val currentPhase3Destination = ApprovedPhase3Destination(
        profileId = profile.id,
        authority = profile.authority,
        sessionGeneration = session.sessionGeneration,
    )
    val currentCoreDestination = ApprovedCoreDestination(
        profileId = profile.id,
        authority = profile.authority,
        sessionGeneration = session.sessionGeneration,
    )
    ImmersiveModeEffect(immersiveMode)
    val currentAdministrationDestination = ApprovedAdministrationDestination(
        profileId = profile.id,
        authority = profile.authority,
        sessionGeneration = session.sessionGeneration,
    )
    LaunchedEffect(currentPhase3Destination) {
        // Every confirmation belongs to the exact generation for which it was opened.
        pendingPhase3Action = null
    }
    val currentOperatorDestination = ApprovedOperatorDestination(
        profileId = profile.id,
        authority = profile.authority,
        sessionGeneration = session.sessionGeneration,
    )
    val currentPicoClawDestination = ApprovedPicoClawDestination(
        profileId = profile.id,
        authority = profile.authority,
        sessionGeneration = session.sessionGeneration,
    )
    val currentDraftSession = ConsoleDraftSession(
        profileId = profile.id,
        authority = profile.authority,
        sessionGeneration = session.sessionGeneration,
    )
    val phase3SurfaceVisible = overlay == ConsoleOverlay.VirtualMedia ||
        overlay == ConsoleOverlay.WakeOnLan
    val administrationSurfaceVisible = overlay == ConsoleOverlay.Administration
    val offlineUpdateSurfaceVisible = overlay == ConsoleOverlay.OfflineUpdate
    val operatorSurfaceVisible = overlay == ConsoleOverlay.OperatorTools
    val automationSurfaceVisible = overlay == ConsoleOverlay.Automation
    val picoClawSurfaceVisible = overlay == ConsoleOverlay.PicoClaw
    DisposableEffect(phase3SurfaceVisible, phase3Controls) {
        if (phase3SurfaceVisible) phase3Controls?.setPhase3SurfaceVisible(true)
        onDispose {
            if (phase3SurfaceVisible) phase3Controls?.setPhase3SurfaceVisible(false)
        }
    }
    DisposableEffect(administrationSurfaceVisible, administrationControls) {
        if (administrationSurfaceVisible) {
            administrationControls?.setAdministrationSurfaceVisible(true)
        }
        onDispose {
            administrationControls?.setAdministrationSurfaceVisible(false)
        }
    }
    DisposableEffect(offlineUpdateSurfaceVisible, offlineUpdateOwner) {
        if (offlineUpdateSurfaceVisible) offlineUpdateOwner?.setOfflineUpdateSurfaceVisible(true)
        onDispose {
            offlineUpdateOwner?.setOfflineUpdateSurfaceVisible(false)
        }
    }
    DisposableEffect(offlineDocumentHandoff) {
        onDispose { offlineDocumentHandoff.close() }
    }
    DisposableEffect(operatorSurfaceVisible, operatorControls) {
        if (operatorSurfaceVisible) operatorControls?.setOperatorSurfaceVisible(true)
        onDispose {
            operatorControls?.setOperatorSurfaceVisible(false)
        }
    }
    LaunchedEffect(automationSurfaceVisible, automationOwner) {
        // The ViewModel retains AutomationDialogController across configuration recreation. A
        // DisposableEffect teardown would transiently background the gateway, invalidate the
        // retained catalogs/approvals, and then reattach a controller that appears usable but can
        // only reject commands. True background transitions are handled by AppViewModel and
        // MainActivity; here the lease follows an actual surface-visibility change only.
        automationOwner?.setAutomationSurfaceVisible(automationSurfaceVisible)
    }
    DisposableEffect(picoClawSurfaceVisible, picoClawControls) {
        if (picoClawSurfaceVisible) picoClawControls?.setPicoClawSurfaceVisible(true)
        onDispose {
            picoClawControls?.setPicoClawSurfaceVisible(false)
        }
    }
    LaunchedEffect(
        profile.id,
        profile.authority,
        session.connection,
        session.sessionGeneration,
    ) {
        pendingPhase3Action = null
        pendingPowerAction = null
        disconnectConfirmationVisible = false
        if (!session.connection.isSessionUsable) {
            if (
                pointerMode == PointerMode.Captured ||
                pointerCaptureController.state != PointerCaptureState.Idle
            ) {
                // The capture host owns the one neutral remote-input release for this lifecycle.
                pointerCaptureController.release(PointerCaptureReleaseReason.SessionChanged)
            } else {
                input.releaseAllInput()
            }
            if (pointerMode == PointerMode.Captured) pointerMode = PointerMode.Trackpad
            if (
                session.connection == ConnectionState.Failed ||
                (!offlineDocumentPending && offlinePickerTarget == null)
            ) {
                closeOfflineUpdate()
            }
            overlay = overlay.dismissSessionBoundOverlay()
        }
    }
    val offlineUpdateGateway = if (offlineUpdateSurfaceVisible) {
        offlineUpdateOwner?.currentOfflineUpdateGateway()
    } else {
        null
    }
    LaunchedEffect(
        offlineUpdateSurfaceVisible,
        session.connection,
        session.sessionGeneration,
        offlineDocumentSequence,
        offlineUpdateGateway,
    ) {
        if (
            offlineUpdateSurfaceVisible && offlineDocumentPending &&
            session.connection.isSessionUsable && offlineUpdateGateway != null
        ) {
            if (offlineDocumentHandoff.deliverTo(offlineUpdateGateway)) {
                offlineDocumentPending = false
            }
        }
    }
    val requestClipboardPaste = {
        if (!session.connection.isSessionUsable) {
            pasteError = pasteDisconnectedMessage
        } else {
            when (val result = clipboardGateway.readDirectPlainText()) {
                is ClipboardReadResult.Available -> {
                    revealSensitivePaste = false
                    pasteRequest = PendingClipboardPaste(
                        confirmation = PasteConfirmationRequest(result.payload, currentPasteTarget),
                        keyboardLayout = keyboardLayout,
                    )
                }
                is ClipboardReadResult.Empty -> pasteError = pasteEmptyMessage
                is ClipboardReadResult.Rejected -> pasteError = if (
                    result.reason == ClipboardRejectionReason.TooLarge
                ) {
                    pasteTooLargeMessage
                } else {
                    pasteRejectedMessage
                }
                ClipboardReadResult.Unavailable -> pasteError = pasteUnavailableMessage
            }
        }
    }
    LaunchedEffect(session.connection, session.sessionGeneration, profile.id) {
        val request = pasteRequest ?: return@LaunchedEffect
        if (
            !session.connection.isSessionUsable ||
            !request.confirmation.remainsBoundTo(currentPasteTarget)
        ) {
            pasteRequest = null
            revealSensitivePaste = false
        }
    }
    LaunchedEffect(sensitiveWorkGeneration) {
        if (observedSensitiveWorkGeneration != sensitiveWorkGeneration) {
            pasteRequest = null
            pasteError = null
            revealSensitivePaste = false
            pendingPhase3Action = null
            if (offlineResultSensitiveGeneration != sensitiveWorkGeneration) {
                offlineDocumentHandoff.clear()
                offlineDocumentPending = false
                if (overlay == ConsoleOverlay.OfflineUpdate) overlay = ConsoleOverlay.None
            }
            overlay = overlay.dismissSessionBoundOverlay()
            observedSensitiveWorkGeneration = sensitiveWorkGeneration
        }
    }
    LaunchedEffect(pointerCaptureState) {
        if (
            pointerMode == PointerMode.Captured &&
            pointerCaptureState == PointerCaptureState.Idle &&
            pointerCaptureController.lastReleaseReason != null
        ) {
            pointerMode = PointerMode.Trackpad
        }
    }
    LaunchedEffect(immersiveMode, keyboardVisible, pointerCaptureState) {
        if (
            immersiveMode &&
            !keyboardVisible &&
            pointerCaptureState != PointerCaptureState.Active &&
            pointerCaptureState != PointerCaptureState.Requesting
        ) {
            consoleFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(
        pendingSharedPaste,
        session.connection,
        session.sessionGeneration,
        profile.id,
        pasteRequest,
    ) {
        val sharedPayload = pendingSharedPaste ?: return@LaunchedEffect
        if (session.connection.isSessionUsable && pasteRequest == null) {
            revealSensitivePaste = false
            pasteRequest = PendingClipboardPaste(
                confirmation = PasteConfirmationRequest(sharedPayload, currentPasteTarget),
                keyboardLayout = keyboardLayout,
            )
            onSharedPasteConsumed(sharedPayload)
        }
    }
    val selectPointerMode: (PointerMode) -> Unit = select@{ selected ->
        if (pointerMode == selected) return@select
        if (selected == PointerMode.Captured && !session.connection.isSessionUsable) {
            return@select
        }
        if (pointerMode == PointerMode.Captured) {
            pointerCaptureController.release(PointerCaptureReleaseReason.PointerModeChanged)
        } else {
            input.releaseAllInput()
        }
        if (selected == PointerMode.Trackpad || selected == PointerMode.Captured) {
            // A neutral relative report also switches the backend away from its last absolute point.
            input.moveRelative(0, 0)
        }
        pointerMode = selected
        if (selected == PointerMode.Captured) {
            controlsExpanded = false
            keyboardVisible = false
            pointerCaptureController.request()
        }
    }
    val toggleKeyboard = {
        val showing = !keyboardVisible
        keyboardVisible = showing
        if (showing) {
            if (pointerMode == PointerMode.Captured) {
                pointerCaptureController.release(PointerCaptureReleaseReason.KeyboardOpened)
                pointerMode = PointerMode.Trackpad
            }
            controlsExpanded = false
        } else {
            input.releaseAllInput()
        }
    }
    val toggleViewNavigation = {
        input.releaseAllInput()
        viewNavigationVisible = !viewNavigationVisible
        controlsExpanded = false
    }
    val requestViewportAction: (ViewportAction) -> Unit = { action ->
        viewportCommandSequence++
        viewportCommand = ViewportCommand(viewportCommandSequence, action)
    }
    val requestPowerAction: (PowerAction) -> Unit = request@{ action ->
        if (!session.connection.isSessionUsable) return@request
        pendingPowerAction = PendingPowerAction(
            action = action,
            destination = currentCoreDestination,
            destinationName = profile.name.ifBlank { profile.authority },
        )
    }
    val requestDisconnect = {
        if (
            pointerMode == PointerMode.Captured ||
            pointerCaptureController.state != PointerCaptureState.Idle
        ) {
            pointerCaptureController.release(PointerCaptureReleaseReason.User)
            pointerMode = PointerMode.Trackpad
        } else {
            input.releaseAllInput()
        }
        disconnectConfirmationVisible = true
    }
    BackHandler {
        when {
            pointerMode == PointerMode.Captured ||
                pointerCaptureState != PointerCaptureState.Idle -> {
                pointerCaptureController.release(PointerCaptureReleaseReason.Back)
                pointerMode = PointerMode.Trackpad
            }
            controlsExpanded -> controlsExpanded = false
            keyboardVisible -> {
                keyboardVisible = false
                input.releaseAllInput()
            }
            immersiveMode -> immersiveMode = false
            else -> requestDisconnect()
        }
    }

    ConsoleMaterialTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val handled = immersiveEscape.onKeyEvent(
                        event = event.nativeKeyEvent,
                        enabled = immersiveMode,
                        onEscape = { immersiveMode = false },
                    )
                    if (
                        handled &&
                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE
                    ) {
                        immersiveEscapeInProgress = when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> true
                            android.view.KeyEvent.ACTION_UP -> false
                            else -> immersiveEscapeInProgress
                        }
                    }
                    handled
                }
                .then(
                    if ((immersiveMode || immersiveEscapeInProgress) && !keyboardVisible) {
                        Modifier.focusRequester(consoleFocusRequester).focusable()
                    } else {
                        Modifier
                    },
                )
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val windowSizeClass = windowAdaptiveInfo.windowSizeClass
        val expandedWidth = windowSizeClass.isWidthAtLeastBreakpoint(
            WIDTH_DP_EXPANDED_LOWER_BOUND,
        ) || availableWidth.value >= WIDTH_DP_EXPANDED_LOWER_BOUND.toFloat()
        val compactWidth = !windowSizeClass.isWidthAtLeastBreakpoint(
            WIDTH_DP_MEDIUM_LOWER_BOUND,
        ) && availableWidth.value < WIDTH_DP_MEDIUM_LOWER_BOUND.toFloat()
        val presentation = consoleControlsPresentation(
            isExpandedWidth = expandedWidth,
            isCompactWidth = compactWidth,
            heightDp = availableHeight.value,
        )
        val consoleContentState = ConsoleContentState(
            destinationName = profile.name.ifBlank { profile.authority },
            authority = profile.authority,
            session = session,
            input = input,
            videoSurface = videoSurface,
            pointerMode = pointerMode,
            pointerCaptureController = pointerCaptureController,
            fitRequest = fitRequest,
            scrollSensitivity = scrollSensitivity,
            keyboardVisible = keyboardVisible,
            keyboardLayout = keyboardLayout,
            viewNavigationVisible = viewNavigationVisible,
            showQuickActionLabels =
                availableWidth.value >= WIDTH_DP_MEDIUM_LOWER_BOUND.toFloat(),
            viewportCommand = viewportCommand,
            immersiveMode = immersiveMode,
            onExitImmersive = { immersiveMode = false },
            onKeyboard = toggleKeyboard,
            onKeyboardLayoutChange = { keyboardLayout = it },
            onClipboard = {
                if (session.pasteProgress != null) {
                    coreControls.cancelPaste()
                } else {
                    requestClipboardPaste()
                }
            },
            onOpenControls = { controlsExpanded = true },
            onHideKeyboard = {
                keyboardVisible = false
                input.releaseAllInput()
            },
            onViewportAction = requestViewportAction,
            onCtrlAltDelete = { requestPowerAction(PowerAction.CtrlAltDelete) },
            onRetryConnection = {
                input.releaseAllInput()
                if (session.status == org.nanokvm.mobile.runtime.ConsoleMessage.AuthenticationExpired) {
                    onReauthenticate()
                } else {
                    coreControls.reconnect()
                }
            },
            onStopReconnect = {
                input.releaseAllInput()
                coreControls.cancelReconnect()
            },
            onDisconnect = requestDisconnect,
        )
        val controlsContent: @Composable (Modifier) -> Unit = { controlsModifier ->
            ConsoleControlContent(
                profile = profile,
                session = session,
                pointerMode = pointerMode,
                keyboardVisible = keyboardVisible,
                viewNavigationVisible = viewNavigationVisible,
                scrollSensitivity = scrollSensitivity,
                onClose = { controlsExpanded = false },
                onKeyboard = toggleKeyboard,
                onClipboard = {
                    controlsExpanded = false
                    if (session.pasteProgress != null) {
                        coreControls.cancelPaste()
                    } else {
                        requestClipboardPaste()
                    }
                },
                onPointerMode = selectPointerMode,
                onViewNavigation = toggleViewNavigation,
                onMouseClick = { button -> input.sendOneShotMouseClick(button) },
                onFit = {
                    fitRequest++
                    controlsExpanded = false
                },
                onActualSize = {
                    requestViewportAction(ViewportAction.ActualSize)
                    controlsExpanded = false
                },
                onScrollSettings = {
                    controlsExpanded = false
                    overlay = ConsoleOverlay.ScrollSettings
                },
                onVideo = {
                    controlsExpanded = false
                    overlay = ConsoleOverlay.VideoSettings
                },
                onPower = {
                    controlsExpanded = false
                    overlay = ConsoleOverlay.PowerMenu
                },
                onMore = {
                    controlsExpanded = false
                    overlay = ConsoleOverlay.MoreMenu
                },
                modifier = controlsModifier,
            )
        }

        // The adaptive scaffold is the permanent parent of RemoteViewport. Only its directive and
        // pane value change across breakpoints, so the TextureView never leaves its composition
        // slot and the decoder Surface stays attached while the current window is resized.
        val scaffoldDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo).copy(
            horizontalPartitionSpacerSize = 0.dp,
            defaultPanePreferredWidth = SUPPORTING_PANE_WIDTH_DP.dp,
        )
        val scaffoldValue = ThreePaneScaffoldValue(
            primary = PaneAdaptedValue.Expanded,
            secondary = if (
                presentation == ConsoleControlsPresentation.SupportingPane && controlsExpanded
            ) {
                PaneAdaptedValue.Expanded
            } else {
                PaneAdaptedValue.Hidden
            },
            tertiary = PaneAdaptedValue.Hidden,
        )
        SupportingPaneScaffold(
            directive = scaffoldDirective,
            value = scaffoldValue,
            mainPane = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(
                            if (presentation == ConsoleControlsPresentation.SupportingPane) {
                                "console-layout-supporting-pane"
                            } else {
                                "console-layout-single-pane"
                            },
                        ),
                ) {
                    ConsoleMainContent(consoleContentState)
                    if (pointerCaptureState != PointerCaptureState.Idle) {
                        PointerCaptureStatus(
                            state = pointerCaptureState,
                            onRetry = pointerCaptureController::request,
                            onRelease = {
                                pointerCaptureController.release(
                                    PointerCaptureReleaseReason.User,
                                )
                                pointerMode = PointerMode.Trackpad
                            },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        )
                    }
                    if (picoClawState?.value?.manualInputBlockedOrUncertain == true) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(8.dp)
                                .testTag("picoclaw-global-hid-lock"),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = 6.dp,
                        ) {
                            Text(
                                stringResource(R.string.picoclaw_global_lock),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    if (
                        controlsExpanded &&
                        presentation == ConsoleControlsPresentation.SideOverlay
                    ) {
                        val closeControlsLabel = stringResource(R.string.console_close_controls)
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = CONTROL_SCRIM_ALPHA))
                                .clickable(
                                    onClickLabel = closeControlsLabel,
                                    onClick = { controlsExpanded = false },
                                )
                                .semantics { contentDescription = closeControlsLabel }
                                .testTag("console-control-scrim"),
                        )
                        ConsoleControlSideSurface(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(consoleSideSheetWidth(availableWidth.value).dp)
                                .fillMaxHeight(),
                        ) {
                            controlsContent(Modifier.fillMaxSize())
                        }
                    }
                }
            },
            supportingPane = {
                if (
                    presentation == ConsoleControlsPresentation.SupportingPane && controlsExpanded
                ) {
                    ConsoleControlSideSurface(modifier = Modifier.fillMaxSize()) {
                        controlsContent(Modifier.fillMaxSize())
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

            if (controlsExpanded && presentation == ConsoleControlsPresentation.BottomSheet) {
                ConsoleControlBottomSheet(
                    onDismiss = { controlsExpanded = false },
                ) {
                    controlsContent(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = availableHeight * BOTTOM_SHEET_MAX_HEIGHT_FRACTION),
                    )
                }
            }
        }
    }

    if (overlay == ConsoleOverlay.VideoSettings) {
        VideoSettingsDialog(
            initial = session.videoSettings,
            appliedTransport = session.streamLabel.displayText(),
            initialMjpegFrameDetectionEnabled = mjpegFrameDetectionEnabled,
            onDismiss = { overlay = ConsoleOverlay.None },
            onApply = { settings, frameDetectionEnabled ->
                if (settings != session.videoSettings) coreControls.updateVideo(settings)
                if (frameDetectionEnabled != mjpegFrameDetectionEnabled) {
                    onMjpegFrameDetectionEnabledChange(frameDetectionEnabled)
                }
                overlay = ConsoleOverlay.None
            },
        )
    }
    if (overlay == ConsoleOverlay.ScrollSettings) {
        ScrollSettingsDialog(
            initialSensitivity = scrollSensitivity,
            onDismiss = { overlay = ConsoleOverlay.None },
            onApply = {
                onScrollSensitivityChange(it)
                overlay = ConsoleOverlay.None
            },
        )
    }
    if (overlay == ConsoleOverlay.PowerMenu) {
        PowerMenuDialog(
            onDismiss = { overlay = ConsoleOverlay.None },
            onChoose = {
                overlay = ConsoleOverlay.None
                requestPowerAction(it)
            },
        )
    }
    pendingPowerAction?.let { request ->
        ConfirmPowerDialog(
            request = request,
            onDismiss = { pendingPowerAction = null },
            onConfirm = {
                coreControls.power(request.destination, request.action)
                pendingPowerAction = null
            },
        )
    }
    if (overlay == ConsoleOverlay.MoreMenu) {
        MoreActionsDialog(
            onDismiss = { overlay = ConsoleOverlay.None },
            onReconnect = {
                overlay = ConsoleOverlay.None
                input.releaseAllInput()
                coreControls.reconnect()
            },
            onResetHid = {
                overlay = ConsoleOverlay.None
                input.releaseAllInput()
                coreControls.resetHid()
            },
            onDeviceInfo = {
                overlay = ConsoleOverlay.DeviceInfo
            },
            phase3Available = phase3Controls != null,
            onVirtualMedia = {
                if (phase3Controls != null) overlay = ConsoleOverlay.VirtualMedia
            },
            onWakeOnLan = {
                if (phase3Controls != null) overlay = ConsoleOverlay.WakeOnLan
            },
            administrationAvailable = administrationControls != null,
            onAdministration = {
                if (administrationControls != null) overlay = ConsoleOverlay.Administration
            },
            operatorToolsAvailable = operatorControls != null,
            onOperatorTools = {
                if (operatorControls != null) overlay = ConsoleOverlay.OperatorTools
            },
            automationAvailable = automationOwner != null,
            onAutomation = {
                automationOwner?.setAutomationSurfaceVisible(true)
                overlay = ConsoleOverlay.Automation
            },
            picoClawAvailable = picoClawControls != null,
            onPicoClaw = {
                if (picoClawControls != null) overlay = ConsoleOverlay.PicoClaw
            },
            immersiveMode = immersiveMode,
            onImmersiveMode = {
                overlay = ConsoleOverlay.None
                immersiveMode = !immersiveMode
            },
            onDisconnect = {
                overlay = ConsoleOverlay.None
                requestDisconnect()
            },
        )
    }
    if (disconnectConfirmationVisible) {
        DisconnectConfirmationDialog(
            destinationName = profile.name.ifBlank { profile.authority },
            authority = profile.authority,
            onDismiss = { disconnectConfirmationVisible = false },
            onConfirm = {
                disconnectConfirmationVisible = false
                onDisconnect()
            },
        )
    }
    if (overlay == ConsoleOverlay.DeviceInfo) {
        DeviceInfoDialog(
            session = session,
            onDismiss = { overlay = ConsoleOverlay.None },
        )
    }
    if (overlay == ConsoleOverlay.Administration && administrationControls != null) {
        AdministrationDialog(
            destinationLabel = profile.name.ifBlank { profile.authority },
            destination = currentAdministrationDestination,
            state = session.administration,
            controls = administrationControls,
            passwordChangeInProgress = passwordChangeInProgress,
            canProtectPassword = canProtectPassword,
            onPasswordChange = onPasswordChange,
            offlineUpdateAvailable = offlineUpdateOwner != null,
            onOfflineUpdate = {
                offlineUpdateOwner?.setOfflineUpdateSurfaceVisible(true)
                overlay = if (offlineUpdateOwner != null) {
                    ConsoleOverlay.OfflineUpdate
                } else {
                    ConsoleOverlay.None
                }
            },
            onDismiss = { overlay = ConsoleOverlay.None },
        )
    }
    if (overlay == ConsoleOverlay.OfflineUpdate) {
        OfflineUpdateGatewayDialog(
            gateway = offlineUpdateGateway,
            onChoosePackage = chooseOfflineUpdateDocument,
            onDismiss = closeOfflineUpdate,
        )
    }
    if (
        overlay == ConsoleOverlay.OperatorTools &&
        operatorControls != null && operatorState != null
    ) {
        val operatorMemory = remember(drafts, currentDraftSession) {
            drafts.operatorMemory(currentDraftSession)
        }
        OperatorDialog(
            destinationLabel = profile.name.ifBlank { profile.authority },
            destination = currentOperatorDestination,
            state = operatorState.value,
            output = operatorControls.operatorOutput,
            controls = operatorControls,
            onDismiss = {
                drafts.clearOperator()
                overlay = ConsoleOverlay.None
            },
            retainedMemory = operatorMemory,
        )
    }
    if (overlay == ConsoleOverlay.Automation) {
        val gateway = automationOwner?.currentAutomationGateway()
        if (gateway != null) {
            val leaderKeyAvailable = session.capabilities
                ?.get(NanoKvmCapability.HID_LEADER_KEY) !is
                NanoKvmCapabilitySupport.Unsupported
            val automationController = remember(
                drafts,
                currentDraftSession,
                gateway,
                leaderKeyAvailable,
            ) {
                drafts.automationController(
                    session = currentDraftSession,
                    gateway = gateway,
                    leaderKeyAvailable = leaderKeyAvailable,
                )
            }
            AutomationDialog(
                destinationLabel = profile.name.ifBlank { profile.authority },
                gateway = gateway,
                onDismiss = {
                    drafts.clearAutomation()
                    overlay = ConsoleOverlay.None
                },
                leaderKeyAvailable = leaderKeyAvailable,
                retainedController = automationController,
            )
        } else {
            LaunchedEffect(overlay, session.sessionGeneration) {
                drafts.clearAutomation()
                overlay = ConsoleOverlay.None
            }
        }
    }
    if (overlay == ConsoleOverlay.PicoClaw && picoClawControls != null && picoClawState != null) {
        PicoClawDialog(
            destinationLabel = profile.name.ifBlank { profile.authority },
            destination = currentPicoClawDestination,
            state = picoClawState.value,
            controls = picoClawControls,
            onDismiss = { overlay = ConsoleOverlay.None },
        )
    }
    if (overlay == ConsoleOverlay.VirtualMedia && phase3Controls != null) {
        VirtualMediaDialog(
            state = session.phase3,
            onDismiss = { overlay = ConsoleOverlay.None },
            onRefresh = phase3Controls::refreshPhase3,
            onMount = { image, mode ->
                pendingPhase3Action = PendingPhase3Action.MountImage(image, mode)
            },
            onRestore = { pendingPhase3Action = PendingPhase3Action.RestorePhysicalMedia },
            onDelete = { image ->
                pendingPhase3Action = PendingPhase3Action.DeleteImage(image)
            },
            onSetHidMode = { selection ->
                pendingPhase3Action = PendingPhase3Action.SetHidMode(selection)
            },
            onSetNetworkEnabled = { enabled ->
                pendingPhase3Action = PendingPhase3Action.SetNetworkEnabled(enabled)
            },
            onSetDiskEnabled = { enabled ->
                pendingPhase3Action = PendingPhase3Action.SetDiskEnabled(enabled)
            },
            onStartTransfer = { sourceUrl ->
                pendingPhase3Action = PendingPhase3Action.StartImageTransfer(sourceUrl)
            },
        )
    }
    if (overlay == ConsoleOverlay.WakeOnLan && phase3Controls != null) {
        WakeOnLanDialog(
            state = session.phase3,
            onDismiss = { overlay = ConsoleOverlay.None },
            onRefresh = phase3Controls::refreshPhase3,
            onWake = { macAddress ->
                pendingPhase3Action = PendingPhase3Action.SendWakeOnLan(macAddress)
            },
            onRename = { target, name ->
                phase3Controls.renamePhase3WakeOnLanTarget(
                    currentPhase3Destination,
                    target.id,
                    name,
                )
            },
            onDelete = { target ->
                pendingPhase3Action = PendingPhase3Action.DeleteWakeOnLan(target)
            },
        )
    }
    pendingPhase3Action?.let { action ->
        val controls = phase3Controls ?: return@let
        ConfirmPhase3ActionDialog(
            action = action,
            destinationLabel = profile.name.ifBlank { profile.authority },
            destination = currentPhase3Destination,
            onDismiss = { pendingPhase3Action = null },
            onConfirm = {
                when (action) {
                    is PendingPhase3Action.MountImage -> controls.mountPhase3Image(
                        currentPhase3Destination,
                        action.image.id,
                        action.mode,
                    )
                    PendingPhase3Action.RestorePhysicalMedia ->
                        controls.restorePhase3PhysicalMedia(currentPhase3Destination)
                    is PendingPhase3Action.DeleteImage -> controls.deletePhase3Image(
                        currentPhase3Destination,
                        action.image.id,
                    )
                    is PendingPhase3Action.SetDiskEnabled -> controls.setPhase3DiskEnabled(
                        currentPhase3Destination,
                        action.enabled,
                    )
                    is PendingPhase3Action.SetNetworkEnabled ->
                        controls.setPhase3NetworkEnabled(
                            currentPhase3Destination,
                            action.enabled,
                        )
                    is PendingPhase3Action.SetHidMode -> controls.setPhase3HidMode(
                        currentPhase3Destination,
                        action.selection,
                    )
                    is PendingPhase3Action.StartImageTransfer ->
                        controls.startPhase3ImageTransfer(
                            currentPhase3Destination,
                            action.sourceUrl,
                        )
                    is PendingPhase3Action.SendWakeOnLan -> controls.sendPhase3WakeOnLan(
                        currentPhase3Destination,
                        action.macAddress,
                    )
                    is PendingPhase3Action.DeleteWakeOnLan ->
                        controls.deletePhase3WakeOnLanTarget(
                            currentPhase3Destination,
                            action.target.id,
                        )
                }
                pendingPhase3Action = null
            },
        )
    }
    pasteRequest?.let { request ->
        ClipboardPasteDialog(
            request = request.confirmation,
            keyboardLayout = request.keyboardLayout,
            revealSensitive = revealSensitivePaste,
            onRevealSensitive = { revealSensitivePaste = !revealSensitivePaste },
            onDismiss = {
                pasteRequest = null
                revealSensitivePaste = false
            },
            onConfirm = {
                val stillConnected = session.connection.isSessionUsable
                val confirmation = request.confirmation
                val stillBound = confirmation.remainsBoundTo(currentPasteTarget)
                if (stillConnected && stillBound) {
                    coreControls.pasteText(
                        ApprovedPasteRequest(
                            profileId = confirmation.target.profileId,
                            authority = confirmation.target.authority,
                            sessionGeneration = confirmation.target.sessionGeneration,
                            content = confirmation.payload.text,
                            keyboardLayout = request.keyboardLayout,
                        ),
                    )
                } else if (!stillConnected || !stillBound) {
                    pasteError = pasteSessionChangedMessage
                }
                pasteRequest = null
                revealSensitivePaste = false
            },
        )
    }
    pasteError?.let { message ->
        AlertDialog(
            onDismissRequest = { pasteError = null },
            confirmButton = {
                TextButton(onClick = { pasteError = null }) {
                    Text(stringResource(R.string.console_ok))
                }
            },
            title = { Text(stringResource(R.string.console_clipboard_error_title)) },
            text = { Text(message) },
        )
    }
}

@Composable
private fun OfflineUpdateGatewayDialog(
    gateway: NanoKvmOfflineUpdateGateway?,
    onChoosePackage: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (gateway == null) {
        OfflineUpdateDialog(
            state = NanoKvmOfflineUpdateUiState(NanoKvmOfflineUpdatePhase.INACTIVE),
            onChoosePackage = onChoosePackage,
            onConfirm = {},
            onCancelUpload = {},
            onDismiss = onDismiss,
        )
        return
    }
    val state by gateway.state.collectAsStateWithLifecycle()
    OfflineUpdateDialog(
        state = state,
        onChoosePackage = onChoosePackage,
        onConfirm = gateway::confirmAndStart,
        onCancelUpload = gateway::cancelUpload,
        onDismiss = onDismiss,
    )
}

/** Owns at most one transient document opener and exposes no URI, path, grant, or filename. */
private class OfflineUpdateDocumentHandoff : AutoCloseable {
    private var pending: PendingOfflineUpdateDocument? = null

    fun replace(result: NanoKvmOfflineUpdateDocumentSelectionResult) {
        clear()
        pending = when (result) {
            is NanoKvmOfflineUpdateDocumentSelectionResult.Success ->
                PendingOfflineUpdateDocument.Source(result.source)
            is NanoKvmOfflineUpdateDocumentSelectionResult.Failure ->
                PendingOfflineUpdateDocument.Invalid
        }
    }

    fun deliverTo(gateway: NanoKvmOfflineUpdateGateway): Boolean {
        val selection = pending ?: return false
        pending = null
        when (selection) {
            is PendingOfflineUpdateDocument.Source -> gateway.select(selection.source)
            PendingOfflineUpdateDocument.Invalid -> gateway.rejectDocumentSelection()
        }
        return true
    }

    fun clear() {
        (pending as? PendingOfflineUpdateDocument.Source)?.source?.close()
        pending = null
    }

    override fun close() = clear()

    override fun toString(): String = "OfflineUpdateDocumentHandoff(pending=${pending != null})"
}

private sealed interface PendingOfflineUpdateDocument {
    class Source(val source: NanoKvmOfflineUpdateSource) : PendingOfflineUpdateDocument
    data object Invalid : PendingOfflineUpdateDocument
}

private data class OfflineUpdatePickerTarget(
    val profileId: String,
    val authority: String,
)

private data class PendingClipboardPaste(
    val confirmation: PasteConfirmationRequest,
    val keyboardLayout: KeyboardLayout,
)

private data class PendingPowerAction(
    val action: PowerAction,
    val destination: ApprovedCoreDestination,
    val destinationName: String,
)

private data class ConsoleContentState(
    val destinationName: String,
    val authority: String,
    val session: BackendSession,
    val input: RemoteInputSink,
    val videoSurface: VideoSurfaceSink,
    val pointerMode: PointerMode,
    val pointerCaptureController: PointerCaptureController,
    val fitRequest: Int,
    val scrollSensitivity: Float,
    val keyboardVisible: Boolean,
    val keyboardLayout: KeyboardLayout,
    val viewNavigationVisible: Boolean,
    val showQuickActionLabels: Boolean,
    val viewportCommand: ViewportCommand?,
    val immersiveMode: Boolean,
    val onExitImmersive: () -> Unit,
    val onKeyboard: () -> Unit,
    val onKeyboardLayoutChange: (KeyboardLayout) -> Unit,
    val onClipboard: () -> Unit,
    val onOpenControls: () -> Unit,
    val onHideKeyboard: () -> Unit,
    val onViewportAction: (ViewportAction) -> Unit,
    val onCtrlAltDelete: () -> Unit,
    val onRetryConnection: () -> Unit,
    val onStopReconnect: () -> Unit,
    val onDisconnect: () -> Unit,
)

@Composable
private fun ConsoleMainContent(state: ConsoleContentState) {
    Box(Modifier.fillMaxSize()) {
        ConsoleBody(
            modifier = Modifier.fillMaxSize(),
            destinationName = state.destinationName,
            authority = state.authority,
            session = state.session,
            input = state.input,
            videoSurface = state.videoSurface,
            pointerMode = state.pointerMode,
            pointerCaptureController = state.pointerCaptureController,
            fitRequest = state.fitRequest,
            scrollSensitivity = state.scrollSensitivity,
            keyboardVisible = state.keyboardVisible,
            keyboardLayout = state.keyboardLayout,
            viewNavigationVisible = state.viewNavigationVisible,
            showQuickActionLabels = state.showQuickActionLabels,
            viewportCommand = state.viewportCommand,
            immersiveMode = state.immersiveMode,
            onExitImmersive = state.onExitImmersive,
            onKeyboard = state.onKeyboard,
            onKeyboardLayoutChange = state.onKeyboardLayoutChange,
            onClipboard = state.onClipboard,
            onOpenControls = state.onOpenControls,
            onHideKeyboard = state.onHideKeyboard,
            onViewportAction = state.onViewportAction,
            onCtrlAltDelete = state.onCtrlAltDelete,
            onRetryConnection = state.onRetryConnection,
            onStopReconnect = state.onStopReconnect,
            onDisconnect = state.onDisconnect,
        )
        if (!state.viewNavigationVisible) {
            ConsoleQuickActions(
                session = state.session,
                keyboardVisible = state.keyboardVisible,
                showLabels = state.showQuickActionLabels,
                onKeyboard = state.onKeyboard,
                onClipboard = state.onClipboard,
                onOpenControls = state.onOpenControls,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun ConsoleBody(
    modifier: Modifier,
    destinationName: String,
    authority: String,
    session: BackendSession,
    input: RemoteInputSink,
    videoSurface: VideoSurfaceSink,
    pointerMode: PointerMode,
    pointerCaptureController: PointerCaptureController,
    fitRequest: Int,
    scrollSensitivity: Float,
    keyboardVisible: Boolean,
    keyboardLayout: KeyboardLayout,
    viewNavigationVisible: Boolean,
    showQuickActionLabels: Boolean,
    viewportCommand: ViewportCommand?,
    immersiveMode: Boolean,
    onExitImmersive: () -> Unit,
    onKeyboard: () -> Unit,
    onKeyboardLayoutChange: (KeyboardLayout) -> Unit,
    onClipboard: () -> Unit,
    onOpenControls: () -> Unit,
    onHideKeyboard: () -> Unit,
    onViewportAction: (ViewportAction) -> Unit,
    onCtrlAltDelete: () -> Unit,
    onRetryConnection: () -> Unit,
    onStopReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val consoleColors = LocalConsoleColorScheme.current
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RemoteViewport(
                input = input,
                videoSurface = videoSurface,
                remoteWidth = session.remoteWidth,
                remoteHeight = session.remoteHeight,
                inputGeneration = session.sessionGeneration,
                videoSurfaceGeneration = session.videoSurfaceGeneration,
                pointerMode = pointerMode,
                pointerCaptureController = pointerCaptureController,
                fitRequest = fitRequest,
                scrollSensitivity = scrollSensitivity,
                viewNavigationVisible = viewNavigationVisible,
                keyboardVisible = keyboardVisible,
                viewportCommand = viewportCommand,
                navigationActions = {
                    ConsoleQuickActions(
                        session = session,
                        keyboardVisible = keyboardVisible,
                        showLabels = showQuickActionLabels,
                        onKeyboard = onKeyboard,
                        onClipboard = onClipboard,
                        onOpenControls = onOpenControls,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!session.connection.isSessionUsable) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .testTag("console-transient-status")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    colors = CardDefaults.cardColors(
                        containerColor = consoleColors.controlSurfaceElevated.copy(alpha = 0.96f),
                        contentColor = consoleColors.onSurface,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(connectionLabel(session.connection), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                R.string.console_confirm_host_target,
                                destinationName,
                                authority,
                            ),
                            color = consoleColors.onSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            session.status?.displayText()
                                ?: stringResource(R.string.console_waiting_for_video),
                            color = consoleColors.onSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (
                            session.connection == ConnectionState.Reconnecting &&
                            session.reconnectAttempt != null &&
                            session.reconnectMaximumAttempts != null
                        ) {
                            Text(
                                stringResource(
                                    R.string.console_reconnect_progress,
                                    session.reconnectAttempt,
                                    session.reconnectMaximumAttempts,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            session.nextReconnectDelayMillis?.let { delayMillis ->
                                val seconds = ((delayMillis + 999L) / 1_000L)
                                    .coerceAtLeast(1L)
                                    .coerceAtMost(Int.MAX_VALUE.toLong())
                                    .toInt()
                                Text(
                                    stringResource(
                                        R.string.console_reconnect_delay,
                                        pluralStringResource(
                                            R.plurals.console_message_reconnect_delay,
                                            seconds,
                                            seconds,
                                        ),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when (session.connection) {
                                ConnectionState.Reconnecting -> OutlinedButton(
                                    onClick = onStopReconnect,
                                    modifier = Modifier.testTag("stop-reconnect-action"),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = consoleColors.onSurface,
                                    ),
                                ) {
                                    Text(stringResource(R.string.console_stop_reconnecting))
                                }
                                ConnectionState.Failed,
                                ConnectionState.Disconnected,
                                -> OutlinedButton(
                                    onClick = onRetryConnection,
                                    modifier = Modifier.testTag("retry-connection-action"),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = consoleColors.onSurface,
                                    ),
                                ) {
                                    Text(stringResource(R.string.console_retry))
                                }
                                ConnectionState.Connecting,
                                ConnectionState.Connected,
                                ConnectionState.Degraded,
                                -> Unit
                            }
                            Button(
                                onClick = onDisconnect,
                                modifier = Modifier.testTag("transient-disconnect-action"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = consoleColors.active,
                                    contentColor = consoleColors.onActive,
                                ),
                            ) {
                                Text(stringResource(R.string.console_disconnect))
                            }
                        }
                    }
                }
            }
            if (session.connection == ConnectionState.Degraded) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .testTag("console-degraded-status")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = consoleColors.controlSurfaceElevated,
                    contentColor = consoleColors.warning,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        listOfNotNull(
                            connectionLabel(ConnectionState.Degraded),
                            session.streamLabel.displayText(),
                            session.status?.displayText(),
                        ).joinToString(stringResource(R.string.console_status_separator)),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        ConsoleKeyboard(
            input = input,
            visible = keyboardVisible,
            releaseGeneration = session.inputReleaseGeneration,
            interceptLocalEscape = immersiveMode,
            onLocalEscape = onExitImmersive,
            layout = keyboardLayout,
            onLayoutChange = onKeyboardLayoutChange,
            onClose = onHideKeyboard,
            onCtrlAltDelete = onCtrlAltDelete,
            onViewportAction = onViewportAction,
            modifier = Modifier.testTag("keyboard-accessory"),
        )
    }
}

@Composable
private fun ConsoleQuickActions(
    session: BackendSession,
    keyboardVisible: Boolean,
    showLabels: Boolean,
    onKeyboard: () -> Unit,
    onClipboard: () -> Unit,
    onOpenControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val consoleColors = LocalConsoleColorScheme.current
    val keyboardActionDescription = stringResource(
        if (keyboardVisible) R.string.console_hide_keyboard else R.string.console_show_keyboard,
    )
    val keyboardStateDescription = stringResource(
        if (keyboardVisible) R.string.console_keyboard_visible else R.string.console_keyboard_hidden,
    )
    val pasteProgress = session.pasteProgress
    val clipboardActionDescription = if (pasteProgress == null) {
        stringResource(R.string.console_type_phone_clipboard)
    } else {
        stringResource(
            if (pasteProgress.phase == RemotePastePhase.Cancelling) {
                R.string.console_stopping_clipboard_typing_progress
            } else {
                R.string.console_cancel_clipboard_typing_progress
            },
            pasteProgress.sentKeystrokes,
            pasteProgress.totalKeystrokes,
        )
    }
    val openControlsDescription = stringResource(R.string.console_open_controls)
    val keyboardLabel = stringResource(R.string.console_quick_keyboard)
    val clipboardLabel = stringResource(
        if (pasteProgress == null) R.string.console_quick_clipboard else R.string.console_quick_stop,
    )
    val controlsLabel = stringResource(R.string.console_quick_controls)
    val sessionSummary = sessionStatusSummary(session)
    val connectionStatus = connectionLabel(session.connection)
    val statusDescription = listOf(connectionStatus, sessionSummary)
        .filter(String::isNotBlank)
        .joinToString(stringResource(R.string.console_status_separator))
    val useLargeTextLayout = LocalDensity.current.fontScale >= LARGE_TEXT_LAYOUT_FONT_SCALE
    val actionItems: @Composable () -> Unit = {
        ConsoleQuickAction(
            icon = Icons.Default.Keyboard,
            visibleLabel = keyboardLabel.takeIf { showLabels },
            actionDescription = keyboardActionDescription,
            state = keyboardStateDescription,
            selected = keyboardVisible,
            onClick = onKeyboard,
            tag = "console-keyboard-action",
        )
        ConsoleQuickAction(
            icon = if (pasteProgress == null) {
                Icons.Default.ContentPaste
            } else {
                Icons.Default.Close
            },
            visibleLabel = clipboardLabel.takeIf { showLabels },
            actionDescription = clipboardActionDescription,
            enabled = pasteProgress != null || session.connection.isSessionUsable,
            selected = pasteProgress != null,
            onClick = onClipboard,
            tag = "console-clipboard-action",
        )
        ConsoleQuickAction(
            icon = Icons.Default.Settings,
            visibleLabel = controlsLabel.takeIf { showLabels },
            actionDescription = openControlsDescription,
            state = statusDescription,
            onClick = onOpenControls,
            tag = "console-controls-action",
            statusIcon = sessionStatusIcon(session.connection),
            statusColor = sessionStatusColor(session),
        )
    }
    if (!showLabels) {
        Box(modifier.testTag("console-quick-actions")) {
            Row(
                modifier = Modifier.testTag("console-quick-actions-icons"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actionItems()
            }
        }
        return
    }
    Surface(
        modifier = modifier.testTag("console-quick-actions"),
        shape = MaterialTheme.shapes.large,
        color = consoleColors.controlSurfaceElevated,
        contentColor = consoleColors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        if (useLargeTextLayout) {
            Column(
                modifier = Modifier
                    .padding(4.dp)
                    .testTag("console-quick-actions-labelled"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                actionItems()
            }
        } else {
            Row(
                modifier = Modifier
                    .padding(4.dp)
                    .testTag("console-quick-actions-labelled"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                actionItems()
            }
        }
    }
}

@Composable
private fun ConsoleQuickAction(
    icon: ImageVector,
    visibleLabel: String?,
    actionDescription: String,
    onClick: () -> Unit,
    tag: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    state: String? = null,
    statusIcon: ImageVector? = null,
    statusColor: Color = Color.Unspecified,
) {
    val consoleColors = LocalConsoleColorScheme.current
    val actionModifier = Modifier
        .semantics(mergeDescendants = true) {
            contentDescription = actionDescription
            state?.let { stateDescription = it }
        }
        .testTag(tag)
    if (visibleLabel == null) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = actionModifier.size(48.dp),
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = consoleColors.controlSurfaceElevated.copy(alpha = 0.96f),
                contentColor = when {
                    !enabled -> consoleColors.onSurfaceMuted
                    selected -> consoleColors.active
                    else -> consoleColors.onSurface
                },
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
            ) {
                ConsoleQuickActionIcon(
                    icon = icon,
                    statusIcon = statusIcon,
                    statusColor = statusColor,
                    statusIconInset = 4.dp,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) consoleColors.active else consoleColors.onSurface,
            disabledContentColor = consoleColors.onSurfaceMuted,
        ),
        modifier = actionModifier
            .widthIn(min = 84.dp)
            .heightIn(min = 56.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ConsoleQuickActionIcon(
                icon = icon,
                statusIcon = statusIcon,
                statusColor = statusColor,
            )
            Text(
                visibleLabel,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun ConsoleQuickActionIcon(
    icon: ImageVector,
    statusIcon: ImageVector?,
    statusColor: Color,
    modifier: Modifier = Modifier,
    statusIconInset: Dp = 0.dp,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        if (statusIcon != null) {
            Icon(
                statusIcon,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = statusIconInset, end = statusIconInset)
                    .size(10.dp),
                tint = statusColor,
            )
        }
    }
}

private const val LARGE_TEXT_LAYOUT_FONT_SCALE = 1.5f

@Composable
private fun ClipboardPasteDialog(
    request: PasteConfirmationRequest,
    keyboardLayout: KeyboardLayout,
    revealSensitive: Boolean,
    onRevealSensitive: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val payload = request.payload
    val previewHidden = payload.isSensitive && !revealSensitive
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.console_clipboard_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        R.string.console_clipboard_destination,
                        request.target.destinationLabel,
                        request.target.authority,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.console_clipboard_size,
                        pluralStringResource(
                            R.plurals.console_clipboard_character_count,
                            payload.characterCount,
                            payload.characterCount,
                        ),
                        pluralStringResource(
                            R.plurals.console_clipboard_utf8_byte_count,
                            payload.utf8ByteCount,
                            payload.utf8ByteCount,
                        ),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    stringResource(
                        R.string.console_clipboard_keyboard_layout,
                        stringResource(
                            if (keyboardLayout == KeyboardLayout.Us) {
                                R.string.console_keyboard_layout_us
                            } else {
                                R.string.console_keyboard_layout_uk
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("clipboard-preview"),
                ) {
                    Text(
                        text = if (previewHidden) {
                            stringResource(R.string.console_clipboard_sensitive_hidden)
                        } else {
                            payload.text
                        },
                        modifier = Modifier.padding(12.dp),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (payload.isSensitive) {
                    OutlinedButton(onClick = onRevealSensitive) {
                        Icon(
                            imageVector = if (previewHidden) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.VisibilityOff
                            },
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (previewHidden) {
                                    R.string.console_clipboard_reveal_sensitive
                                } else {
                                    R.string.console_clipboard_hide_sensitive
                                },
                            ),
                        )
                    }
                }
                payload.warnings.forEach { warning ->
                    Text(
                        text = "• " + stringResource(warning.stringResourceId()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.console_clipboard_hid_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.console_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = true,
                modifier = Modifier.testTag("clipboard-confirm"),
            ) {
                Text(stringResource(R.string.console_clipboard_type_remote))
            }
        },
    )
}

private fun ClipboardTextWarning.stringResourceId(): Int = when (this) {
    ClipboardTextWarning.ContainsNewline -> R.string.console_clipboard_warning_newline
    ClipboardTextWarning.ContainsTab -> R.string.console_clipboard_warning_tab
    ClipboardTextWarning.ContainsOtherControlCharacter ->
        R.string.console_clipboard_warning_control
}

@Composable
private fun ConsoleControlSideSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val consoleColors = LocalConsoleColorScheme.current
    Surface(
        modifier = modifier.testTag("console-control-sheet"),
        color = consoleColors.controlSurfaceElevated,
        contentColor = consoleColors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        content = { content() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleControlBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val consoleColors = LocalConsoleColorScheme.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("console-control-sheet"),
        containerColor = consoleColors.controlSurfaceElevated,
        contentColor = consoleColors.onSurface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = consoleColors.onSurfaceMuted)
        },
        tonalElevation = 0.dp,
        content = { content() },
    )
}

@Composable
internal fun PointerCaptureStatus(
    state: PointerCaptureState,
    onRetry: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == PointerCaptureState.Idle) return
    val captureMessage = when (state) {
        PointerCaptureState.Active -> stringResource(R.string.console_capture_active)
        PointerCaptureState.Requesting -> stringResource(R.string.console_capture_requesting)
        is PointerCaptureState.Unavailable -> stringResource(R.string.console_capture_unavailable)
        PointerCaptureState.Idle -> return
    }
    Surface(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .testTag("pointer-capture-status")
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(captureMessage, style = MaterialTheme.typography.labelLarge)
            if (state is PointerCaptureState.Unavailable) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pointer-capture-retry-action"),
                ) {
                    Text(stringResource(R.string.console_retry))
                }
            }
            TextButton(
                onClick = onRelease,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pointer-capture-release-action"),
            ) {
                Text(
                    stringResource(
                        if (state is PointerCaptureState.Unavailable) {
                            R.string.console_close
                        } else {
                            R.string.console_release_input
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ConsoleControlContent(
    profile: HostProfile,
    session: BackendSession,
    pointerMode: PointerMode,
    keyboardVisible: Boolean,
    viewNavigationVisible: Boolean,
    scrollSensitivity: Float,
    onClose: () -> Unit,
    onKeyboard: () -> Unit,
    onClipboard: () -> Unit,
    onPointerMode: (PointerMode) -> Unit,
    onViewNavigation: () -> Unit,
    onMouseClick: (MouseButton) -> Unit,
    onFit: () -> Unit,
    onActualSize: () -> Unit,
    onScrollSettings: () -> Unit,
    onVideo: () -> Unit,
    onPower: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SheetAction(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            stringResource(R.string.console_close_controls),
            onClose,
        )
        SideSessionStatus(profile, session)
        SheetAction(
            Icons.Default.OpenWith,
            stringResource(
                R.string.console_scroll_sensitivity_value,
                scrollSensitivity.formatZoom(),
            ),
            onScrollSettings,
        )
        SheetToggle(
            Icons.Default.Keyboard,
            stringResource(
                if (keyboardVisible) {
                    R.string.console_hide_keyboard
                } else {
                    R.string.console_show_keyboard
                },
            ),
            keyboardVisible,
            onKeyboard,
        )
        val pasteProgress = session.pasteProgress
        SheetAction(
            if (pasteProgress == null) Icons.Default.ContentPaste else Icons.Default.Close,
            if (pasteProgress == null) {
                stringResource(R.string.console_type_phone_clipboard)
            } else {
                stringResource(
                    if (pasteProgress.phase == RemotePastePhase.Cancelling) {
                        R.string.console_stopping_clipboard_typing_progress
                    } else {
                        R.string.console_cancel_clipboard_typing_progress
                    },
                    pasteProgress.sentKeystrokes,
                    pasteProgress.totalKeystrokes,
                )
            },
            onClipboard,
        )
        PointerModeChoices(
            selected = pointerMode,
            captureEnabled = session.connection.isSessionUsable,
            onSelect = onPointerMode,
        )
        SheetToggle(
            Icons.Default.OpenWith,
            stringResource(
                if (viewNavigationVisible) {
                    R.string.console_hide_view_pad
                } else {
                    R.string.console_dock_view_pad
                },
            ),
            viewNavigationVisible,
            onViewNavigation,
        )
        MouseClickControls(onMouseClick)
        SheetAction(
            Icons.Default.FitScreen,
            stringResource(R.string.console_fit_view),
            onFit,
        )
        SheetAction(
            Icons.Default.OpenWith,
            stringResource(R.string.console_actual_size_remote_view),
            onActualSize,
        )
        SheetAction(
            Icons.Default.Tune,
            stringResource(R.string.console_video_settings),
            onVideo,
        )
        SheetAction(
            Icons.Default.PowerSettingsNew,
            stringResource(R.string.console_power_controls),
            onPower,
            enabled = session.connection.isSessionUsable,
        )
        SheetAction(
            Icons.Default.MoreVert,
            stringResource(R.string.console_more_actions),
            onMore,
        )
    }
}

/** Emits an adjacent press/release pair from one synchronous UI action. */
private fun RemoteInputSink.sendOneShotMouseClick(button: MouseButton) {
    mouseButton(button, true)
    mouseButton(button, false)
}

@Composable
private fun PointerModeChoices(
    selected: PointerMode,
    captureEnabled: Boolean,
    onSelect: (PointerMode) -> Unit,
) {
    val directLabel = stringResource(R.string.console_pointer_direct)
    val trackpadLabel = stringResource(R.string.console_pointer_trackpad)
    val captureLabel = stringResource(R.string.console_capture_input)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("console-pointer-mode-controls"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.console_pointer_mode_title),
            style = MaterialTheme.typography.labelMedium,
            color = LocalConsoleColorScheme.current.onSurfaceMuted,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("console-pointer-mode-choices"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = selected == PointerMode.Direct,
                onClick = { onSelect(PointerMode.Direct) },
                label = { Text(directLabel) },
                leadingIcon = {
                    Icon(Icons.Default.TouchApp, contentDescription = null)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = directLabel },
                colors = consoleFilterChipColors(),
            )
            FilterChip(
                selected = selected == PointerMode.Trackpad,
                onClick = { onSelect(PointerMode.Trackpad) },
                label = { Text(trackpadLabel) },
                leadingIcon = {
                    Icon(Icons.Default.Mouse, contentDescription = null)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = trackpadLabel },
                colors = consoleFilterChipColors(),
            )
            FilterChip(
                selected = selected == PointerMode.Captured,
                enabled = captureEnabled,
                onClick = { onSelect(PointerMode.Captured) },
                label = { Text(captureLabel) },
                leadingIcon = {
                    Icon(Icons.Default.OpenWith, contentDescription = null)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = captureLabel },
                colors = consoleFilterChipColors(),
            )
        }
        Text(
            text = stringResource(R.string.console_capture_input_detail),
            style = MaterialTheme.typography.bodySmall,
            color = LocalConsoleColorScheme.current.onSurfaceMuted,
        )
    }
}

@Composable
private fun consoleFilterChipColors() = LocalConsoleColorScheme.current.let { consoleColors ->
    FilterChipDefaults.filterChipColors(
        containerColor = consoleColors.controlSurface,
        labelColor = consoleColors.onSurface,
        iconColor = consoleColors.onSurface,
        disabledContainerColor = consoleColors.controlSurface,
        disabledLabelColor = consoleColors.onSurfaceMuted,
        disabledLeadingIconColor = consoleColors.onSurfaceMuted,
        selectedContainerColor = consoleColors.active,
        selectedLabelColor = consoleColors.onActive,
        selectedLeadingIconColor = consoleColors.onActive,
        disabledSelectedContainerColor = consoleColors.controlSurface,
    )
}

@Composable
private fun MouseClickControls(onClick: (MouseButton) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("console-mouse-controls"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.console_mouse_buttons),
            style = MaterialTheme.typography.labelMedium,
            color = LocalConsoleColorScheme.current.onSurfaceMuted,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("console-mouse-button-actions"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MouseClickButton(
                label = stringResource(R.string.console_mouse_middle_click),
                tag = "console-mouse-middle-click",
            ) { onClick(MouseButton.Middle) }
            MouseClickButton(
                label = stringResource(R.string.console_mouse_back_click),
                tag = "console-mouse-back-click",
            ) { onClick(MouseButton.Back) }
            MouseClickButton(
                label = stringResource(R.string.console_mouse_forward_click),
                tag = "console-mouse-forward-click",
            ) { onClick(MouseButton.Forward) }
        }
    }
}

@Composable
private fun MouseClickButton(label: String, tag: String, onClick: () -> Unit) {
    val consoleColors = LocalConsoleColorScheme.current
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label }
            .testTag(tag),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = consoleColors.onSurface,
        ),
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun SideSessionStatus(profile: HostProfile, session: BackendSession) {
    val consoleColors = LocalConsoleColorScheme.current
    val connectionStatus = connectionLabel(session.connection)
    val statusSummary = sessionStatusSummary(session)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("console-status-summary")
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = sessionStatusIcon(session.connection),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = sessionStatusColor(session),
            )
            Text(
                profile.name,
                style = MaterialTheme.typography.labelLarge,
                color = consoleColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            listOf(connectionStatus, statusSummary)
                .filter(String::isNotBlank)
                .joinToString(stringResource(R.string.console_status_separator)),
            style = MaterialTheme.typography.labelSmall,
            color = consoleColors.onSurfaceMuted,
            modifier = Modifier.testTag("console-connection-status-label"),
        )
        val applicationVersion = session.deviceStatus.applicationVersion.takeIf(String::isNotBlank)
        val hardwareVersion = session.deviceStatus.hardwareVersion?.takeIf(String::isNotBlank)
        val deviceDetails = listOfNotNull(
            applicationVersion?.let { stringResource(R.string.console_device_app_version, it) },
            hardwareVersion?.let { stringResource(R.string.console_device_hardware_version, it) },
            session.deviceStatus.mdnsName?.takeIf(String::isNotBlank),
        )
        if (deviceDetails.isNotEmpty()) {
            Text(
                deviceDetails.joinToString(stringResource(R.string.console_status_separator)),
                style = MaterialTheme.typography.labelSmall,
                color = consoleColors.onSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        session.deviceStatus.powerOn?.let { powerOn ->
            val powerLabel = stringResource(
                if (powerOn) R.string.console_host_power_on else R.string.console_host_power_off,
            )
            val driveLabel = if (session.deviceStatus.hardDriveActive == true) {
                stringResource(R.string.console_host_drive_active)
            } else {
                null
            }
            Text(
                listOfNotNull(powerLabel, driveLabel)
                    .joinToString(stringResource(R.string.console_status_separator)),
                style = MaterialTheme.typography.labelSmall,
                color = consoleColors.onSurfaceMuted,
            )
        }
        session.status?.let { status ->
            Text(
                status.displayText(),
                style = MaterialTheme.typography.labelSmall,
                color = consoleColors.onSurfaceMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        session.lastActionFeedback?.let { feedback ->
            key(feedback.revision) {
                Text(
                    feedback.content.displayText(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelSmall,
                    color = consoleColors.onSurfaceMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun sessionStatusIcon(connection: ConnectionState): ImageVector = when (connection) {
    ConnectionState.Connected -> Icons.Default.CheckCircle
    ConnectionState.Degraded -> Icons.Default.ErrorOutline
    ConnectionState.Connecting, ConnectionState.Reconnecting -> Icons.Default.Refresh
    ConnectionState.Failed -> Icons.Default.ErrorOutline
    ConnectionState.Disconnected -> Icons.Default.LinkOff
}

@Composable
private fun sessionStatusColor(session: BackendSession): Color {
    val consoleColors = LocalConsoleColorScheme.current
    return when (session.connection) {
        ConnectionState.Connected -> consoleColors.connected
        ConnectionState.Degraded -> consoleColors.warning
        ConnectionState.Connecting, ConnectionState.Reconnecting -> consoleColors.warning
        ConnectionState.Failed -> consoleColors.critical
        ConnectionState.Disconnected -> consoleColors.onSurfaceMuted
    }
}

@Composable
private fun sessionStatusSummary(session: BackendSession): String {
    val frames = session.framesPerSecond?.let { value ->
        stringResource(R.string.console_status_fps, value)
    }
    val latency = session.roundTripMs?.let { value ->
        stringResource(R.string.console_status_latency, value)
    }
    val dropped = session.droppedFrames.takeIf { it > 0L }?.let { value ->
        stringResource(R.string.console_status_dropped_frames, value)
    }
    val stalls = session.videoStallEvents.takeIf { it > 0L }?.let { value ->
        stringResource(R.string.console_status_video_stalls, value)
    }
    return listOfNotNull(session.streamLabel.displayText(), frames, latency, dropped, stalls)
        .joinToString(stringResource(R.string.console_status_separator))
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val consoleColors = LocalConsoleColorScheme.current
    val contentColor = if (enabled) consoleColors.onSurface else consoleColors.onSurfaceMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

@Composable
private fun SheetToggle(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val consoleColors = LocalConsoleColorScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = selected,
                role = Role.Switch,
                onValueChange = { onClick() },
            )
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) consoleColors.active else consoleColors.onSurface,
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) consoleColors.active else consoleColors.onSurface,
        )
    }
}

@Composable
private fun ScrollSettingsDialog(
    initialSensitivity: Float,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit,
) {
    var sensitivity by rememberSaveable(initialSensitivity) {
        mutableFloatStateOf(normalizeScrollSensitivity(initialSensitivity))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.console_scroll_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.console_scroll_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    sensitivity.formatZoom(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = normalizeScrollSensitivity(it) },
                    valueRange = MIN_SCROLL_SENSITIVITY..MAX_SCROLL_SENSITIVITY,
                    steps = 4,
                    modifier = Modifier.testTag("scroll-sensitivity-slider"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.console_scroll_slower),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        stringResource(R.string.console_scroll_faster),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(normalizeScrollSensitivity(sensitivity)) }) {
                Text(stringResource(R.string.console_apply))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { sensitivity = DEFAULT_SCROLL_SENSITIVITY }) {
                    Text(stringResource(R.string.console_reset))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.console_cancel))
                }
            }
        },
    )
}

@Composable
private fun VideoSettingsDialog(
    initial: VideoSettings,
    appliedTransport: String,
    initialMjpegFrameDetectionEnabled: Boolean,
    onDismiss: () -> Unit,
    onApply: (VideoSettings, Boolean) -> Unit,
) {
    var transport by rememberSaveable(initial) { mutableStateOf(initial.transportPreference) }
    var resolution by rememberSaveable(initial) { mutableIntStateOf(initial.resolutionHeight) }
    var fps by rememberSaveable(initial) { mutableIntStateOf(initial.framesPerSecond) }
    var bitrate by rememberSaveable(initial) { mutableIntStateOf(initial.bitrateKbps) }
    var quality by rememberSaveable(initial) { mutableIntStateOf(initial.jpegQuality) }
    var gop by rememberSaveable(initial) { mutableIntStateOf(initial.gopFrames) }
    var mjpegFrameDetectionEnabled by rememberSaveable(initialMjpegFrameDetectionEnabled) {
        mutableStateOf(initialMjpegFrameDetectionEnabled)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.console_video_settings)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(R.string.console_applied_transport, appliedTransport),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("video-applied-transport"),
                )
                TransportChoiceRow(transport) { transport = it }
                ChoiceRow(
                    stringResource(R.string.console_resolution),
                    listOf(0, 480, 600, 720, 1080),
                    resolution,
                    {
                        if (it == 0) {
                            stringResource(R.string.console_resolution_native)
                        } else {
                            stringResource(R.string.console_resolution_value, it)
                        }
                    },
                ) { resolution = it }
                ChoiceRow(
                    stringResource(R.string.console_frame_rate),
                    listOf(24, 30, 60),
                    fps,
                    { stringResource(R.string.console_frame_rate_value, it) },
                ) { fps = it }
                if (transport != VideoTransportPreference.MJPEG) {
                    Text(
                        stringResource(R.string.console_h264_settings),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    ChoiceRow(
                        stringResource(R.string.console_h264_bitrate),
                        listOf(1_000, 2_000, 3_000, 5_000),
                        bitrate,
                        { stringResource(R.string.console_bitrate_value, it / 1_000) },
                    ) { bitrate = it }
                    ChoiceRow(
                        stringResource(R.string.console_h264_gop),
                        listOf(10, 30, 50, 100),
                        gop,
                        {
                            pluralStringResource(
                                R.plurals.console_h264_gop_value,
                                it,
                                it,
                            )
                        },
                    ) { gop = it }
                }
                if (transport != VideoTransportPreference.H264) {
                    Text(
                        stringResource(R.string.console_mjpeg_settings),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    ChoiceRow(
                        stringResource(R.string.console_mjpeg_quality),
                        listOf(50, 60, 80, 100),
                        quality,
                        { it.toString() },
                    ) { quality = it }
                    MjpegFrameDetectionPreferenceControl(
                        enabled = mjpegFrameDetectionEnabled,
                        onEnabledChange = { mjpegFrameDetectionEnabled = it },
                    )
                }
                Text(
                    stringResource(R.string.console_video_settings_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(
                        VideoSettings(
                            transportPreference = transport,
                            resolutionHeight = resolution,
                            framesPerSecond = fps,
                            bitrateKbps = bitrate,
                            jpegQuality = quality,
                            gopFrames = gop,
                        ),
                        mjpegFrameDetectionEnabled,
                    )
                },
            ) {
                Text(stringResource(R.string.console_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.console_cancel))
            }
        },
    )
}

@Composable
private fun TransportChoiceRow(
    selected: VideoTransportPreference,
    onSelect: (VideoTransportPreference) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.console_transport), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VideoTransportPreference.entries.forEach { preference ->
                FilterChip(
                    selected = preference == selected,
                    onClick = { onSelect(preference) },
                    label = {
                        Text(
                            when (preference) {
                                VideoTransportPreference.Auto -> {
                                    stringResource(R.string.console_transport_auto)
                                }
                                VideoTransportPreference.WEBRTC -> {
                                    stringResource(R.string.console_transport_webrtc)
                                }
                                VideoTransportPreference.H264 -> {
                                    stringResource(R.string.console_transport_h264)
                                }
                                VideoTransportPreference.MJPEG -> {
                                    stringResource(R.string.console_transport_mjpeg)
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    choices: List<Int>,
    selected: Int,
    label: @Composable (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    label = { Text(label(choice)) },
                )
            }
        }
    }
}

@Composable
private fun PowerMenuDialog(onDismiss: () -> Unit, onChoose: (PowerAction) -> Unit) {
    val consoleColors = LocalConsoleColorScheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = consoleColors.warning,
            )
        },
        title = { Text(stringResource(R.string.console_host_controls)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PowerChoice(
                    stringResource(R.string.console_power_button),
                    stringResource(R.string.console_power_button_detail),
                ) { onChoose(PowerAction.ShortPress) }
                PowerChoice(
                    stringResource(R.string.console_reset_button),
                    stringResource(R.string.console_reset_button_detail),
                ) { onChoose(PowerAction.Reset) }
                PowerChoice(
                    stringResource(R.string.console_force_power_off),
                    stringResource(R.string.console_force_power_off_detail),
                ) { onChoose(PowerAction.LongPress()) }
                PowerChoice(
                    stringResource(R.string.console_ctrl_alt_delete),
                    stringResource(R.string.console_ctrl_alt_delete_detail),
                ) { onChoose(PowerAction.CtrlAltDelete) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.console_close))
            }
        },
    )
}

@Composable
private fun PowerChoice(title: String, detail: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConfirmPowerDialog(
    request: PendingPowerAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val action = request.action
    val (title, detail, confirmLabel) = when (action) {
        PowerAction.ShortPress -> Triple(
            stringResource(R.string.console_confirm_power_title),
            stringResource(R.string.console_confirm_power_detail),
            stringResource(R.string.console_confirm_power_action),
        )
        PowerAction.Reset -> Triple(
            stringResource(R.string.console_confirm_reset_title),
            stringResource(R.string.console_confirm_reset_detail),
            stringResource(R.string.console_confirm_reset_action),
        )
        is PowerAction.LongPress -> Triple(
            stringResource(R.string.console_confirm_force_off_title),
            pluralStringResource(
                R.plurals.console_confirm_force_off_detail,
                action.seconds,
                action.seconds,
            ),
            stringResource(R.string.console_confirm_force_off_action),
        )
        PowerAction.CtrlAltDelete -> {
            Triple(
                stringResource(R.string.console_confirm_ctrl_alt_delete_title),
                stringResource(R.string.console_confirm_ctrl_alt_delete_detail),
                stringResource(R.string.console_confirm_ctrl_alt_delete_action),
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        R.string.console_confirm_host_target,
                        request.destinationName,
                        request.destination.authority,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("power-confirmation-target"),
                )
                Text(detail)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("power-confirmation-action"),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.console_cancel))
            }
        },
    )
}

@Composable
private fun DisconnectConfirmationDialog(
    destinationName: String,
    authority: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.console_disconnect_title, destinationName))
        },
        text = {
            Text(stringResource(R.string.console_disconnect_detail, authority))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("disconnect-confirmation-action"),
            ) {
                Text(stringResource(R.string.console_disconnect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.console_cancel))
            }
        },
    )
}

@Composable
private fun MoreActionsDialog(
    onDismiss: () -> Unit,
    onReconnect: () -> Unit,
    onResetHid: () -> Unit,
    onDeviceInfo: () -> Unit,
    phase3Available: Boolean,
    onVirtualMedia: () -> Unit,
    onWakeOnLan: () -> Unit,
    administrationAvailable: Boolean,
    onAdministration: () -> Unit,
    operatorToolsAvailable: Boolean,
    onOperatorTools: () -> Unit,
    automationAvailable: Boolean,
    onAutomation: () -> Unit,
    picoClawAvailable: Boolean,
    onPicoClaw: () -> Unit,
    immersiveMode: Boolean,
    onImmersiveMode: () -> Unit,
    onDisconnect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.console_actions_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.console_reconnect_stream))
                }
                OutlinedButton(onClick = onResetHid, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Usb, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.console_reset_keyboard_mouse))
                }
                OutlinedButton(
                    onClick = onDeviceInfo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("device-info-action"),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.console_device_details))
                }
                if (phase3Available) {
                    OutlinedButton(
                        onClick = onVirtualMedia,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("phase3-virtual-media-action"),
                    ) {
                        Icon(Icons.Default.Usb, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.console_virtual_media))
                    }
                    OutlinedButton(
                        onClick = onWakeOnLan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("phase3-wol-action"),
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.console_wake_on_lan))
                    }
                }
                if (administrationAvailable) {
                    OutlinedButton(
                        onClick = onAdministration,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("administration-action"),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.console_administration))
                    }
                }
                if (operatorToolsAvailable) {
                    OutlinedButton(
                        onClick = onOperatorTools,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("operator-tools-action"),
                    ) {
                        Icon(Icons.Default.Keyboard, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.console_operator_tools))
                    }
                }
                if (automationAvailable) {
                    OutlinedButton(
                        onClick = onAutomation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("automation-action"),
                    ) {
                        Icon(Icons.Default.Keyboard, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.automation_title))
                    }
                }
                if (picoClawAvailable) {
                    OutlinedButton(
                        onClick = onPicoClaw,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("picoclaw-action"),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.picoclaw_action))
                    }
                }
                OutlinedButton(
                    onClick = onImmersiveMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("immersive-mode-action"),
                ) {
                    Icon(Icons.Default.FitScreen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (immersiveMode) {
                                R.string.console_exit_immersive
                            } else {
                                R.string.console_enter_immersive
                            },
                        ),
                    )
                }
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LinkOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.console_disconnect))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.console_close))
            }
        },
    )
}

internal enum class ConsoleControlsPresentation { BottomSheet, SideOverlay, SupportingPane }

internal fun consoleControlsPresentation(
    isExpandedWidth: Boolean,
    isCompactWidth: Boolean,
    heightDp: Float,
): ConsoleControlsPresentation = when {
    heightDp < COMPACT_PORTRAIT_MIN_HEIGHT_DP -> ConsoleControlsPresentation.SideOverlay
    isExpandedWidth -> ConsoleControlsPresentation.SupportingPane
    isCompactWidth -> ConsoleControlsPresentation.BottomSheet
    else -> ConsoleControlsPresentation.SideOverlay
}

private fun consoleSideSheetWidth(widthDp: Float): Float = when {
    widthDp >= MEDIUM_WIDTH_DP -> MEDIUM_SIDE_SHEET_WIDTH_DP
    else -> COMPACT_SIDE_SHEET_WIDTH_DP.coerceAtMost(widthDp * MAX_SIDE_SHEET_WIDTH_FRACTION)
}

@Composable
private fun Float.formatZoom(): String = stringResource(R.string.console_multiplier_format, this)

@Composable
private fun connectionLabel(state: ConnectionState): String = stringResource(
    when (state) {
        ConnectionState.Disconnected -> R.string.console_connection_disconnected
        ConnectionState.Connecting -> R.string.console_connection_connecting
        ConnectionState.Connected -> R.string.console_connection_connected
        ConnectionState.Degraded -> R.string.console_connection_degraded
        ConnectionState.Reconnecting -> R.string.console_connection_reconnecting
        ConnectionState.Failed -> R.string.console_connection_failed
    },
)

private const val MEDIUM_WIDTH_DP = 600f
private const val COMPACT_PORTRAIT_MIN_HEIGHT_DP = 480f
private const val COMPACT_SIDE_SHEET_WIDTH_DP = 216f
private const val MEDIUM_SIDE_SHEET_WIDTH_DP = 280f
private const val SUPPORTING_PANE_WIDTH_DP = 320
private const val MAX_SIDE_SHEET_WIDTH_FRACTION = 0.78f
private const val BOTTOM_SHEET_MAX_HEIGHT_FRACTION = 0.78f
private const val CONTROL_SCRIM_ALPHA = 0.32f
private val OFFLINE_UPDATE_MIME_TYPES = arrayOf(
    "application/gzip",
    "application/x-gzip",
    "application/octet-stream",
)

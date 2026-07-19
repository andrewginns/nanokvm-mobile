package org.nanokvm.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import org.nanokvm.mobile.R
import org.nanokvm.mobile.clipboard.ClipboardGateway
import org.nanokvm.mobile.clipboard.ClipboardPayload
import org.nanokvm.mobile.clipboard.ClipboardReadResult
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
import org.nanokvm.mobile.runtime.ApprovedOperatorDestination
import org.nanokvm.mobile.runtime.ApprovedPasteRequest
import org.nanokvm.mobile.runtime.ApprovedPhase3Destination
import org.nanokvm.mobile.runtime.ApprovedPicoClawDestination
import org.nanokvm.mobile.runtime.ConnectionState
import org.nanokvm.mobile.runtime.ConsoleCommandSink
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.NanoKvmAutomationFeatureOwner
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateFeatureOwner
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateGateway
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdatePhase
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateSource
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateUiState
import org.nanokvm.mobile.runtime.currentAutomationGateway
import org.nanokvm.mobile.runtime.currentOfflineUpdateGateway
import org.nanokvm.mobile.platform.NanoKvmOfflineUpdateDocumentSelectionResult
import org.nanokvm.mobile.platform.NanoKvmOfflineUpdateDocumentSource
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.RemotePastePhase
import org.nanokvm.mobile.runtime.VideoSurfaceSink
import org.nanokvm.mobile.runtime.PowerAction
import org.nanokvm.mobile.runtime.VideoSettings
import org.nanokvm.mobile.runtime.VideoTransportPreference
import org.nanokvm.mobile.ui.components.ConsoleKeyboard
import org.nanokvm.mobile.ui.components.ImmersiveModeEffect
import org.nanokvm.mobile.ui.components.PointerMode
import org.nanokvm.mobile.ui.components.RemoteViewport
import org.nanokvm.mobile.ui.components.ViewportAction
import org.nanokvm.mobile.ui.components.ViewportCommand
import org.nanokvm.mobile.ui.theme.LocalConsoleColorScheme
import org.nanokvm.protocol.NanoKvmCapability
import org.nanokvm.protocol.NanoKvmCapabilitySupport

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ConsoleScreen(
    profile: HostProfile,
    session: BackendSession,
    input: RemoteInputSink,
    videoSurface: VideoSurfaceSink,
    commands: ConsoleCommandSink,
    onDisconnect: () -> Unit,
    clipboardGateway: ClipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
    pendingSharedPaste: ClipboardPayload? = null,
    onSharedPasteConsumed: (ClipboardPayload) -> Unit = {},
    sensitiveWorkGeneration: Long = 0,
    scrollSensitivity: Float = DEFAULT_SCROLL_SENSITIVITY,
    onScrollSensitivityChange: (Float) -> Unit = {},
    mjpegFrameDetectionEnabled: Boolean = false,
    onMjpegFrameDetectionEnabledChange: (Boolean) -> Unit = {},
    passwordChangeInProgress: Boolean = false,
    canProtectPassword: Boolean = false,
    onPasswordChange: (
        destination: ApprovedAdministrationDestination,
        username: String,
        password: CharArray,
        saveProtectedCredential: Boolean,
    ) -> Unit = { _, _, password, _ -> password.fill('\u0000') },
) {
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val operatorState by commands.operatorState.collectAsState()
    val picoClawState by commands.picoClawState.collectAsState()
    val automationOwner = commands as? NanoKvmAutomationFeatureOwner
    val offlineUpdateOwner = commands as? NanoKvmOfflineUpdateFeatureOwner
    var pointerMode by rememberSaveable { mutableStateOf(PointerMode.Direct) }
    var keyboardVisible by rememberSaveable { mutableStateOf(false) }
    var keyboardLayout by rememberSaveable { mutableStateOf(KeyboardLayout.Us) }
    var viewNavigationVisible by rememberSaveable { mutableStateOf(true) }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var viewportCommandSequence by remember { mutableIntStateOf(0) }
    var viewportCommand by remember { mutableStateOf<ViewportCommand?>(null) }
    var showVideoSettings by rememberSaveable { mutableStateOf(false) }
    var showScrollSettings by rememberSaveable { mutableStateOf(false) }
    var showPowerMenu by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }
    var showDeviceInfo by rememberSaveable { mutableStateOf(false) }
    var showVirtualMedia by rememberSaveable { mutableStateOf(false) }
    var showWakeOnLan by rememberSaveable { mutableStateOf(false) }
    var showAdministration by rememberSaveable { mutableStateOf(false) }
    var showOfflineUpdate by remember { mutableStateOf(false) }
    var showAutomation by rememberSaveable { mutableStateOf(false) }
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
            showOfflineUpdate = false
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
        showAdministration = false
        offlineUpdateOwner?.setOfflineUpdateSurfaceVisible(true)
        showOfflineUpdate = offlineUpdateOwner != null
        if (offlineUpdateOwner == null) {
            offlineDocumentHandoff.clear()
            offlineDocumentPending = false
        }
    }
    val closeOfflineUpdate = {
        offlinePickerTarget = null
        offlineDocumentHandoff.clear()
        offlineDocumentPending = false
        showOfflineUpdate = false
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
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(commands, uriHandler) {
        commands.externalNavigation.collect { request ->
            request.open(uriHandler::openUri)
        }
    }
    var showOperatorTools by rememberSaveable { mutableStateOf(false) }
    var showPicoClaw by rememberSaveable { mutableStateOf(false) }
    var immersiveMode by rememberSaveable { mutableStateOf(false) }
    var pendingPhase3Action by remember { mutableStateOf<PendingPhase3Action?>(null) }
    var pasteRequest by remember { mutableStateOf<PendingClipboardPaste?>(null) }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var revealSensitivePaste by remember { mutableStateOf(false) }
    var observedSensitiveWorkGeneration by remember { mutableStateOf(sensitiveWorkGeneration) }
    var pendingPowerCode by rememberSaveable { mutableIntStateOf(NO_PENDING_POWER) }
    val pasteUnavailableMessage = stringResource(R.string.console_clipboard_unavailable)
    val pasteEmptyMessage = stringResource(R.string.console_clipboard_empty)
    val pasteRejectedMessage = stringResource(R.string.console_clipboard_rejected)
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
    val phase3SurfaceVisible = showVirtualMedia || showWakeOnLan
    DisposableEffect(phase3SurfaceVisible, commands) {
        if (phase3SurfaceVisible) commands.setPhase3SurfaceVisible(true)
        onDispose {
            if (phase3SurfaceVisible) commands.setPhase3SurfaceVisible(false)
        }
    }
    DisposableEffect(showAdministration, commands) {
        if (showAdministration) commands.setAdministrationSurfaceVisible(true)
        onDispose {
            commands.setAdministrationSurfaceVisible(false)
        }
    }
    DisposableEffect(showOfflineUpdate, offlineUpdateOwner) {
        if (showOfflineUpdate) offlineUpdateOwner?.setOfflineUpdateSurfaceVisible(true)
        onDispose {
            offlineUpdateOwner?.setOfflineUpdateSurfaceVisible(false)
        }
    }
    DisposableEffect(offlineDocumentHandoff) {
        onDispose { offlineDocumentHandoff.close() }
    }
    DisposableEffect(showOperatorTools, commands) {
        if (showOperatorTools) commands.setOperatorSurfaceVisible(true)
        onDispose {
            commands.setOperatorSurfaceVisible(false)
        }
    }
    DisposableEffect(showAutomation, automationOwner) {
        if (showAutomation) automationOwner?.setAutomationSurfaceVisible(true)
        onDispose {
            automationOwner?.setAutomationSurfaceVisible(false)
        }
    }
    DisposableEffect(showPicoClaw, commands) {
        if (showPicoClaw) commands.setPicoClawSurfaceVisible(true)
        onDispose {
            commands.setPicoClawSurfaceVisible(false)
        }
    }
    LaunchedEffect(session.connection, session.sessionGeneration) {
        pendingPhase3Action = null
        if (session.connection != ConnectionState.Connected) {
            showVirtualMedia = false
            showWakeOnLan = false
            showDeviceInfo = false
            showAdministration = false
            if (
                session.connection == ConnectionState.Failed ||
                (!offlineDocumentPending && offlinePickerTarget == null)
            ) {
                closeOfflineUpdate()
            }
            showOperatorTools = false
            showPicoClaw = false
            showAutomation = false
        }
    }
    val offlineUpdateGateway = if (showOfflineUpdate) {
        offlineUpdateOwner?.currentOfflineUpdateGateway()
    } else {
        null
    }
    LaunchedEffect(
        showOfflineUpdate,
        session.connection,
        session.sessionGeneration,
        offlineDocumentSequence,
        offlineUpdateGateway,
    ) {
        if (
            showOfflineUpdate && offlineDocumentPending &&
            session.connection == ConnectionState.Connected && offlineUpdateGateway != null
        ) {
            if (offlineDocumentHandoff.deliverTo(offlineUpdateGateway)) {
                offlineDocumentPending = false
            }
        }
    }
    val requestClipboardPaste = {
        if (session.connection != ConnectionState.Connected) {
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
                is ClipboardReadResult.Rejected -> pasteError = pasteRejectedMessage
                ClipboardReadResult.Unavailable -> pasteError = pasteUnavailableMessage
            }
        }
    }
    LaunchedEffect(session.connection, session.sessionGeneration, profile.id) {
        val request = pasteRequest ?: return@LaunchedEffect
        if (
            session.connection != ConnectionState.Connected ||
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
            showVirtualMedia = false
            showWakeOnLan = false
            showDeviceInfo = false
            showAdministration = false
            if (offlineResultSensitiveGeneration != sensitiveWorkGeneration) {
                offlineDocumentHandoff.clear()
                offlineDocumentPending = false
                showOfflineUpdate = false
            }
            showOperatorTools = false
            showPicoClaw = false
            showAutomation = false
            observedSensitiveWorkGeneration = sensitiveWorkGeneration
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
        if (session.connection == ConnectionState.Connected && pasteRequest == null) {
            revealSensitivePaste = false
            pasteRequest = PendingClipboardPaste(
                confirmation = PasteConfirmationRequest(sharedPayload, currentPasteTarget),
                keyboardLayout = keyboardLayout,
            )
            onSharedPasteConsumed(sharedPayload)
        }
    }
    val togglePointerMode = {
        input.releaseAllInput()
        pointerMode = if (pointerMode == PointerMode.Direct) {
            // A neutral relative report also switches the backend away from its last absolute point.
            input.moveRelative(0, 0)
            PointerMode.Trackpad
        } else {
            PointerMode.Direct
        }
    }
    val toggleKeyboard = {
        val showing = !keyboardVisible
        keyboardVisible = showing
        if (showing) {
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
    BackHandler(enabled = controlsExpanded) { controlsExpanded = false }
    BackHandler(enabled = immersiveMode && !controlsExpanded) { immersiveMode = false }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
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
            session = session,
            input = input,
            videoSurface = videoSurface,
            pointerMode = pointerMode,
            fitRequest = fitRequest,
            scrollSensitivity = scrollSensitivity,
            keyboardVisible = keyboardVisible,
            keyboardLayout = keyboardLayout,
            viewNavigationVisible = viewNavigationVisible,
            viewportCommand = viewportCommand,
            onZoomChanged = { zoom = it },
            onKeyboard = toggleKeyboard,
            onKeyboardLayoutChange = { keyboardLayout = it },
            onClipboard = {
                if (session.pasteProgress != null) {
                    commands.cancelPaste()
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
            onCtrlAltDelete = { pendingPowerCode = POWER_CTRL_ALT_DELETE },
        )
        val controlsContent: @Composable (Modifier) -> Unit = { controlsModifier ->
            ConsoleControlContent(
                profile = profile,
                session = session,
                pointerMode = pointerMode,
                keyboardVisible = keyboardVisible,
                viewNavigationVisible = viewNavigationVisible,
                zoom = zoom,
                scrollSensitivity = scrollSensitivity,
                onClose = { controlsExpanded = false },
                onKeyboard = toggleKeyboard,
                onClipboard = {
                    controlsExpanded = false
                    if (session.pasteProgress != null) {
                        commands.cancelPaste()
                    } else {
                        requestClipboardPaste()
                    }
                },
                onPointerMode = togglePointerMode,
                onViewNavigation = toggleViewNavigation,
                onMouseClick = { button -> input.sendOneShotMouseClick(button) },
                onFit = {
                    fitRequest++
                    controlsExpanded = false
                },
                onScrollSettings = {
                    controlsExpanded = false
                    showScrollSettings = true
                },
                onVideo = {
                    controlsExpanded = false
                    showVideoSettings = true
                },
                onPower = {
                    controlsExpanded = false
                    showPowerMenu = true
                },
                onMore = {
                    controlsExpanded = false
                    showMoreMenu = true
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
                    if (picoClawState.manualInputBlockedOrUncertain) {
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

    if (showVideoSettings) {
        VideoSettingsDialog(
            initial = session.videoSettings,
            initialMjpegFrameDetectionEnabled = mjpegFrameDetectionEnabled,
            onDismiss = { showVideoSettings = false },
            onApply = { settings, frameDetectionEnabled ->
                if (settings != session.videoSettings) commands.updateVideo(settings)
                if (frameDetectionEnabled != mjpegFrameDetectionEnabled) {
                    onMjpegFrameDetectionEnabledChange(frameDetectionEnabled)
                }
                showVideoSettings = false
            },
        )
    }
    if (showScrollSettings) {
        ScrollSettingsDialog(
            initialSensitivity = scrollSensitivity,
            onDismiss = { showScrollSettings = false },
            onApply = {
                onScrollSensitivityChange(it)
                showScrollSettings = false
            },
        )
    }
    if (showPowerMenu) {
        PowerMenuDialog(
            onDismiss = { showPowerMenu = false },
            onChoose = {
                showPowerMenu = false
                pendingPowerCode = it.toSaveableCode()
            },
        )
    }
    pendingPowerCode.toPowerActionOrNull()?.let { action ->
        ConfirmPowerDialog(
            action = action,
            onDismiss = { pendingPowerCode = NO_PENDING_POWER },
            onConfirm = {
                commands.power(action)
                pendingPowerCode = NO_PENDING_POWER
            },
        )
    }
    if (showMoreMenu) {
        MoreActionsDialog(
            onDismiss = { showMoreMenu = false },
            onReconnect = {
                showMoreMenu = false
                input.releaseAllInput()
                commands.reconnect()
            },
            onResetHid = {
                showMoreMenu = false
                input.releaseAllInput()
                commands.resetHid()
            },
            onDeviceInfo = {
                showMoreMenu = false
                showDeviceInfo = true
            },
            onVirtualMedia = {
                showMoreMenu = false
                showVirtualMedia = true
            },
            onWakeOnLan = {
                showMoreMenu = false
                showWakeOnLan = true
            },
            onAdministration = {
                showMoreMenu = false
                showAdministration = true
            },
            onOperatorTools = {
                showMoreMenu = false
                showOperatorTools = true
            },
            automationAvailable = automationOwner != null,
            onAutomation = {
                showMoreMenu = false
                automationOwner?.setAutomationSurfaceVisible(true)
                showAutomation = true
            },
            onPicoClaw = {
                showMoreMenu = false
                showPicoClaw = true
            },
            immersiveMode = immersiveMode,
            onImmersiveMode = {
                showMoreMenu = false
                immersiveMode = !immersiveMode
            },
            onDisconnect = {
                showMoreMenu = false
                onDisconnect()
            },
        )
    }
    if (showDeviceInfo) {
        DeviceInfoDialog(
            session = session,
            onDismiss = { showDeviceInfo = false },
        )
    }
    if (showAdministration) {
        AdministrationDialog(
            destinationLabel = profile.name.ifBlank { profile.authority },
            destination = currentAdministrationDestination,
            state = session.administration,
            commands = commands,
            passwordChangeInProgress = passwordChangeInProgress,
            canProtectPassword = canProtectPassword,
            onPasswordChange = onPasswordChange,
            offlineUpdateAvailable = offlineUpdateOwner != null,
            onOfflineUpdate = {
                showAdministration = false
                offlineUpdateOwner?.setOfflineUpdateSurfaceVisible(true)
                showOfflineUpdate = offlineUpdateOwner != null
            },
            onDismiss = { showAdministration = false },
        )
    }
    if (showOfflineUpdate) {
        OfflineUpdateGatewayDialog(
            gateway = offlineUpdateGateway,
            onChoosePackage = chooseOfflineUpdateDocument,
            onDismiss = closeOfflineUpdate,
        )
    }
    if (showOperatorTools) {
        OperatorDialog(
            destinationLabel = profile.name.ifBlank { profile.authority },
            destination = currentOperatorDestination,
            state = operatorState,
            output = commands.operatorOutput,
            commands = commands,
            onDismiss = { showOperatorTools = false },
        )
    }
    if (showAutomation) {
        val gateway = automationOwner?.currentAutomationGateway()
        if (gateway != null) {
            AutomationDialog(
                destinationLabel = profile.name.ifBlank { profile.authority },
                gateway = gateway,
                onDismiss = { showAutomation = false },
                leaderKeyAvailable = session.capabilities
                    ?.get(NanoKvmCapability.HID_LEADER_KEY) !is
                    NanoKvmCapabilitySupport.Unsupported,
            )
        } else {
            LaunchedEffect(showAutomation, session.sessionGeneration) {
                showAutomation = false
            }
        }
    }
    if (showPicoClaw) {
        PicoClawDialog(
            destinationLabel = profile.name.ifBlank { profile.authority },
            destination = currentPicoClawDestination,
            state = picoClawState,
            commands = commands,
            onDismiss = { showPicoClaw = false },
        )
    }
    if (showVirtualMedia) {
        VirtualMediaDialog(
            state = session.phase3,
            onDismiss = { showVirtualMedia = false },
            onRefresh = commands::refreshPhase3,
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
    if (showWakeOnLan) {
        WakeOnLanDialog(
            state = session.phase3,
            onDismiss = { showWakeOnLan = false },
            onRefresh = commands::refreshPhase3,
            onWake = { macAddress ->
                pendingPhase3Action = PendingPhase3Action.SendWakeOnLan(macAddress)
            },
            onRename = { target, name ->
                commands.renamePhase3WakeOnLanTarget(
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
        ConfirmPhase3ActionDialog(
            action = action,
            destinationLabel = profile.name.ifBlank { profile.authority },
            destination = currentPhase3Destination,
            onDismiss = { pendingPhase3Action = null },
            onConfirm = {
                when (action) {
                    is PendingPhase3Action.MountImage -> commands.mountPhase3Image(
                        currentPhase3Destination,
                        action.image.id,
                        action.mode,
                    )
                    PendingPhase3Action.RestorePhysicalMedia ->
                        commands.restorePhase3PhysicalMedia(currentPhase3Destination)
                    is PendingPhase3Action.DeleteImage -> commands.deletePhase3Image(
                        currentPhase3Destination,
                        action.image.id,
                    )
                    is PendingPhase3Action.SetDiskEnabled -> commands.setPhase3DiskEnabled(
                        currentPhase3Destination,
                        action.enabled,
                    )
                    is PendingPhase3Action.SetNetworkEnabled ->
                        commands.setPhase3NetworkEnabled(
                            currentPhase3Destination,
                            action.enabled,
                        )
                    is PendingPhase3Action.SetHidMode -> commands.setPhase3HidMode(
                        currentPhase3Destination,
                        action.selection,
                    )
                    is PendingPhase3Action.StartImageTransfer ->
                        commands.startPhase3ImageTransfer(
                            currentPhase3Destination,
                            action.sourceUrl,
                        )
                    is PendingPhase3Action.SendWakeOnLan -> commands.sendPhase3WakeOnLan(
                        currentPhase3Destination,
                        action.macAddress,
                    )
                    is PendingPhase3Action.DeleteWakeOnLan ->
                        commands.deletePhase3WakeOnLanTarget(
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
                val stillConnected = session.connection == ConnectionState.Connected
                val confirmation = request.confirmation
                val stillBound = confirmation.remainsBoundTo(currentPasteTarget)
                if (stillConnected && stillBound && confirmation.payload.fitsServerPasteLimit) {
                    commands.pasteText(
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
    val state by gateway.state.collectAsState()
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

private data class ConsoleContentState(
    val session: BackendSession,
    val input: RemoteInputSink,
    val videoSurface: VideoSurfaceSink,
    val pointerMode: PointerMode,
    val fitRequest: Int,
    val scrollSensitivity: Float,
    val keyboardVisible: Boolean,
    val keyboardLayout: KeyboardLayout,
    val viewNavigationVisible: Boolean,
    val viewportCommand: ViewportCommand?,
    val onZoomChanged: (Float) -> Unit,
    val onKeyboard: () -> Unit,
    val onKeyboardLayoutChange: (KeyboardLayout) -> Unit,
    val onClipboard: () -> Unit,
    val onOpenControls: () -> Unit,
    val onHideKeyboard: () -> Unit,
    val onViewportAction: (ViewportAction) -> Unit,
    val onCtrlAltDelete: () -> Unit,
)

@Composable
private fun ConsoleMainContent(state: ConsoleContentState) {
    Box(Modifier.fillMaxSize()) {
        ConsoleBody(
            modifier = Modifier.fillMaxSize(),
            session = state.session,
            input = state.input,
            videoSurface = state.videoSurface,
            pointerMode = state.pointerMode,
            fitRequest = state.fitRequest,
            scrollSensitivity = state.scrollSensitivity,
            keyboardVisible = state.keyboardVisible,
            keyboardLayout = state.keyboardLayout,
            viewNavigationVisible = state.viewNavigationVisible,
            viewportCommand = state.viewportCommand,
            onZoomChanged = state.onZoomChanged,
            onKeyboard = state.onKeyboard,
            onKeyboardLayoutChange = state.onKeyboardLayoutChange,
            onClipboard = state.onClipboard,
            onOpenControls = state.onOpenControls,
            onHideKeyboard = state.onHideKeyboard,
            onViewportAction = state.onViewportAction,
            onCtrlAltDelete = state.onCtrlAltDelete,
        )
        if (!state.viewNavigationVisible) {
            ConsoleQuickActions(
                session = state.session,
                keyboardVisible = state.keyboardVisible,
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
    session: BackendSession,
    input: RemoteInputSink,
    videoSurface: VideoSurfaceSink,
    pointerMode: PointerMode,
    fitRequest: Int,
    scrollSensitivity: Float,
    keyboardVisible: Boolean,
    keyboardLayout: KeyboardLayout,
    viewNavigationVisible: Boolean,
    viewportCommand: ViewportCommand?,
    onZoomChanged: (Float) -> Unit,
    onKeyboard: () -> Unit,
    onKeyboardLayoutChange: (KeyboardLayout) -> Unit,
    onClipboard: () -> Unit,
    onOpenControls: () -> Unit,
    onHideKeyboard: () -> Unit,
    onViewportAction: (ViewportAction) -> Unit,
    onCtrlAltDelete: () -> Unit,
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
                fitRequest = fitRequest,
                scrollSensitivity = scrollSensitivity,
                viewNavigationVisible = viewNavigationVisible,
                keyboardVisible = keyboardVisible,
                viewportCommand = viewportCommand,
                onZoomChanged = onZoomChanged,
                navigationActions = {
                    ConsoleQuickActions(
                        session = session,
                        keyboardVisible = keyboardVisible,
                        onKeyboard = onKeyboard,
                        onClipboard = onClipboard,
                        onOpenControls = onOpenControls,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (session.connection != ConnectionState.Connected) {
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
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(connectionLabel(session.connection), style = MaterialTheme.typography.titleMedium)
                        Text(
                            session.message ?: stringResource(R.string.console_waiting_for_video),
                            color = consoleColors.onSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        ConsoleKeyboard(
            input = input,
            visible = keyboardVisible,
            releaseGeneration = session.inputReleaseGeneration,
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
    val sessionSummary = sessionStatusSummary(session)
    val connectionStatus = connectionLabel(session.connection)
    val statusDescription = listOf(connectionStatus, sessionSummary)
        .filter(String::isNotBlank)
        .joinToString(stringResource(R.string.console_status_separator))
    Row(
        modifier = modifier.testTag("console-quick-actions"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onKeyboard,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = keyboardActionDescription
                    stateDescription = keyboardStateDescription
                },
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = consoleColors.controlSurfaceElevated.copy(alpha = 0.96f),
                contentColor = consoleColors.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (keyboardVisible) consoleColors.active else consoleColors.onSurface,
                    )
                }
            }
        }
        IconButton(
            onClick = onClipboard,
            enabled = pasteProgress != null || session.connection == ConnectionState.Connected,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = clipboardActionDescription }
                .testTag("console-clipboard-action"),
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = consoleColors.controlSurfaceElevated.copy(alpha = 0.96f),
                contentColor = consoleColors.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (pasteProgress == null) Icons.Default.ContentPaste else Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (pasteProgress == null) consoleColors.onSurface else consoleColors.active,
                    )
                }
            }
        }
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onOpenControls,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = openControlsDescription
                        stateDescription = statusDescription
                    },
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = consoleColors.controlSurfaceElevated.copy(alpha = 0.96f),
                    contentColor = consoleColors.onSurface,
                    tonalElevation = 0.dp,
                    shadowElevation = 4.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp)
                    .size(18.dp)
                    .semantics { contentDescription = connectionStatus }
                    .testTag("console-quick-connection-status"),
                shape = MaterialTheme.shapes.extraLarge,
                color = consoleColors.controlSurfaceElevated,
                contentColor = sessionStatusColor(session),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = sessionStatusIcon(session.connection),
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

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
                        payload.characterCount,
                        payload.utf8ByteCount,
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
                        color = if (warning == ClipboardTextWarning.ExceedsServerPasteLimit) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
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
                enabled = payload.fitsServerPasteLimit,
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
    ClipboardTextWarning.ExceedsServerPasteLimit -> R.string.console_clipboard_warning_too_long
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
        tonalElevation = 0.dp,
        content = { content() },
    )
}

@Composable
private fun ConsoleControlContent(
    profile: HostProfile,
    session: BackendSession,
    pointerMode: PointerMode,
    keyboardVisible: Boolean,
    viewNavigationVisible: Boolean,
    zoom: Float,
    scrollSensitivity: Float,
    onClose: () -> Unit,
    onKeyboard: () -> Unit,
    onClipboard: () -> Unit,
    onPointerMode: () -> Unit,
    onViewNavigation: () -> Unit,
    onMouseClick: (MouseButton) -> Unit,
    onFit: () -> Unit,
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
        SheetControl(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            stringResource(R.string.console_close_controls),
            false,
            onClose,
        )
        SideSessionStatus(profile, session)
        SheetControl(
            Icons.Default.OpenWith,
            stringResource(
                R.string.console_scroll_sensitivity_value,
                scrollSensitivity.formatZoom(),
            ),
            false,
            onScrollSettings,
        )
        SheetControl(
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
        SheetControl(
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
            pasteProgress != null,
            onClipboard,
        )
        SheetControl(
            if (pointerMode == PointerMode.Direct) Icons.Default.TouchApp else Icons.Default.Mouse,
            stringResource(
                if (pointerMode == PointerMode.Direct) {
                    R.string.console_pointer_direct
                } else {
                    R.string.console_pointer_trackpad
                },
            ),
            pointerMode == PointerMode.Trackpad,
            onPointerMode,
        )
        SheetControl(
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
        SheetControl(
            Icons.Default.FitScreen,
            if (zoom > 1.01f) {
                stringResource(R.string.console_fit_view_value, zoom.formatZoom())
            } else {
                stringResource(R.string.console_fit_view)
            },
            false,
            onFit,
        )
        SheetControl(
            Icons.Default.Tune,
            stringResource(R.string.console_video_settings),
            false,
            onVideo,
        )
        SheetControl(
            Icons.Default.PowerSettingsNew,
            stringResource(R.string.console_power_controls),
            false,
            onPower,
        )
        SheetControl(
            Icons.Default.MoreVert,
            stringResource(R.string.console_more_actions),
            false,
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
        session.message?.takeIf(String::isNotBlank)?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = consoleColors.onSurfaceMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun sessionStatusIcon(connection: ConnectionState): ImageVector = when (connection) {
    ConnectionState.Connected -> Icons.Default.CheckCircle
    ConnectionState.Connecting, ConnectionState.Reconnecting -> Icons.Default.Refresh
    ConnectionState.Failed -> Icons.Default.ErrorOutline
    ConnectionState.Disconnected -> Icons.Default.LinkOff
}

@Composable
private fun sessionStatusColor(session: BackendSession): Color {
    val consoleColors = LocalConsoleColorScheme.current
    return when (session.connection) {
        ConnectionState.Connected -> consoleColors.connected
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
    return listOfNotNull(session.streamLabel, frames, latency, dropped, stalls)
        .joinToString(stringResource(R.string.console_status_separator))
}

@Composable
private fun SheetControl(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val consoleColors = LocalConsoleColorScheme.current
    val selectionState = stringResource(
        if (selected) R.string.console_selected else R.string.console_not_selected,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
                stateDescription = selectionState
            }
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
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) consoleColors.active else consoleColors.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
                ChoiceRow(
                    stringResource(R.string.console_h264_bitrate),
                    listOf(1_000, 2_000, 3_000, 5_000),
                    bitrate,
                    { stringResource(R.string.console_bitrate_value, it / 1_000) },
                ) { bitrate = it }
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
                ChoiceRow(
                    stringResource(R.string.console_h264_gop),
                    listOf(10, 30, 50, 100),
                    gop,
                    { stringResource(R.string.console_h264_gop_value, it) },
                ) { gop = it }
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
private fun ConfirmPowerDialog(action: PowerAction, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, detail) = when (action) {
        PowerAction.ShortPress -> stringResource(R.string.console_confirm_power_title) to
            stringResource(R.string.console_confirm_power_detail)
        PowerAction.Reset -> stringResource(R.string.console_confirm_reset_title) to
            stringResource(R.string.console_confirm_reset_detail)
        is PowerAction.LongPress -> stringResource(R.string.console_confirm_force_off_title) to
            pluralStringResource(
                R.plurals.console_confirm_force_off_detail,
                action.seconds,
                action.seconds,
            )
        PowerAction.CtrlAltDelete -> {
            stringResource(R.string.console_confirm_ctrl_alt_delete_title) to
                stringResource(R.string.console_confirm_ctrl_alt_delete_detail)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.console_send)) }
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
    onVirtualMedia: () -> Unit,
    onWakeOnLan: () -> Unit,
    onAdministration: () -> Unit,
    onOperatorTools: () -> Unit,
    automationAvailable: Boolean,
    onAutomation: () -> Unit,
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
    isExpandedWidth -> ConsoleControlsPresentation.SupportingPane
    isCompactWidth && heightDp >= COMPACT_PORTRAIT_MIN_HEIGHT_DP -> {
        ConsoleControlsPresentation.BottomSheet
    }
    else -> ConsoleControlsPresentation.SideOverlay
}

private fun consoleSideSheetWidth(widthDp: Float): Float = when {
    widthDp >= MEDIUM_WIDTH_DP -> MEDIUM_SIDE_SHEET_WIDTH_DP
    else -> COMPACT_SIDE_SHEET_WIDTH_DP.coerceAtMost(widthDp * MAX_SIDE_SHEET_WIDTH_FRACTION)
}

@Composable
private fun Float.formatZoom(): String = stringResource(R.string.console_multiplier_format, this)

private fun PowerAction.toSaveableCode(): Int = when (this) {
    PowerAction.ShortPress -> POWER_SHORT_PRESS
    PowerAction.Reset -> POWER_RESET
    is PowerAction.LongPress -> POWER_LONG_PRESS
    PowerAction.CtrlAltDelete -> POWER_CTRL_ALT_DELETE
}

private fun Int.toPowerActionOrNull(): PowerAction? = when (this) {
    POWER_SHORT_PRESS -> PowerAction.ShortPress
    POWER_RESET -> PowerAction.Reset
    POWER_LONG_PRESS -> PowerAction.LongPress()
    POWER_CTRL_ALT_DELETE -> PowerAction.CtrlAltDelete
    else -> null
}

@Composable
private fun connectionLabel(state: ConnectionState): String = stringResource(
    when (state) {
        ConnectionState.Disconnected -> R.string.console_connection_disconnected
        ConnectionState.Connecting -> R.string.console_connection_connecting
        ConnectionState.Connected -> R.string.console_connection_connected
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
private const val NO_PENDING_POWER = 0
private const val POWER_SHORT_PRESS = 1
private const val POWER_RESET = 2
private const val POWER_LONG_PRESS = 3
private const val POWER_CTRL_ALT_DELETE = 4

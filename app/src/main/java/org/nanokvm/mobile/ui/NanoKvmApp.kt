package org.nanokvm.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.nanokvm.mobile.R
import org.nanokvm.mobile.clipboard.ClipboardGateway
import org.nanokvm.mobile.security.CredentialAuthenticator
import org.nanokvm.mobile.ui.screens.CertificateReviewScreen
import org.nanokvm.mobile.ui.screens.ConsoleScreen
import org.nanokvm.mobile.ui.screens.ProfileEditorScreen
import org.nanokvm.mobile.ui.screens.ProfilesScreen
import org.nanokvm.mobile.ui.theme.NanoKvmConsoleTheme
import org.nanokvm.mobile.ui.theme.NanoKvmTheme
import org.nanokvm.mobile.ui.theme.shouldUseDarkTheme

@Composable
fun NanoKvmApp(
    viewModel: AppViewModel,
    credentialAuthenticator: CredentialAuthenticator,
    clipboardGateway: ClipboardGateway,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val pendingNotice = state.pendingAppNotices.firstOrNull()
    val noticeText = pendingNotice?.content?.displayText()

    NanoKvmTheme(
        themeMode = state.themeMode,
        useDynamicColor = state.useDynamicColor,
    ) {
        SystemBarAppearance(
            useDarkIcons = !shouldUseDarkTheme(state.themeMode) && state.screen !is AppScreen.Console,
        )

        // The profile catalogue (or its actionable recovery state) is the first useful screen.
        ReportDrawnWhen { state.profileCatalogResolved }
        val consoleColors = NanoKvmConsoleTheme.colors

        DisposableEffect(viewModel) {
            onDispose { viewModel.releaseAllInput() }
        }
        DisposableEffect(credentialAuthenticator) {
            onDispose { credentialAuthenticator.cancel() }
        }

        LaunchedEffect(state.credentialPrompt?.id, credentialAuthenticator) {
            state.credentialPrompt?.let(credentialAuthenticator::authenticate)
        }

        LaunchedEffect(pendingNotice?.id, lifecycleOwner, snackbarHostState, viewModel) {
            pendingNotice?.let { notice ->
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    snackbarHostState.showSnackbar(noticeText.orEmpty())
                    viewModel.acknowledgeNotice(notice.id)
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = if (state.screen is AppScreen.Console) {
                consoleColors.canvas
            } else {
                MaterialTheme.colorScheme.background
            },
            contentColor = if (state.screen is AppScreen.Console) {
                consoleColors.onSurface
            } else {
                MaterialTheme.colorScheme.onBackground
            },
        ) { outerPadding ->
            Box(Modifier.fillMaxSize().padding(outerPadding)) {
                when (val screen = state.screen) {
                    AppScreen.Profiles -> ProfilesScreen(
                        profiles = state.profiles,
                        profileCatalogResolved = state.profileCatalogResolved,
                        savedPasswordProfileIds = state.savedPasswordProfileIds,
                        profileStorageIssue = state.profileStorageIssue,
                        profileStorageBusy = state.profileStorageBusy,
                        passwordEntryProfile = state.passwordEntryProfile,
                        canSavePassword = credentialAuthenticator.canProtectPasswords,
                        themeMode = state.themeMode,
                        useDynamicColor = state.useDynamicColor,
                        dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onThemeModeChange = viewModel::setThemeMode,
                        onUseDynamicColorChange = viewModel::setUseDynamicColor,
                        onAdd = viewModel::addProfile,
                        onEdit = viewModel::editProfile,
                        onPrepareConnection = viewModel::prepareConnection,
                        onSubmitPassword = viewModel::submitPassword,
                        onDismissPassword = viewModel::dismissPasswordEntry,
                        onRemoveSavedCredential = viewModel::removeSavedCredential,
                        onResetProfileStorage = viewModel::resetProfileStorage,
                        onRetryProfileStorage = viewModel::retryProfileStorage,
                    )
                    is AppScreen.EditProfile -> ProfileEditorScreen(
                        initial = screen.profile,
                        isNew = screen.isNew,
                        mutation = state.profileMutation,
                        hasSavedPassword = screen.profile.id in state.savedPasswordProfileIds,
                        onSave = viewModel::saveProfile,
                        onDelete = if (screen.isNew) null else viewModel::deleteProfile,
                        onRemoveSavedPassword = viewModel::removeSavedCredential,
                        onForgetCertificate = viewModel::forgetProfileCertificate,
                        onCancel = viewModel::cancelToProfiles,
                    )
                    is AppScreen.Connecting -> ConnectingScreen(screen.profile.name)
                    is AppScreen.ReviewCertificate -> CertificateReviewScreen(
                        profile = screen.profile,
                        certificate = screen.certificate,
                        onTrustOnce = { viewModel.trustCertificate(persist = false) },
                        onTrustAndRemember = { viewModel.trustCertificate(persist = true) },
                        onCancel = viewModel::cancelToProfiles,
                    )
                    is AppScreen.Console -> ConsoleScreen(
                        profile = screen.profile,
                        session = state.backendSession,
                        scrollSensitivity = state.scrollSensitivity,
                        input = viewModel.remoteInput,
                        videoSurface = viewModel.videoSurface,
                        features = viewModel.consoleFeatures,
                        sessionDraftOwner = viewModel.consoleSessionDraftOwner,
                        clipboardGateway = clipboardGateway,
                        pendingSharedPaste = state.pendingSharedPaste,
                        onSharedPasteConsumed = viewModel::consumeSharedPlainText,
                        sensitiveWorkGeneration = state.sensitiveWorkGeneration,
                        onScrollSensitivityChange = viewModel::setScrollSensitivity,
                        mjpegFrameDetectionEnabled = state.mjpegFrameDetectionEnabled,
                        onMjpegFrameDetectionEnabledChange =
                            viewModel::setMjpegFrameDetectionEnabled,
                        passwordChangeInProgress = state.passwordChangeInProgress,
                        canProtectPassword = credentialAuthenticator.canProtectPasswords,
                        onPasswordChange = viewModel::changeAdministrationPassword,
                        onDisconnect = viewModel::disconnect,
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 37) {
            LocalNetworkPermissionHost(
                state = state.localNetworkPermission,
                onBeginRequest = viewModel::beginLocalNetworkPermissionRequest,
                onResult = viewModel::onLocalNetworkPermissionResult,
                onRetry = viewModel::retryLocalNetworkPermission,
                onRefresh = viewModel::refreshLocalNetworkAccess,
                onDismiss = viewModel::dismissLocalNetworkPermission,
            )
        }

        BackHandler(enabled = state.screen !is AppScreen.Profiles) {
            if (state.screen is AppScreen.Console) {
                viewModel.disconnect()
            } else {
                viewModel.cancelToProfiles()
            }
        }
    }
}

@Composable
private fun SystemBarAppearance(useDarkIcons: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    SideEffect {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
    }
}

@Composable
@RequiresApi(37)
private fun LocalNetworkPermissionHost(
    state: LocalNetworkPermissionUiState?,
    onBeginRequest: () -> Unit,
    onResult: (granted: Boolean, canRequestAgain: Boolean) -> Unit,
    onRetry: () -> Unit,
    onRefresh: (canRequestAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onResult(
            granted,
            activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == true,
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefresh(
                    activity?.shouldShowRequestPermissionRationale(
                        Manifest.permission.ACCESS_LOCAL_NETWORK,
                    ) == true,
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (state == null) return

    when (state) {
        is LocalNetworkPermissionUiState.Rationale -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.local_network_permission_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.local_network_permission_rationale,
                        state.profile.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onBeginRequest()
                        permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    },
                ) {
                    Text(stringResource(R.string.local_network_permission_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.local_network_permission_not_now))
                }
            },
        )
        is LocalNetworkPermissionUiState.Denied -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.local_network_permission_denied_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.canRequestAgain) {
                            R.string.local_network_permission_denied_retry
                        } else {
                            R.string.local_network_permission_denied_settings
                        },
                        state.profile.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (state.canRequestAgain) {
                            onRetry()
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (state.canRequestAgain) {
                                R.string.local_network_permission_try_again
                            } else {
                                R.string.local_network_permission_open_settings
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.local_network_permission_cancel))
                }
            },
        )
        is LocalNetworkPermissionUiState.Requesting -> Unit
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ConnectingScreen(profileName: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.connecting_to_profile, profileName),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.connecting_status),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

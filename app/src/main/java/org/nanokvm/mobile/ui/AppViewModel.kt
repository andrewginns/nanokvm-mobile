package org.nanokvm.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.clipboard.ClipboardPayload
import org.nanokvm.mobile.data.AppSettings
import org.nanokvm.mobile.data.AppSettingsStore
import org.nanokvm.mobile.data.DEFAULT_SCROLL_SENSITIVITY
import org.nanokvm.mobile.data.ThemeMode
import org.nanokvm.mobile.data.normalizeScrollSensitivity
import org.nanokvm.mobile.data.ProfileCatalogState
import org.nanokvm.mobile.data.ProfilesRepository
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.CertificateDetails
import org.nanokvm.mobile.runtime.ConnectOutcome
import org.nanokvm.mobile.runtime.ConnectRequest
import org.nanokvm.mobile.runtime.ConnectionFailure
import org.nanokvm.mobile.runtime.ConnectionState
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.ConsoleFeatureBundle
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.VideoSurfaceSink
import org.nanokvm.mobile.runtime.TrustPreflightOutcome
import org.nanokvm.mobile.runtime.ApprovedAdministrationDestination
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeCoordinator
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeLocalFailure
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeRequest
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeResult
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeSessionEndReason
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeSessionTerminator
import org.nanokvm.mobile.platform.LocalNetworkAccess
import org.nanokvm.mobile.security.SavedCredentialStore
import org.nanokvm.mobile.security.SavedCredentials
import org.nanokvm.mobile.security.CredentialPromptKind
import org.nanokvm.mobile.security.CredentialPromptFailure
import org.nanokvm.mobile.security.CredentialPromptRequest
import org.nanokvm.mobile.security.CredentialPromptResult
import org.nanokvm.mobile.security.StagedCredential
import org.nanokvm.mobile.ui.screens.ConsoleSessionDraftOwner

sealed interface AppScreen {
    data object Profiles : AppScreen
    data class EditProfile(val profile: HostProfile, val isNew: Boolean) : AppScreen
    data class Connecting(val profile: HostProfile) : AppScreen
    data class ReviewCertificate(
        val profile: HostProfile,
        val certificate: CertificateDetails,
    ) : AppScreen
    data class Console(val profile: HostProfile) : AppScreen
}

data class AppUiState(
    val screen: AppScreen = AppScreen.Profiles,
    val profiles: List<HostProfile> = emptyList(),
    /** True only after profile storage has produced a renderable terminal state. */
    val profileCatalogResolved: Boolean = false,
    val savedPasswordProfileIds: Set<String> = emptySet(),
    val scrollSensitivity: Float = DEFAULT_SCROLL_SENSITIVITY,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val mjpegFrameDetectionEnabled: Boolean = false,
    val backendSession: BackendSession = BackendSession(),
    val credentialPrompt: CredentialPromptRequest? = null,
    val passwordChangeInProgress: Boolean = false,
    val passwordEntryProfile: HostProfile? = null,
    val profileStorageIssue: ProfileStorageIssue? = null,
    val profileStorageBusy: Boolean = false,
    val profileMutation: ProfileMutationUiState = ProfileMutationUiState.Idle,
    val localNetworkPermission: LocalNetworkPermissionUiState? = null,
    /** Memory-only ACTION_SEND text awaiting destination-bound review; never persisted. */
    val pendingSharedPaste: ClipboardPayload? = null,
    /** Changes on every genuine background transition to clear composable-only sensitive work. */
    val sensitiveWorkGeneration: Long = 0,
    /** Memory-only FIFO of notices awaiting explicit presentation acknowledgement. */
    val pendingAppNotices: List<PendingAppNotice> = emptyList(),
)

data class PendingAppNotice(
    val id: Long,
    val content: AppNotice,
)

sealed interface ProfileMutationUiState {
    data object Idle : ProfileMutationUiState
    data class Saving(val profileId: String) : ProfileMutationUiState
    data class Deleting(val profileId: String) : ProfileMutationUiState
}

sealed interface LocalNetworkPermissionUiState {
    data class Rationale(val profile: HostProfile) : LocalNetworkPermissionUiState
    data class Requesting(val profile: HostProfile) : LocalNetworkPermissionUiState
    data class Denied(
        val profile: HostProfile,
        val canRequestAgain: Boolean,
    ) : LocalNetworkPermissionUiState
}

sealed interface ProfileStorageIssue {
    data object Corrupted : ProfileStorageIssue
    data object Unavailable : ProfileStorageIssue
}

private fun CredentialPromptFailure.toAppNotice(): AppNotice = AppNotice.Credential(
    when (this) {
        CredentialPromptFailure.DeviceProtectionUnavailable ->
            CredentialNotice.DeviceProtectionUnavailable
        CredentialPromptFailure.AuthenticationStartFailed ->
            CredentialNotice.AuthenticationStartFailed
        CredentialPromptFailure.AuthenticationFailed ->
            CredentialNotice.AuthenticationFailedUsePassword
    },
)

class AppViewModel internal constructor(
    private val profilesRepository: ProfilesRepository,
    private val appSettingsStore: AppSettingsStore,
    private val backend: ConsoleBackend,
    private val savedCredentialStore: SavedCredentials,
    credentialResults: Flow<CredentialPromptResult>,
    private val savedStateHandle: SavedStateHandle,
    private val localNetworkAccess: LocalNetworkAccess,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AppUiState(screen = RestorableAppScreenState.restore(savedStateHandle)),
    )
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    val remoteInput: RemoteInputSink get() = backend
    val videoSurface: VideoSurfaceSink get() = backend
    internal val consoleFeatures: ConsoleFeatureBundle get() = backend.features
    internal val consoleSessionDraftOwner = ConsoleSessionDraftOwner()

    private val attempts = ConnectionAttemptSlot()
    private val settingsWriteMutex = Mutex()
    private var connectJob: Job? = null
    private var profileCollectionJob: Job? = null
    private var nextCredentialRequestId = 0L
    private var pendingCredentialAction: PendingCredentialAction? = null
    private var pendingPasswordChange: PendingPasswordChange? = null
    private var passwordChangeJob: Job? = null
    private var activePasswordChangeCoordinator: NanoKvmPasswordChangeCoordinator? = null
    private var pendingTrustIntent: PendingTrustIntent? = null
    private var pendingMjpegFrameDetectionEnabled: Boolean? = null
    private var foreground = true
    private val appNoticeIds = AtomicLong()

    private fun updateWithNotice(
        content: AppNotice,
        transform: AppUiState.() -> AppUiState = { this },
    ) {
        val pending = PendingAppNotice(
            id = appNoticeIds.incrementAndGet(),
            content = content,
        )
        mutableState.update { current ->
            current.transform().copy(
                pendingAppNotices = current.pendingAppNotices + pending,
            )
        }
    }

    init {
        startProfileCollection()
        viewModelScope.launch {
            appSettingsStore.settings.collect(::applyAppSettings)
        }
        viewModelScope.launch {
            backend.session.collect { session ->
                val previous = mutableState.value.backendSession
                if (
                    session.sessionGeneration != previous.sessionGeneration ||
                    session.connection == ConnectionState.Disconnected ||
                    session.connection == ConnectionState.Failed
                ) {
                    consoleSessionDraftOwner.clear()
                }
                mutableState.update { it.copy(backendSession = session) }
            }
        }
        viewModelScope.launch {
            credentialResults.collect(::handleCredentialPromptResult)
        }
    }

    fun setScrollSensitivity(sensitivity: Float) {
        val normalizedSensitivity = normalizeScrollSensitivity(sensitivity)
        viewModelScope.launch {
            try {
                settingsWriteMutex.withLock {
                    appSettingsStore.setScrollSensitivity(normalizedSensitivity)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateWithNotice(AppNotice.Simple(SimpleNotice.ScrollSensitivitySaveFailed))
            }
        }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            try {
                settingsWriteMutex.withLock { appSettingsStore.setThemeMode(themeMode) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateWithNotice(AppNotice.Simple(SimpleNotice.ThemePreferenceSaveFailed))
            }
        }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsWriteMutex.withLock { appSettingsStore.setUseDynamicColor(enabled) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateWithNotice(AppNotice.Simple(SimpleNotice.DeviceColourPreferenceSaveFailed))
            }
        }
    }

    fun setMjpegFrameDetectionEnabled(enabled: Boolean) {
        val currentIntent = pendingMjpegFrameDetectionEnabled
            ?: mutableState.value.mjpegFrameDetectionEnabled
        if (currentIntent == enabled) return
        pendingMjpegFrameDetectionEnabled = enabled
        viewModelScope.launch {
            try {
                settingsWriteMutex.withLock {
                    appSettingsStore.setMjpegFrameDetectionEnabled(enabled)
                    // Preference collection is local-only; this is the sole explicit appliance
                    // write and only follows a successful local persistence operation.
                    backend.setMjpegFrameDetectionEnabled(enabled)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateWithNotice(
                    AppNotice.Simple(SimpleNotice.MjpegFrameDetectionPreferenceSaveFailed),
                )
            } finally {
                if (pendingMjpegFrameDetectionEnabled == enabled) {
                    pendingMjpegFrameDetectionEnabled = null
                }
            }
        }
    }

    private fun applyAppSettings(settings: AppSettings) {
        backend.setMjpegFrameDetectionPreference(settings.mjpegFrameDetectionEnabled)
        mutableState.update {
            it.copy(
                scrollSensitivity = settings.scrollSensitivity,
                themeMode = settings.themeMode,
                useDynamicColor = settings.useDynamicColor,
                mjpegFrameDetectionEnabled = settings.mjpegFrameDetectionEnabled,
            )
        }
    }

    private fun startProfileCollection() {
        profileCollectionJob?.cancel()
        profileCollectionJob = viewModelScope.launch {
            profilesRepository.profiles.collect { catalog ->
                when (catalog) {
                    is ProfileCatalogState.Ready -> {
                        val credentialIds = try {
                            savedCredentialIds(catalog.profiles)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            mutableState.value.savedPasswordProfileIds
                        }
                        mutableState.update {
                            it.copy(
                                profiles = catalog.profiles,
                                profileCatalogResolved = true,
                                savedPasswordProfileIds = credentialIds,
                                profileStorageIssue = null,
                                profileStorageBusy = false,
                            )
                        }
                    }
                    ProfileCatalogState.Corrupted -> mutableState.update {
                        it.copy(
                            profiles = emptyList(),
                            profileCatalogResolved = true,
                            savedPasswordProfileIds = emptySet(),
                            profileStorageIssue = ProfileStorageIssue.Corrupted,
                            profileStorageBusy = false,
                        )
                    }
                    ProfileCatalogState.Unavailable -> mutableState.update {
                        it.copy(
                            profiles = emptyList(),
                            profileCatalogResolved = true,
                            savedPasswordProfileIds = emptySet(),
                            profileStorageIssue = ProfileStorageIssue.Unavailable,
                            profileStorageBusy = false,
                        )
                    }
                }
            }
        }
    }

    private fun profileCatalogIsActionable(): Boolean = mutableState.value.run {
        profileCatalogResolved && profileStorageIssue == null && !profileStorageBusy &&
            profileMutation == ProfileMutationUiState.Idle
    }

    fun addProfile() {
        if (!profileCatalogIsActionable()) return
        val screen = AppScreen.EditProfile(
            profile = HostProfile(
                name = "NanoKVM ${mutableState.value.profiles.size + 1}",
                host = "",
            ),
            isNew = true,
        )
        persistRestorableScreen(screen)
        mutableState.update { it.copy(screen = screen) }
    }

    fun editProfile(profile: HostProfile) {
        if (!profileCatalogIsActionable()) return
        val screen = AppScreen.EditProfile(profile, isNew = false)
        persistRestorableScreen(screen)
        mutableState.update { it.copy(screen = screen) }
    }

    fun saveProfile(profile: HostProfile) {
        if (!profileCatalogIsActionable()) return
        val mutation = ProfileMutationUiState.Saving(profile.id)
        mutableState.update { it.copy(profileMutation = mutation) }
        viewModelScope.launch {
            try {
                val existing = mutableState.value.profiles.firstOrNull { it.id == profile.id }
                if (
                    existing != null &&
                    SavedCredentialStore.credentialIdentity(existing) !=
                    SavedCredentialStore.credentialIdentity(profile)
                ) {
                    // Never hide credential deletion inside an ordinary profile save. Requiring the
                    // explicit remove action avoids an unresolvable cross-store partial transaction.
                    if (savedCredentialStore.hasCredential(profile.id)) {
                        updateWithNotice(
                            AppNotice.Credential(
                                CredentialNotice.RemoveBeforeEndpointChange,
                            ),
                        )
                        return@launch
                    }
                }
                profilesRepository.upsert(profile)
                persistRestorableScreen(AppScreen.Profiles)
                mutableState.update { it.copy(screen = AppScreen.Profiles) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateWithNotice(AppNotice.Simple(SimpleNotice.ProfileSaveFailed))
            } finally {
                mutableState.update {
                    if (it.profileMutation == mutation) {
                        it.copy(profileMutation = ProfileMutationUiState.Idle)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * Removes only the certificate decision stored for the profile currently being edited.
     *
     * Editor fields are intentionally not accepted here: they may be incomplete or contain
     * unrelated unsaved work. The editor remains open and Compose retains that draft while the
     * persisted profile snapshot is refreshed without its certificate pin.
     */
    fun forgetProfileCertificate(profileId: String) {
        if (!profileCatalogIsActionable()) return
        val editor = mutableState.value.screen as? AppScreen.EditProfile ?: return
        if (editor.isNew || editor.profile.id != profileId) return
        val persisted = mutableState.value.profiles.firstOrNull { it.id == profileId } ?: return
        if (persisted.trustedCertificateSha256 == null) return

        viewModelScope.launch {
            val updatedProfile = persisted.copy(trustedCertificateSha256 = null)
            try {
                profilesRepository.upsert(updatedProfile)
                val currentScreen = mutableState.value.screen
                if (currentScreen is AppScreen.EditProfile && currentScreen.profile.id == profileId) {
                    val updatedScreen = currentScreen.copy(profile = updatedProfile)
                    persistRestorableScreen(updatedScreen)
                    mutableState.update { it.copy(screen = updatedScreen) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateWithNotice(
                    AppNotice.Simple(SimpleNotice.CertificateDecisionUpdateFailed),
                )
            }
        }
    }

    fun deleteProfile(profile: HostProfile) {
        if (!profileCatalogIsActionable()) return
        val mutation = ProfileMutationUiState.Deleting(profile.id)
        mutableState.update { it.copy(profileMutation = mutation) }
        viewModelScope.launch {
            try {
                try {
                    // Profile deletion is intentionally blocked if credential removal cannot be proven.
                    savedCredentialStore.delete(profile.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    updateWithNotice(
                        AppNotice.Credential(
                            CredentialNotice.ProfileDeleteCredentialRemovalUnverified,
                        ),
                    )
                    return@launch
                }
                try {
                    profilesRepository.delete(profile.id)
                    persistRestorableScreen(AppScreen.Profiles)
                    mutableState.update { it.copy(screen = AppScreen.Profiles) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    updateWithNotice(
                        AppNotice.Simple(SimpleNotice.ProfileDeleteAfterCredentialRemovalFailed),
                    )
                }
            } finally {
                mutableState.update {
                    if (it.profileMutation == mutation) {
                        it.copy(profileMutation = ProfileMutationUiState.Idle)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun resetProfileStorage() {
        if (mutableState.value.profileStorageIssue !is ProfileStorageIssue.Corrupted) return
        if (mutableState.value.profileStorageBusy) return
        mutableState.update { it.copy(profileStorageBusy = true) }
        viewModelScope.launch {
            try {
                savedCredentialStore.deleteAll()
                profilesRepository.reset()
                mutableState.update {
                    it.copy(
                        profileCatalogResolved = false,
                        profileStorageIssue = null,
                        profileStorageBusy = false,
                    )
                }
                startProfileCollection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateWithNotice(AppNotice.Simple(SimpleNotice.ProfileResetFailed)) {
                    copy(profileStorageBusy = false)
                }
            }
        }
    }

    fun retryProfileStorage() {
        if (mutableState.value.profileStorageIssue !is ProfileStorageIssue.Unavailable) return
        if (mutableState.value.profileStorageBusy) return
        mutableState.update {
            it.copy(
                profileCatalogResolved = false,
                profileStorageIssue = null,
                profileStorageBusy = true,
            )
        }
        startProfileCollection()
    }

    fun prepareConnection(profile: HostProfile) {
        if (!profileCatalogIsActionable()) return
        if (mutableState.value.credentialPrompt != null || pendingCredentialAction != null) return
        if (!profile.useHttps) {
            updateWithNotice(AppNotice.Core(ConnectionFailure.HttpsRequired))
            return
        }
        if (!localNetworkAccess.isGranted()) {
            mutableState.update {
                it.copy(
                    localNetworkPermission = LocalNetworkPermissionUiState.Rationale(profile),
                )
            }
            return
        }
        prepareConnectionWithLocalNetworkAccess(profile)
    }

    private fun prepareConnectionWithLocalNetworkAccess(profile: HostProfile) {
        val intent = PendingTrustIntent(
            profile = profile,
            unlockSavedCredential = profile.id in mutableState.value.savedPasswordProfileIds,
        )
        persistRestorableScreen(AppScreen.Profiles)
        pendingTrustIntent = intent
        launchTrustPreflight(intent)
    }

    fun beginLocalNetworkPermissionRequest() {
        when (val rationale = mutableState.value.localNetworkPermission) {
            is LocalNetworkPermissionUiState.Rationale -> mutableState.update {
                it.copy(
                    localNetworkPermission =
                        LocalNetworkPermissionUiState.Requesting(rationale.profile),
                )
            }
            else -> Unit
        }
    }

    fun onLocalNetworkPermissionResult(granted: Boolean, canRequestAgain: Boolean) {
        val request = mutableState.value.localNetworkPermission ?: return
        if (granted && localNetworkAccess.isGranted()) {
            mutableState.update { it.copy(localNetworkPermission = null) }
            val profile = when (request) {
                is LocalNetworkPermissionUiState.Rationale -> request.profile
                is LocalNetworkPermissionUiState.Requesting -> request.profile
                is LocalNetworkPermissionUiState.Denied -> request.profile
            }
            prepareConnection(profile)
            return
        }
        mutableState.update {
            it.copy(
                localNetworkPermission = when (request) {
                    is LocalNetworkPermissionUiState.Rationale ->
                        LocalNetworkPermissionUiState.Denied(request.profile, canRequestAgain)
                    is LocalNetworkPermissionUiState.Requesting ->
                        LocalNetworkPermissionUiState.Denied(request.profile, canRequestAgain)
                    is LocalNetworkPermissionUiState.Denied ->
                        request.copy(canRequestAgain = canRequestAgain)
                },
            )
        }
    }

    fun retryLocalNetworkPermission() {
        when (val denied = mutableState.value.localNetworkPermission) {
            is LocalNetworkPermissionUiState.Denied -> mutableState.update {
                it.copy(localNetworkPermission = LocalNetworkPermissionUiState.Rationale(denied.profile))
            }
            else -> Unit
        }
    }

    fun refreshLocalNetworkAccess(canRequestAgain: Boolean) {
        val currentState = mutableState.value
        val request = currentState.localNetworkPermission
        if (request != null && localNetworkAccess.isGranted()) {
            mutableState.update { it.copy(localNetworkPermission = null) }
            when (request) {
                is LocalNetworkPermissionUiState.Rationale -> prepareConnection(request.profile)
                is LocalNetworkPermissionUiState.Requesting -> prepareConnection(request.profile)
                is LocalNetworkPermissionUiState.Denied -> prepareConnection(request.profile)
            }
            return
        }
        if (request is LocalNetworkPermissionUiState.Requesting) {
            mutableState.update {
                it.copy(
                    localNetworkPermission = LocalNetworkPermissionUiState.Denied(
                        request.profile,
                        canRequestAgain,
                    ),
                )
            }
            return
        }
        if (request != null || localNetworkAccess.isGranted()) return

        val interruptedProfile = when (val screen = currentState.screen) {
            is AppScreen.Connecting -> screen.profile
            is AppScreen.ReviewCertificate -> screen.profile
            is AppScreen.Console -> screen.profile
            else -> currentState.passwordEntryProfile ?: pendingCredentialAction?.profile
        } ?: return

        connectJob?.cancel()
        connectJob = null
        clearActiveAttempt()
        clearPendingCredentialAction()
        pendingTrustIntent = null
        consoleSessionDraftOwner.clear()
        persistRestorableScreen(AppScreen.Profiles)
        runCatching { backend.releaseAllInput() }
        mutableState.update {
            it.copy(
                screen = AppScreen.Profiles,
                credentialPrompt = null,
                passwordEntryProfile = null,
                localNetworkPermission = LocalNetworkPermissionUiState.Denied(
                    interruptedProfile,
                    canRequestAgain,
                ),
            )
        }
        viewModelScope.launch {
            try {
                backend.disconnect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Permission recovery remains actionable even if transport teardown reports failure.
            }
        }
    }

    fun dismissLocalNetworkPermission() {
        mutableState.update { it.copy(localNetworkPermission = null) }
    }

    private fun continueAfterTrust(profile: HostProfile, unlockSavedCredential: Boolean) {
        pendingTrustIntent = null
        if (unlockSavedCredential) {
            val request = newCredentialRequest(CredentialPromptKind.Unlock, profile)
            pendingCredentialAction = PendingCredentialAction.Unlock(request, profile)
            mutableState.update {
                it.copy(
                    screen = AppScreen.Profiles,
                    credentialPrompt = request,
                    passwordEntryProfile = null,
                )
            }
        } else {
            mutableState.update {
                it.copy(
                    screen = AppScreen.Profiles,
                    passwordEntryProfile = profile,
                )
            }
        }
    }

    fun dismissPasswordEntry() {
        mutableState.update { it.copy(passwordEntryProfile = null) }
    }

    fun submitPassword(profile: HostProfile, password: CharArray, savePassword: Boolean) {
        if (!profileCatalogIsActionable()) {
            password.fill('\u0000')
            return
        }
        mutableState.update { it.copy(passwordEntryProfile = null) }
        if (!savePassword) {
            connect(profile, password)
            return
        }
        if (pendingCredentialAction != null || mutableState.value.credentialPrompt != null) {
            password.fill('\u0000')
            return
        }
        val request = newCredentialRequest(CredentialPromptKind.Save, profile)
        val action = PendingCredentialAction.Save(request, profile, password)
        pendingCredentialAction = action
        viewModelScope.launch {
            try {
                savedCredentialStore.prepareToSave(profile)
                if (pendingCredentialAction !== action) return@launch
                mutableState.update { it.copy(credentialPrompt = request) }
            } catch (cancelled: CancellationException) {
                clearPendingCredentialAction(action)
                throw cancelled
            } catch (_: Throwable) {
                clearPendingCredentialAction(action)
                updateWithNotice(
                    AppNotice.Credential(CredentialNotice.ProtectedKeyCreationFailed),
                ) {
                    copy(passwordEntryProfile = profile)
                }
            }
        }
    }

    fun connect(
        profile: HostProfile,
        password: CharArray,
        stagedCredential: StagedCredential? = null,
    ) {
        if (!profileCatalogIsActionable()) {
            password.fill('\u0000')
            stagedCredential?.clear()
            return
        }
        if (!profile.useHttps) {
            password.fill('\u0000')
            stagedCredential?.clear()
            updateWithNotice(AppNotice.Core(ConnectionFailure.HttpsRequired)) {
                copy(screen = AppScreen.Profiles)
            }
            return
        }
        if (!localNetworkAccess.isGranted()) {
            password.fill('\u0000')
            stagedCredential?.clear()
            clearActiveAttempt()
            mutableState.update {
                it.copy(
                    screen = AppScreen.Profiles,
                    passwordEntryProfile = null,
                    localNetworkPermission = LocalNetworkPermissionUiState.Rationale(profile),
                )
            }
            return
        }
        require(stagedCredential == null || stagedCredential.profileId == profile.id) {
            "A staged credential must belong to the connection profile"
        }
        persistRestorableScreen(AppScreen.Profiles)
        val request = ConnectRequest(profile = profile, password = password)
        val attempt = ConnectionAttempt(request, stagedCredential)
        replaceActiveAttempt(attempt)
        launchConnect(attempt, request) {
            performConnectInCurrentCoroutine(attempt, request)
        }
    }

    fun trustCertificate(persist: Boolean) {
        val screen = mutableState.value.screen as? AppScreen.ReviewCertificate ?: return
        val trustIntent = pendingTrustIntent
        if (trustIntent != null && trustIntent.profile.id == screen.profile.id) {
            val trustedProfile = screen.profile.copy(
                trustedCertificateSha256 = screen.certificate.sha256,
            )
            connectJob?.cancel()
            connectJob = viewModelScope.launch {
                try {
                    if (persist) profilesRepository.upsert(trustedProfile)
                    if (pendingTrustIntent !== trustIntent) return@launch
                    continueAfterTrust(trustedProfile, trustIntent.unlockSavedCredential)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (pendingTrustIntent === trustIntent) pendingTrustIntent = null
                    updateWithNotice(
                        AppNotice.Simple(SimpleNotice.CertificateDecisionSaveFailed),
                    ) {
                        copy(screen = AppScreen.Profiles)
                    }
                }
            }
            return
        }
        val attempt = attempts.current ?: return
        val request = attempt.request
        if (request.profile.id != screen.profile.id) return
        val trustedProfile = screen.profile.copy(
            trustedCertificateSha256 = screen.certificate.sha256,
        )
        val trustedRequest = request.copy(
            profile = trustedProfile,
            acceptedCertificateSha256 = screen.certificate.sha256,
        )
        attempt.continueWith(trustedRequest)
        launchConnect(attempt, trustedRequest) {
            if (persist) profilesRepository.upsert(trustedProfile)
            performConnectInCurrentCoroutine(attempt, trustedRequest)
        }
    }

    fun cancelToProfiles() {
        if (mutableState.value.profileMutation != ProfileMutationUiState.Idle) return
        cancelPasswordChange()
        connectJob?.cancel()
        connectJob = null
        clearActiveAttempt()
        clearPendingCredentialAction()
        pendingTrustIntent = null
        consoleSessionDraftOwner.clear()
        persistRestorableScreen(AppScreen.Profiles)
        runCatching { backend.releaseAllInput() }
        mutableState.update {
            it.copy(
                screen = AppScreen.Profiles,
                credentialPrompt = null,
                passwordEntryProfile = null,
                localNetworkPermission = null,
            )
        }
    }

    fun disconnect() {
        cancelPasswordChange()
        connectJob?.cancel()
        connectJob = null
        clearActiveAttempt()
        pendingTrustIntent = null
        consoleSessionDraftOwner.clear()
        persistRestorableScreen(AppScreen.Profiles)
        runCatching { backend.releaseAllInput() }
        viewModelScope.launch {
            try {
                backend.disconnect()
                mutableState.update { it.copy(screen = AppScreen.Profiles) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateWithNotice(AppNotice.Simple(SimpleNotice.DisconnectCleanupFailed)) {
                    copy(screen = AppScreen.Profiles)
                }
            }
        }
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        if (!foreground) backend.releaseAllInput()
        val networkFlowActive = mutableState.value.screen.let { screen ->
            screen is AppScreen.Connecting ||
                screen is AppScreen.ReviewCertificate ||
                screen is AppScreen.Console
        }
        if (foreground && networkFlowActive && !localNetworkAccess.isGranted()) {
            // ON_RESUME performs the user-facing recovery. Do not briefly resume video or input
            // after Android has revoked local-network access while the app was stopped.
            backend.setForeground(false)
            return
        }
        backend.setForeground(foreground)
    }

    /** Called only for a genuine background transition, never configuration recreation. */
    fun clearSensitiveWorkForBackground() {
        foreground = false
        consoleSessionDraftOwner.clear()
        connectJob?.cancel()
        connectJob = null
        clearActiveAttempt()
        pendingTrustIntent = null
        // A system device-credential UI can stop the host Activity. Keep that single prompt-owned
        // action until its terminal callback; the callback below refuses to connect while stopped.
        val promptInProgress = mutableState.value.credentialPrompt != null
        if (!promptInProgress) clearPendingCredentialAction()
        if (!promptInProgress) cancelPasswordChange()
        val interruptedScreen = mutableState.value.screen
        val mustReturnToProfiles = interruptedScreen is AppScreen.Connecting ||
            interruptedScreen is AppScreen.ReviewCertificate
        if (mustReturnToProfiles) persistRestorableScreen(AppScreen.Profiles)
        mutableState.update {
            it.copy(
                screen = if (mustReturnToProfiles) AppScreen.Profiles else it.screen,
                credentialPrompt = if (promptInProgress) it.credentialPrompt else null,
                passwordEntryProfile = null,
                pendingSharedPaste = null,
                sensitiveWorkGeneration = it.sensitiveWorkGeneration + 1,
            )
        }
    }

    fun releaseAllInput() {
        backend.releaseAllInput()
    }

    fun acknowledgeNotice(expectedId: Long) {
        mutableState.update { current ->
            if (current.pendingAppNotices.firstOrNull()?.id == expectedId) {
                current.copy(pendingAppNotices = current.pendingAppNotices.drop(1))
            } else {
                current
            }
        }
    }

    fun receiveSharedPlainText(payload: ClipboardPayload) {
        if (payload.text.isEmpty()) {
            updateWithNotice(AppNotice.Share(ShareNotice.Empty))
            return
        }
        if (mutableState.value.screen is AppScreen.Console) {
            mutableState.update { it.copy(pendingSharedPaste = payload) }
        } else {
            updateWithNotice(AppNotice.Share(ShareNotice.ChooseConnection)) {
                copy(pendingSharedPaste = payload)
            }
        }
    }

    fun consumeSharedPlainText(payload: ClipboardPayload) {
        mutableState.update {
            if (it.pendingSharedPaste === payload) it.copy(pendingSharedPaste = null) else it
        }
    }

    fun reportShareNotice(notice: ShareNotice) {
        updateWithNotice(AppNotice.Share(notice))
    }

    fun savedCredentialChanged() {
        viewModelScope.launch {
            val ids = try {
                savedCredentialIds(mutableState.value.profiles)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value.savedPasswordProfileIds
            }
            mutableState.update { it.copy(savedPasswordProfileIds = ids) }
        }
    }

    fun removeSavedCredential(profileId: String) {
        viewModelScope.launch {
            try {
                savedCredentialStore.delete(profileId)
                val ids = savedCredentialIds(mutableState.value.profiles)
                mutableState.update { it.copy(savedPasswordProfileIds = ids) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateWithNotice(
                    AppNotice.Credential(
                        CredentialNotice.ProtectedPasswordRemovalUnverified,
                    ),
                )
            }
        }
    }

    /**
     * Starts one reviewed account mutation for the exact console destination. Ownership of
     * [password] transfers immediately; every refusal and terminal path erases that array.
     */
    fun changeAdministrationPassword(
        destination: ApprovedAdministrationDestination,
        username: String,
        password: CharArray,
        saveProtectedCredential: Boolean,
    ) {
        val profile = (mutableState.value.screen as? AppScreen.Console)?.profile
        val owner = backend.features.passwordChange
        if (
            profile == null || owner == null || !foreground ||
            passwordChangeJob != null || pendingPasswordChange != null ||
            pendingCredentialAction != null || mutableState.value.credentialPrompt != null
        ) {
            password.fill('\u0000')
            updateWithNotice(AppNotice.Password(PasswordNotice.ChangeUnavailable))
            return
        }
        val authenticationRequest = if (saveProtectedCredential) {
            newCredentialRequest(CredentialPromptKind.Save, profile)
        } else {
            null
        }
        val coordinator = owner.createPasswordChangeCoordinator(
            NanoKvmPasswordChangeRequest(
                destination = destination,
                profile = profile,
                savedCredentials = savedCredentialStore,
                profilesRepository = profilesRepository,
                sessionTerminator = NanoKvmPasswordChangeSessionTerminator(
                    ::terminatePasswordChangeSession,
                ),
            ),
        )
        if (coordinator == null) {
            password.fill('\u0000')
            updateWithNotice(AppNotice.Password(PasswordNotice.DestinationChanged))
            return
        }

        activePasswordChangeCoordinator = coordinator
        mutableState.update { it.copy(passwordChangeInProgress = true) }
        lateinit var created: Job
        created = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                when (
                    val result = coordinator.begin(
                        username = username,
                        password = password,
                        saveProtectedCredential = saveProtectedCredential,
                        authenticationRequest = authenticationRequest,
                    )
                ) {
                    is NanoKvmPasswordChangeResult.AuthenticationRequired -> {
                        if (
                            activePasswordChangeCoordinator !== coordinator ||
                            !foreground ||
                            mutableState.value.screen !is AppScreen.Console
                        ) {
                            coordinator.invalidate()
                            mutableState.update {
                                it.copy(passwordChangeInProgress = false)
                            }
                            return@launch
                        }
                        pendingPasswordChange = PendingPasswordChange(
                            request = result.request,
                            coordinator = coordinator,
                        )
                        mutableState.update { it.copy(credentialPrompt = result.request) }
                    }
                    else -> {
                        activePasswordChangeCoordinator = null
                        publishPasswordChangeResult(result)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (passwordChangeJob === created) passwordChangeJob = null
                if (
                    pendingPasswordChange?.coordinator !== coordinator &&
                    activePasswordChangeCoordinator === coordinator
                ) {
                    activePasswordChangeCoordinator = null
                    mutableState.update { it.copy(passwordChangeInProgress = false) }
                }
            }
        }
        passwordChangeJob = created
        created.start()
    }

    private suspend fun handleCredentialPromptResult(result: CredentialPromptResult) {
        val passwordChange = pendingPasswordChange
        if (passwordChange?.request?.id == result.requestId) {
            pendingPasswordChange = null
            mutableState.update { it.copy(credentialPrompt = null) }
            if (!foreground || activePasswordChangeCoordinator !== passwordChange.coordinator) {
                passwordChange.coordinator.invalidate()
                if (activePasswordChangeCoordinator === passwordChange.coordinator) {
                    activePasswordChangeCoordinator = null
                }
                mutableState.update { it.copy(passwordChangeInProgress = false) }
                return
            }
            val completed = passwordChange.coordinator.completeAuthentication(result)
            if (activePasswordChangeCoordinator === passwordChange.coordinator) {
                activePasswordChangeCoordinator = null
            }
            publishPasswordChangeResult(completed)
            return
        }
        val action = pendingCredentialAction ?: return
        if (action.request.id != result.requestId) return
        mutableState.update { it.copy(credentialPrompt = null) }
        if (!foreground) {
            clearPendingCredentialAction(action)
            mutableState.update { it.copy(passwordEntryProfile = null) }
            return
        }
        when (result) {
            is CredentialPromptResult.Authenticated -> completeAuthenticatedCredential(action)
            is CredentialPromptResult.Cancelled -> {
                clearPendingCredentialAction(action)
                mutableState.update { it.copy(passwordEntryProfile = action.profile) }
            }
            is CredentialPromptResult.Failed -> {
                clearPendingCredentialAction(action)
                updateWithNotice(result.failure.toAppNotice()) {
                    copy(passwordEntryProfile = action.profile)
                }
            }
        }
    }

    private suspend fun publishPasswordChangeResult(result: NanoKvmPasswordChangeResult) {
        if (result is NanoKvmPasswordChangeResult.AuthenticationRequired) return
        val ids = try {
            savedCredentialIds(mutableState.value.profiles)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutableState.value.savedPasswordProfileIds
        }
        val notice = when (result) {
            is NanoKvmPasswordChangeResult.Changed ->
                if (result.localFailures.isEmpty()) {
                    PasswordNotice.Changed
                } else {
                    passwordChangeLocalFailureNotice(result.localFailures)
                }
            is NanoKvmPasswordChangeResult.ManualVerificationRequired ->
                PasswordNotice.ManualVerificationRequired
            is NanoKvmPasswordChangeResult.Rejected ->
                PasswordNotice.Rejected
            NanoKvmPasswordChangeResult.AuthenticationExpired ->
                PasswordNotice.SessionExpired
            NanoKvmPasswordChangeResult.InvalidRequest ->
                PasswordNotice.InvalidRequest
            NanoKvmPasswordChangeResult.AuthenticationCancelled -> null
            NanoKvmPasswordChangeResult.AuthenticationFailed ->
                PasswordNotice.AndroidAuthenticationFailed
            NanoKvmPasswordChangeResult.LocalPreparationFailed ->
                PasswordNotice.LocalPreparationFailed
            NanoKvmPasswordChangeResult.StaleSession ->
                PasswordNotice.DestinationChanged
            NanoKvmPasswordChangeResult.Busy ->
                PasswordNotice.Busy
            NanoKvmPasswordChangeResult.IgnoredAuthenticationResult -> null
            is NanoKvmPasswordChangeResult.AuthenticationRequired -> null
        }?.let { AppNotice.Password(it) }
        if (notice == null) {
            mutableState.update {
                it.copy(
                    credentialPrompt = null,
                    passwordChangeInProgress = false,
                    savedPasswordProfileIds = ids,
                )
            }
        } else {
            updateWithNotice(notice) {
                copy(
                    credentialPrompt = null,
                    passwordChangeInProgress = false,
                    savedPasswordProfileIds = ids,
                )
            }
        }
    }

    private fun passwordChangeLocalFailureNotice(
        failures: Set<NanoKvmPasswordChangeLocalFailure>,
    ): PasswordNotice = when {
        NanoKvmPasswordChangeLocalFailure.SESSION_END in failures ->
            PasswordNotice.DisconnectUnverified
        NanoKvmPasswordChangeLocalFailure.PROFILE_UPDATE in failures ->
            PasswordNotice.ProfileUpdateFailed
        NanoKvmPasswordChangeLocalFailure.CREDENTIAL_COMMIT in failures ||
            NanoKvmPasswordChangeLocalFailure.CREDENTIAL_DELETE in failures ->
            PasswordNotice.CredentialRetentionFailed
        else -> PasswordNotice.LocalStateUnverified
    }

    private suspend fun terminatePasswordChangeSession(
        reason: NanoKvmPasswordChangeSessionEndReason,
    ) {
        consoleSessionDraftOwner.clear()
        var disconnectFailure: Throwable? = null
        try {
            backend.disconnect()
        } catch (cancelled: CancellationException) {
            disconnectFailure = cancelled
        } catch (error: Throwable) {
            disconnectFailure = error
        } finally {
            persistRestorableScreen(AppScreen.Profiles)
            val manualVerificationRequired =
                reason == NanoKvmPasswordChangeSessionEndReason.OUTCOME_REQUIRES_MANUAL_VERIFICATION
            val transform: AppUiState.() -> AppUiState = {
                copy(
                    screen = AppScreen.Profiles,
                    credentialPrompt = null,
                    passwordEntryProfile = null,
                    pendingSharedPaste = null,
                )
            }
            if (manualVerificationRequired) {
                updateWithNotice(
                    AppNotice.Password(PasswordNotice.ManualVerificationRequired),
                    transform,
                )
            } else {
                mutableState.update { it.transform() }
            }
        }
        disconnectFailure?.let { throw it }
    }

    private suspend fun completeAuthenticatedCredential(action: PendingCredentialAction) {
        when (action) {
            is PendingCredentialAction.Unlock -> {
                try {
                    val password = savedCredentialStore.unlock(action.profile)
                    clearPendingCredentialAction(action)
                    connect(action.profile, password)
                } catch (cancelled: CancellationException) {
                    clearPendingCredentialAction(action)
                    throw cancelled
                } catch (_: Throwable) {
                    val deletionSucceeded = try {
                        savedCredentialStore.delete(action.profile.id)
                        true
                    } catch (cancelled: CancellationException) {
                        clearPendingCredentialAction(action)
                        throw cancelled
                    } catch (_: Throwable) {
                        false
                    }
                    clearPendingCredentialAction(action)
                    val ids = if (deletionSucceeded) {
                        savedCredentialIds(mutableState.value.profiles)
                    } else {
                        mutableState.value.savedPasswordProfileIds
                    }
                    val notice = AppNotice.Credential(
                        if (deletionSucceeded) {
                            CredentialNotice.SavedPasswordUnlockFailed
                        } else {
                            CredentialNotice.SavedPasswordUnlockAndRemovalFailed
                        },
                    )
                    updateWithNotice(notice) {
                        copy(
                            savedPasswordProfileIds = ids,
                            passwordEntryProfile = action.profile,
                        )
                    }
                }
            }
            is PendingCredentialAction.Save -> {
                try {
                    val staged = savedCredentialStore.stageCredential(action.profile, action.password)
                    pendingCredentialAction = null
                    connect(action.profile, action.password, staged)
                } catch (cancelled: CancellationException) {
                    clearPendingCredentialAction(action)
                    throw cancelled
                } catch (_: Throwable) {
                    clearPendingCredentialAction(action)
                    updateWithNotice(
                        AppNotice.Credential(CredentialNotice.PasswordProtectionFailed),
                    ) {
                        copy(passwordEntryProfile = action.profile)
                    }
                }
            }
        }
    }

    private fun newCredentialRequest(
        kind: CredentialPromptKind,
        profile: HostProfile,
    ): CredentialPromptRequest = CredentialPromptRequest(
        id = ++nextCredentialRequestId,
        kind = kind,
        profileName = profile.name,
    )

    private fun clearPendingCredentialAction(expected: PendingCredentialAction? = null) {
        val current = pendingCredentialAction ?: return
        if (expected != null && current !== expected) return
        pendingCredentialAction = null
        current.clear()
    }

    private fun launchConnect(
        attempt: ConnectionAttempt,
        request: ConnectRequest,
        block: suspend () -> Unit,
    ) {
        connectJob?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                if (finishAttemptIfOwned(attempt, request)) {
                    mutableState.update { it.copy(screen = AppScreen.Profiles) }
                }
                throw cancelled
            } catch (_: Throwable) {
                if (finishAttemptIfOwned(attempt, request)) {
                    updateWithNotice(AppNotice.Core(ConnectionFailure.Unexpected)) {
                        copy(screen = AppScreen.Profiles)
                    }
                }
            } finally {
                val ownJob = currentCoroutineContext()[Job]
                if (connectJob === ownJob) connectJob = null
            }
        }
        connectJob = job
        job.start()
    }

    private fun launchTrustPreflight(intent: PendingTrustIntent) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                mutableState.update {
                    it.copy(
                        screen = AppScreen.Connecting(intent.profile),
                        passwordEntryProfile = null,
                    )
                }
                val outcome = backend.preflightTrust(intent.profile)
                if (pendingTrustIntent !== intent) return@launch
                when (outcome) {
                    is TrustPreflightOutcome.Trusted -> continueAfterTrust(
                        intent.profile,
                        intent.unlockSavedCredential,
                    )
                    is TrustPreflightOutcome.CertificateReviewRequired -> mutableState.update {
                        it.copy(
                            screen = AppScreen.ReviewCertificate(intent.profile, outcome.certificate),
                        )
                    }
                    is TrustPreflightOutcome.Failed -> {
                        pendingTrustIntent = null
                        updateWithNotice(AppNotice.Core(outcome.failure)) {
                            copy(screen = AppScreen.Profiles)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (pendingTrustIntent === intent) {
                    pendingTrustIntent = null
                    updateWithNotice(
                        AppNotice.Core(ConnectionFailure.CertificateInspectionFailed),
                    ) {
                        copy(screen = AppScreen.Profiles)
                    }
                }
            } finally {
                val ownJob = currentCoroutineContext()[Job]
                if (connectJob === ownJob) connectJob = null
            }
        }
    }

    private suspend fun performConnectInCurrentCoroutine(
        attempt: ConnectionAttempt,
        request: ConnectRequest,
    ) {
        if (!owns(attempt, request)) return
        mutableState.update { it.copy(screen = AppScreen.Connecting(request.profile)) }
        val outcome = backend.connect(request)
        // A transport is allowed to finish after cancellation. Such an outcome must never mutate
        // UI state or commit a staged credential belonging to a newer attempt/phase.
        if (!owns(attempt, request)) return
        when (outcome) {
            ConnectOutcome.Connected -> {
                val staged = attempt.stagedCredential
                val saveError = staged?.let { credential ->
                    try {
                        savedCredentialStore.commit(credential)
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        error
                    }
                }
                val savedPasswordProfileIds = try {
                    savedCredentialIds(mutableState.value.profiles)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    mutableState.value.savedPasswordProfileIds
                }
                if (!finishAttemptIfOwned(attempt, request)) return
                if (saveError == null) {
                    mutableState.update {
                        it.copy(
                            screen = AppScreen.Console(request.profile),
                            savedPasswordProfileIds = savedPasswordProfileIds,
                        )
                    }
                } else {
                    updateWithNotice(
                        AppNotice.Credential(CredentialNotice.ConnectedPasswordSaveFailed),
                    ) {
                        copy(
                            screen = AppScreen.Console(request.profile),
                            savedPasswordProfileIds = savedPasswordProfileIds,
                        )
                    }
                }
            }
            is ConnectOutcome.CertificateReviewRequired -> mutableState.update {
                it.copy(
                    screen = AppScreen.ReviewCertificate(request.profile, outcome.certificate),
                )
            }
            is ConnectOutcome.Failed -> {
                // A failed replacement never overwrites the last working saved credential.
                if (!finishAttemptIfOwned(attempt, request)) return
                updateWithNotice(AppNotice.Core(outcome.failure)) {
                    copy(screen = AppScreen.Profiles)
                }
            }
        }
    }

    override fun onCleared() {
        consoleSessionDraftOwner.close()
        cancelPasswordChange()
        connectJob?.cancel()
        connectJob = null
        clearActiveAttempt()
        clearPendingCredentialAction()
        pendingTrustIntent = null
        runCatching { backend.releaseAllInput() }
        runCatching { backend.close() }
    }

    class Factory(
        private val profilesRepository: ProfilesRepository,
        private val appSettingsStore: AppSettingsStore,
        private val backendProvider: () -> ConsoleBackend,
        private val savedCredentialStore: SavedCredentials,
        private val credentialResults: Flow<CredentialPromptResult>,
        private val localNetworkAccess: LocalNetworkAccess,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            throw UnsupportedOperationException("AppViewModel creation requires CreationExtras")
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(AppViewModel::class.java))
            val handle = extras.createSavedStateHandle()
            return AppViewModel(
                profilesRepository,
                appSettingsStore,
                backendProvider(),
                savedCredentialStore,
                credentialResults,
                handle,
                localNetworkAccess,
            ) as T
        }
    }

    private fun replaceActiveAttempt(attempt: ConnectionAttempt) {
        connectJob?.cancel()
        connectJob = null
        attempts.replace(attempt)
    }

    private fun owns(attempt: ConnectionAttempt, request: ConnectRequest): Boolean =
        attempts.owns(attempt, request)

    private fun finishAttemptIfOwned(
        attempt: ConnectionAttempt,
        request: ConnectRequest,
    ): Boolean {
        return attempts.finishIfOwned(attempt, request)
    }

    private fun clearActiveAttempt() {
        attempts.clear()
    }

    private suspend fun savedCredentialIds(profiles: List<HostProfile>): Set<String> = buildSet {
        profiles.forEach { profile ->
            if (savedCredentialStore.hasCredential(profile.id)) add(profile.id)
        }
    }

    private fun persistRestorableScreen(screen: AppScreen) {
        RestorableAppScreenState.persist(savedStateHandle, screen)
    }

    private fun cancelPasswordChange() {
        pendingPasswordChange = null
        activePasswordChangeCoordinator?.invalidate()
        activePasswordChangeCoordinator = null
        passwordChangeJob?.cancel(CancellationException("Password change owner invalidated"))
        passwordChangeJob = null
        mutableState.update {
            it.copy(credentialPrompt = null, passwordChangeInProgress = false)
        }
    }
}

internal object RestorableAppScreenState {
    private const val STATE_SCREEN_KIND = "screen.kind"
    private const val SCREEN_PROFILES = "profiles"
    private const val SCREEN_EDIT_PROFILE = "edit-profile"
    private const val STATE_EDIT_IS_NEW = "edit.is-new"
    private const val STATE_EDIT_ID = "edit.id"
    private const val STATE_EDIT_NAME = "edit.name"
    private const val STATE_EDIT_HOST = "edit.host"
    private const val STATE_EDIT_PORT = "edit.port"
    private const val STATE_EDIT_HTTPS = "edit.https"
    private const val STATE_EDIT_USERNAME = "edit.username"
    private const val STATE_EDIT_CERTIFICATE = "edit.certificate"
    private val editStateKeys = listOf(
        STATE_EDIT_IS_NEW,
        STATE_EDIT_ID,
        STATE_EDIT_NAME,
        STATE_EDIT_HOST,
        STATE_EDIT_PORT,
        STATE_EDIT_HTTPS,
        STATE_EDIT_USERNAME,
        STATE_EDIT_CERTIFICATE,
    )

    fun persist(savedStateHandle: SavedStateHandle, screen: AppScreen) {
        when (screen) {
            AppScreen.Profiles -> {
                savedStateHandle[STATE_SCREEN_KIND] = SCREEN_PROFILES
                editStateKeys.forEach { key -> savedStateHandle.remove<Any?>(key) }
            }
            is AppScreen.EditProfile -> {
                savedStateHandle[STATE_SCREEN_KIND] = SCREEN_EDIT_PROFILE
                savedStateHandle[STATE_EDIT_IS_NEW] = screen.isNew
                savedStateHandle[STATE_EDIT_ID] = screen.profile.id
                savedStateHandle[STATE_EDIT_NAME] = screen.profile.name
                savedStateHandle[STATE_EDIT_HOST] = screen.profile.host
                savedStateHandle[STATE_EDIT_PORT] = screen.profile.port
                savedStateHandle[STATE_EDIT_HTTPS] = screen.profile.useHttps
                savedStateHandle[STATE_EDIT_USERNAME] = screen.profile.username
                savedStateHandle[STATE_EDIT_CERTIFICATE] = screen.profile.trustedCertificateSha256
            }
            // Authentication, certificate review, and live sessions never survive process death.
            is AppScreen.Connecting,
            is AppScreen.ReviewCertificate,
            is AppScreen.Console,
            -> persist(savedStateHandle, AppScreen.Profiles)
        }
    }

    fun restore(savedStateHandle: SavedStateHandle): AppScreen {
        if (savedStateHandle.get<String>(STATE_SCREEN_KIND) != SCREEN_EDIT_PROFILE) {
            return AppScreen.Profiles
        }
        val id = savedStateHandle.get<String>(STATE_EDIT_ID) ?: return AppScreen.Profiles
        return AppScreen.EditProfile(
            profile = HostProfile(
                id = id,
                name = savedStateHandle.get<String>(STATE_EDIT_NAME) ?: "NanoKVM",
                host = savedStateHandle.get<String>(STATE_EDIT_HOST).orEmpty(),
                port = savedStateHandle.get<Int>(STATE_EDIT_PORT) ?: 443,
                useHttps = savedStateHandle.get<Boolean>(STATE_EDIT_HTTPS) ?: true,
                username = savedStateHandle.get<String>(STATE_EDIT_USERNAME) ?: "admin",
                trustedCertificateSha256 = savedStateHandle[STATE_EDIT_CERTIFICATE],
            ),
            isNew = savedStateHandle.get<Boolean>(STATE_EDIT_IS_NEW) ?: false,
        )
    }
}

/**
 * Owns all plaintext/staged material for one logical login, including a certificate-review
 * continuation. Request identity also acts as the phase token, so a cancellation-ignoring result
 * from an earlier phase cannot complete the attempt.
 */
internal class ConnectionAttempt(
    initialRequest: ConnectRequest,
    val stagedCredential: StagedCredential?,
) {
    var request: ConnectRequest = initialRequest
        private set

    private var cleared = false

    fun continueWith(nextRequest: ConnectRequest) {
        check(!cleared) { "A cleared connection attempt cannot continue" }
        require(nextRequest.password === request.password) {
            "A certificate continuation must retain the owned password buffer"
        }
        request = nextRequest
    }

    fun owns(candidate: ConnectRequest): Boolean = !cleared && request === candidate

    fun clear() {
        if (cleared) return
        cleared = true
        request.clearPassword()
        stagedCredential?.clear()
    }
}

/** Small, synchronous ownership gate around connection outcomes. */
internal class ConnectionAttemptSlot {
    var current: ConnectionAttempt? = null
        private set

    fun replace(next: ConnectionAttempt) {
        val previous = current
        current = next
        if (previous !== next) previous?.clear()
    }

    fun owns(attempt: ConnectionAttempt, request: ConnectRequest): Boolean =
        current === attempt && attempt.owns(request)

    fun finishIfOwned(attempt: ConnectionAttempt, request: ConnectRequest): Boolean {
        if (!owns(attempt, request)) return false
        current = null
        attempt.clear()
        return true
    }

    fun clear() {
        current?.clear()
        current = null
    }
}

private sealed interface PendingCredentialAction {
    val request: CredentialPromptRequest
    val profile: HostProfile
    fun clear()

    data class Unlock(
        override val request: CredentialPromptRequest,
        override val profile: HostProfile,
    ) : PendingCredentialAction {
        override fun clear() = Unit
    }

    data class Save(
        override val request: CredentialPromptRequest,
        override val profile: HostProfile,
        val password: CharArray,
    ) : PendingCredentialAction {
        override fun clear() {
            password.fill('\u0000')
        }
    }
}

private class PendingPasswordChange(
    val request: CredentialPromptRequest,
    val coordinator: NanoKvmPasswordChangeCoordinator,
) {
    override fun toString(): String =
        "PendingPasswordChange(requestId=${request.id}, coordinator=<redacted>)"
}

private data class PendingTrustIntent(
    val profile: HostProfile,
    val unlockSavedCredential: Boolean,
)

package org.nanokvm.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
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
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.ConsoleCommandSink
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.VideoSurfaceSink
import org.nanokvm.mobile.runtime.TrustPreflightOutcome
import org.nanokvm.mobile.runtime.NanoKvmProtocolWifiAccessPointOnboardingSessionFactory
import org.nanokvm.mobile.runtime.ApprovedAdministrationDestination
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeFeatureOwner
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeCoordinator
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeLocalFailure
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeResult
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeSessionEndReason
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeSessionTerminator
import org.nanokvm.mobile.runtime.createPasswordChangeCoordinator
import org.nanokvm.mobile.runtime.NanoKvmWifiAccessPointOnboardingGateway
import org.nanokvm.mobile.runtime.NanoKvmWifiAccessPointOnboardingResult
import org.nanokvm.mobile.runtime.NanoKvmWifiAccessPointOnboardingSessionFactory
import org.nanokvm.mobile.platform.LocalNetworkAccess
import org.nanokvm.mobile.security.SavedCredentialStore
import org.nanokvm.mobile.security.SavedCredentials
import org.nanokvm.mobile.security.CredentialPromptKind
import org.nanokvm.mobile.security.CredentialPromptRequest
import org.nanokvm.mobile.security.CredentialPromptResult
import org.nanokvm.mobile.security.StagedCredential

sealed interface AppScreen {
    data object Profiles : AppScreen
    data object WifiAccessPointOnboarding : AppScreen
    data class EditProfile(val profile: HostProfile, val isNew: Boolean) : AppScreen
    data class Connecting(val profile: HostProfile) : AppScreen
    data class ReviewCertificate(
        val profile: HostProfile,
        val certificate: CertificateDetails,
    ) : AppScreen
    data class Console(val profile: HostProfile) : AppScreen
}

enum class WifiAccessPointOnboardingNoticeKind { Information, Applied, Indeterminate, Rejected }

data class WifiAccessPointOnboardingUiState(
    val operationInProgress: Boolean = false,
    val noticeKind: WifiAccessPointOnboardingNoticeKind =
        WifiAccessPointOnboardingNoticeKind.Information,
    /** App-authored text only; endpoints, SSIDs, and passwords never enter this state. */
    val notice: String? = null,
)

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
    val localNetworkPermission: LocalNetworkPermissionUiState? = null,
    /** Memory-only ACTION_SEND text awaiting destination-bound review; never persisted. */
    val pendingSharedPaste: ClipboardPayload? = null,
    val wifiAccessPointOnboarding: WifiAccessPointOnboardingUiState =
        WifiAccessPointOnboardingUiState(),
    /** Changes on every genuine background transition to clear composable-only sensitive work. */
    val sensitiveWorkGeneration: Long = 0,
    val errorMessage: String? = null,
)

sealed interface LocalNetworkPermissionUiState {
    data class Rationale(val profile: HostProfile) : LocalNetworkPermissionUiState
    data class Requesting(val profile: HostProfile) : LocalNetworkPermissionUiState
    data class Denied(
        val profile: HostProfile,
        val canRequestAgain: Boolean,
    ) : LocalNetworkPermissionUiState
    data object AccessPointRationale : LocalNetworkPermissionUiState
    data object AccessPointRequesting : LocalNetworkPermissionUiState
    data class AccessPointDenied(val canRequestAgain: Boolean) :
        LocalNetworkPermissionUiState
}

sealed interface ProfileStorageIssue {
    val userMessage: String

    data class Corrupted(override val userMessage: String) : ProfileStorageIssue
    data class Unavailable(override val userMessage: String) : ProfileStorageIssue
}

class AppViewModel internal constructor(
    private val profilesRepository: ProfilesRepository,
    private val appSettingsStore: AppSettingsStore,
    private val backend: ConsoleBackend,
    private val savedCredentialStore: SavedCredentials,
    credentialResults: Flow<CredentialPromptResult>,
    private val savedStateHandle: SavedStateHandle,
    private val localNetworkAccess: LocalNetworkAccess,
    private val wifiAccessPointOnboardingSessionFactory:
        NanoKvmWifiAccessPointOnboardingSessionFactory =
        NanoKvmProtocolWifiAccessPointOnboardingSessionFactory,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AppUiState(screen = RestorableAppScreenState.restore(savedStateHandle)),
    )
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    val remoteInput: RemoteInputSink get() = backend
    val videoSurface: VideoSurfaceSink get() = backend
    val consoleCommands: ConsoleCommandSink get() = backend

    private val attempts = ConnectionAttemptSlot()
    private var connectJob: Job? = null
    private var profileCollectionJob: Job? = null
    private var nextCredentialRequestId = 0L
    private var pendingCredentialAction: PendingCredentialAction? = null
    private var pendingPasswordChange: PendingPasswordChange? = null
    private var passwordChangeJob: Job? = null
    private var activePasswordChangeCoordinator: NanoKvmPasswordChangeCoordinator? = null
    private var pendingTrustIntent: PendingTrustIntent? = null
    private var foreground = true
    private var wifiAccessPointOnboardingGeneration = 0L
    private var activeWifiAccessPointOnboardingGeneration: Long? = null
    private var wifiAccessPointOnboardingGateway: NanoKvmWifiAccessPointOnboardingGateway? = null
    private var wifiAccessPointOnboardingJob: Job? = null

    init {
        startProfileCollection()
        viewModelScope.launch {
            appSettingsStore.settings.collect(::applyAppSettings)
        }
        viewModelScope.launch {
            backend.session.collect { session ->
                mutableState.update { it.copy(backendSession = session) }
            }
        }
        viewModelScope.launch {
            credentialResults.collect(::handleCredentialPromptResult)
        }
    }

    fun setScrollSensitivity(sensitivity: Float) {
        val normalizedSensitivity = normalizeScrollSensitivity(sensitivity)
        mutableState.update { it.copy(scrollSensitivity = normalizedSensitivity) }
        viewModelScope.launch {
            try {
                appSettingsStore.setScrollSensitivity(normalizedSensitivity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(errorMessage = "Scroll sensitivity could not be saved.")
                }
            }
        }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        mutableState.update { it.copy(themeMode = themeMode) }
        viewModelScope.launch {
            try {
                appSettingsStore.setThemeMode(themeMode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update { it.copy(errorMessage = "Theme preference could not be saved.") }
            }
        }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        mutableState.update { it.copy(useDynamicColor = enabled) }
        viewModelScope.launch {
            try {
                appSettingsStore.setUseDynamicColor(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(errorMessage = "Device colour preference could not be saved.")
                }
            }
        }
    }

    fun setMjpegFrameDetectionEnabled(enabled: Boolean) {
        if (mutableState.value.mjpegFrameDetectionEnabled == enabled) return
        mutableState.update { it.copy(mjpegFrameDetectionEnabled = enabled) }
        // Preference collection is local-only; this call is the sole explicit appliance write.
        backend.setMjpegFrameDetectionPreference(enabled)
        backend.setMjpegFrameDetectionEnabled(enabled)
        viewModelScope.launch {
            try {
                appSettingsStore.setMjpegFrameDetectionEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        errorMessage =
                            "MJPEG frame-detection preference could not be saved on this device.",
                    )
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
                    is ProfileCatalogState.Corrupted -> mutableState.update {
                        it.copy(
                            profiles = emptyList(),
                            profileCatalogResolved = true,
                            savedPasswordProfileIds = emptySet(),
                            profileStorageIssue = ProfileStorageIssue.Corrupted(catalog.userMessage),
                            profileStorageBusy = false,
                        )
                    }
                    is ProfileCatalogState.Unavailable -> mutableState.update {
                        it.copy(
                            profiles = emptyList(),
                            profileCatalogResolved = true,
                            savedPasswordProfileIds = emptySet(),
                            profileStorageIssue = ProfileStorageIssue.Unavailable(catalog.userMessage),
                            profileStorageBusy = false,
                        )
                    }
                }
            }
        }
    }

    private fun profileCatalogIsActionable(): Boolean = mutableState.value.run {
        profileCatalogResolved && profileStorageIssue == null && !profileStorageBusy
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
        mutableState.update {
            it.copy(
                screen = screen,
                errorMessage = null,
            )
        }
    }

    fun openWifiAccessPointOnboarding() {
        if (!profileCatalogIsActionable()) return
        invalidateWifiAccessPointOnboarding()
        val generation = ++wifiAccessPointOnboardingGeneration
        activeWifiAccessPointOnboardingGeneration = generation
        wifiAccessPointOnboardingGateway = NanoKvmWifiAccessPointOnboardingGateway(
            generation = generation,
            currentGeneration = {
                if (
                    foreground &&
                    mutableState.value.screen == AppScreen.WifiAccessPointOnboarding &&
                    activeWifiAccessPointOnboardingGeneration == generation
                ) {
                    generation
                } else {
                    null
                }
            },
            sessionFactory = wifiAccessPointOnboardingSessionFactory,
        )
        persistRestorableScreen(AppScreen.Profiles)
        mutableState.update {
            it.copy(
                screen = AppScreen.WifiAccessPointOnboarding,
                wifiAccessPointOnboarding = WifiAccessPointOnboardingUiState(),
                localNetworkPermission = if (localNetworkAccess.isGranted()) {
                    null
                } else {
                    LocalNetworkPermissionUiState.AccessPointRationale
                },
                errorMessage = null,
            )
        }
    }

    /** Ownership of both password arrays transfers here and every terminal path clears them. */
    fun connectWifiAccessPoint(
        endpoint: String,
        apPassword: CharArray,
        targetSsid: String,
        targetPassword: CharArray,
    ) {
        val generation = activeWifiAccessPointOnboardingGeneration
        val gateway = wifiAccessPointOnboardingGateway
        if (
            generation == null || gateway == null || !foreground ||
            mutableState.value.screen != AppScreen.WifiAccessPointOnboarding ||
            wifiAccessPointOnboardingJob != null
        ) {
            apPassword.fill('\u0000')
            targetPassword.fill('\u0000')
            return
        }
        if (!localNetworkAccess.isGranted()) {
            apPassword.fill('\u0000')
            targetPassword.fill('\u0000')
            mutableState.update {
                it.copy(
                    localNetworkPermission =
                        LocalNetworkPermissionUiState.AccessPointRationale,
                    wifiAccessPointOnboarding = WifiAccessPointOnboardingUiState(
                        noticeKind = WifiAccessPointOnboardingNoticeKind.Rejected,
                        notice = "Allow local-network access before starting AP onboarding.",
                    ),
                )
            }
            return
        }
        lateinit var created: Job
        created = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = gateway.connect(endpoint, apPassword, targetSsid, targetPassword)
                if (activeWifiAccessPointOnboardingGeneration != generation || !foreground) return@launch
                mutableState.update { current ->
                    if (current.screen != AppScreen.WifiAccessPointOnboarding) current else {
                        current.copy(
                            wifiAccessPointOnboarding = when (result) {
                                NanoKvmWifiAccessPointOnboardingResult.Applied ->
                                    WifiAccessPointOnboardingUiState(
                                        noticeKind = WifiAccessPointOnboardingNoticeKind.Applied,
                                        notice = "NanoKVM accepted the network request once. Join the target network and connect with a normal profile.",
                                    )
                                is NanoKvmWifiAccessPointOnboardingResult.Indeterminate ->
                                    WifiAccessPointOnboardingUiState(
                                        noticeKind = WifiAccessPointOnboardingNoticeKind.Indeterminate,
                                        notice = "The final network state is unknown. The request was not replayed; check the target network before trying again.",
                                    )
                                is NanoKvmWifiAccessPointOnboardingResult.Rejected ->
                                    WifiAccessPointOnboardingUiState(
                                        noticeKind = WifiAccessPointOnboardingNoticeKind.Rejected,
                                        notice = "AP onboarding was rejected or could not be established. Check the endpoint and manually entered credentials.",
                                    )
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                apPassword.fill('\u0000')
                targetPassword.fill('\u0000')
                if (wifiAccessPointOnboardingJob === created) {
                    wifiAccessPointOnboardingJob = null
                    mutableState.update { current ->
                        if (current.screen == AppScreen.WifiAccessPointOnboarding) {
                            current.copy(
                                wifiAccessPointOnboarding =
                                    current.wifiAccessPointOnboarding.copy(
                                        operationInProgress = false,
                                    ),
                            )
                        } else {
                            current
                        }
                    }
                }
            }
        }
        wifiAccessPointOnboardingJob = created
        mutableState.update {
            it.copy(
                wifiAccessPointOnboarding = WifiAccessPointOnboardingUiState(
                    operationInProgress = true,
                ),
            )
        }
        created.start()
    }

    fun editProfile(profile: HostProfile) {
        if (!profileCatalogIsActionable()) return
        val screen = AppScreen.EditProfile(profile, isNew = false)
        persistRestorableScreen(screen)
        mutableState.update {
            it.copy(screen = screen, errorMessage = null)
        }
    }

    fun saveProfile(profile: HostProfile) {
        if (!profileCatalogIsActionable()) return
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
                        mutableState.update {
                            it.copy(
                                errorMessage =
                                    "Remove the saved password before changing the address, HTTPS setting, port, or username.",
                            )
                        }
                        return@launch
                    }
                }
                profilesRepository.upsert(profile)
                persistRestorableScreen(AppScreen.Profiles)
                mutableState.update { it.copy(screen = AppScreen.Profiles, errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(errorMessage = "The connection was not saved; local storage remains recoverable.")
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
                    mutableState.update {
                        it.copy(screen = updatedScreen, errorMessage = null)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        errorMessage =
                            "The saved certificate decision was not changed; local storage remains recoverable.",
                    )
                }
            }
        }
    }

    fun deleteProfile(profile: HostProfile) {
        if (!profileCatalogIsActionable()) return
        viewModelScope.launch {
            try {
                // Profile deletion is intentionally blocked if credential removal cannot be proven.
                savedCredentialStore.delete(profile.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(errorMessage = "The connection was not deleted because local credential removal could not be verified.")
                }
                return@launch
            }
            try {
                profilesRepository.delete(profile.id)
                persistRestorableScreen(AppScreen.Profiles)
                mutableState.update { it.copy(screen = AppScreen.Profiles, errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(errorMessage = "The protected password was removed, but the connection record could not be deleted.")
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
                        errorMessage = null,
                    )
                }
                startProfileCollection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        profileStorageBusy = false,
                        errorMessage = "Saved connections and credentials could not be reset completely. No recovery was claimed.",
                    )
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
                errorMessage = null,
            )
        }
        startProfileCollection()
    }

    fun prepareConnection(profile: HostProfile) {
        if (!profileCatalogIsActionable()) return
        if (mutableState.value.credentialPrompt != null || pendingCredentialAction != null) return
        if (!profile.useHttps) {
            mutableState.update {
                it.copy(errorMessage = "This connection must be upgraded to HTTPS before connecting.")
            }
            return
        }
        if (!localNetworkAccess.isGranted()) {
            mutableState.update {
                it.copy(
                    localNetworkPermission = LocalNetworkPermissionUiState.Rationale(profile),
                    errorMessage = null,
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
            LocalNetworkPermissionUiState.AccessPointRationale -> mutableState.update {
                it.copy(localNetworkPermission = LocalNetworkPermissionUiState.AccessPointRequesting)
            }
            else -> Unit
        }
    }

    fun onLocalNetworkPermissionResult(granted: Boolean, canRequestAgain: Boolean) {
        val request = mutableState.value.localNetworkPermission ?: return
        if (granted && localNetworkAccess.isGranted()) {
            mutableState.update { it.copy(localNetworkPermission = null, errorMessage = null) }
            if (request is LocalNetworkPermissionUiState.Rationale ||
                request is LocalNetworkPermissionUiState.Requesting ||
                request is LocalNetworkPermissionUiState.Denied
            ) {
                val profile = when (request) {
                    is LocalNetworkPermissionUiState.Rationale -> request.profile
                    is LocalNetworkPermissionUiState.Requesting -> request.profile
                    is LocalNetworkPermissionUiState.Denied -> request.profile
                    else -> error("unreachable")
                }
                prepareConnection(profile)
            }
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
                    LocalNetworkPermissionUiState.AccessPointRationale,
                    LocalNetworkPermissionUiState.AccessPointRequesting,
                    is LocalNetworkPermissionUiState.AccessPointDenied ->
                        LocalNetworkPermissionUiState.AccessPointDenied(canRequestAgain)
                },
                errorMessage = null,
            )
        }
    }

    fun retryLocalNetworkPermission() {
        when (val denied = mutableState.value.localNetworkPermission) {
            is LocalNetworkPermissionUiState.Denied -> mutableState.update {
                it.copy(localNetworkPermission = LocalNetworkPermissionUiState.Rationale(denied.profile))
            }
            is LocalNetworkPermissionUiState.AccessPointDenied -> mutableState.update {
                it.copy(localNetworkPermission = LocalNetworkPermissionUiState.AccessPointRationale)
            }
            else -> Unit
        }
    }

    fun refreshLocalNetworkAccess(canRequestAgain: Boolean) {
        val currentState = mutableState.value
        val request = currentState.localNetworkPermission
        if (request != null && localNetworkAccess.isGranted()) {
            mutableState.update { it.copy(localNetworkPermission = null, errorMessage = null) }
            when (request) {
                is LocalNetworkPermissionUiState.Rationale -> prepareConnection(request.profile)
                is LocalNetworkPermissionUiState.Requesting -> prepareConnection(request.profile)
                is LocalNetworkPermissionUiState.Denied -> prepareConnection(request.profile)
                LocalNetworkPermissionUiState.AccessPointRationale,
                LocalNetworkPermissionUiState.AccessPointRequesting,
                is LocalNetworkPermissionUiState.AccessPointDenied -> Unit
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
        if (request == LocalNetworkPermissionUiState.AccessPointRequesting) {
            mutableState.update {
                it.copy(
                    localNetworkPermission =
                        LocalNetworkPermissionUiState.AccessPointDenied(canRequestAgain),
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
                errorMessage = null,
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
                    errorMessage = null,
                )
            }
        } else {
            mutableState.update {
                it.copy(
                    screen = AppScreen.Profiles,
                    passwordEntryProfile = profile,
                    errorMessage = null,
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
        mutableState.update { it.copy(passwordEntryProfile = null, errorMessage = null) }
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
                mutableState.update {
                    it.copy(
                        passwordEntryProfile = profile,
                        errorMessage = "Android could not create a protected credential key.",
                    )
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
            mutableState.update {
                it.copy(
                    screen = AppScreen.Profiles,
                    errorMessage = "This connection must be upgraded to HTTPS before connecting.",
                )
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
                    errorMessage = null,
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
                    mutableState.update {
                        it.copy(
                            screen = AppScreen.Profiles,
                            errorMessage = "The certificate decision could not be saved.",
                        )
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
        invalidateWifiAccessPointOnboarding()
        cancelPasswordChange()
        connectJob?.cancel()
        connectJob = null
        clearActiveAttempt()
        clearPendingCredentialAction()
        pendingTrustIntent = null
        persistRestorableScreen(AppScreen.Profiles)
        runCatching { backend.releaseAllInput() }
        mutableState.update {
            it.copy(
                screen = AppScreen.Profiles,
                credentialPrompt = null,
                passwordEntryProfile = null,
                localNetworkPermission = null,
                errorMessage = null,
            )
        }
    }

    fun disconnect() {
        cancelPasswordChange()
        connectJob?.cancel()
        connectJob = null
        clearActiveAttempt()
        pendingTrustIntent = null
        persistRestorableScreen(AppScreen.Profiles)
        runCatching { backend.releaseAllInput() }
        viewModelScope.launch {
            try {
                backend.disconnect()
                mutableState.update { it.copy(screen = AppScreen.Profiles, errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        screen = AppScreen.Profiles,
                        errorMessage = "Could not disconnect cleanly. The session was closed locally.",
                    )
                }
            }
        }
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        if (!foreground) invalidateWifiAccessPointOnboarding()
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
        invalidateWifiAccessPointOnboarding()
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
            interruptedScreen is AppScreen.ReviewCertificate ||
            interruptedScreen == AppScreen.WifiAccessPointOnboarding
        if (mustReturnToProfiles) persistRestorableScreen(AppScreen.Profiles)
        mutableState.update {
            it.copy(
                screen = if (mustReturnToProfiles) AppScreen.Profiles else it.screen,
                credentialPrompt = if (promptInProgress) it.credentialPrompt else null,
                passwordEntryProfile = null,
                pendingSharedPaste = null,
                sensitiveWorkGeneration = it.sensitiveWorkGeneration + 1,
                errorMessage = null,
            )
        }
    }

    fun releaseAllInput() {
        backend.releaseAllInput()
    }

    fun clearError() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    fun receiveSharedPlainText(payload: ClipboardPayload) {
        if (payload.text.isEmpty()) {
            mutableState.update { it.copy(errorMessage = "Shared text is empty.") }
            return
        }
        mutableState.update { it.copy(pendingSharedPaste = payload) }
    }

    fun consumeSharedPlainText(payload: ClipboardPayload) {
        mutableState.update {
            if (it.pendingSharedPaste === payload) it.copy(pendingSharedPaste = null) else it
        }
    }

    fun reportError(message: String) {
        mutableState.update { it.copy(errorMessage = message) }
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
                mutableState.update { it.copy(savedPasswordProfileIds = ids, errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(errorMessage = "Android could not verify that the protected password was removed.")
                }
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
        val owner = backend as? NanoKvmPasswordChangeFeatureOwner
        if (
            profile == null || owner == null || !foreground ||
            passwordChangeJob != null || pendingPasswordChange != null ||
            pendingCredentialAction != null || mutableState.value.credentialPrompt != null
        ) {
            password.fill('\u0000')
            mutableState.update {
                it.copy(errorMessage = "Password change is not available for this session.")
            }
            return
        }
        val authenticationRequest = if (saveProtectedCredential) {
            newCredentialRequest(CredentialPromptKind.Save, profile)
        } else {
            null
        }
        val coordinator = owner.createPasswordChangeCoordinator(
            destination = destination,
            profile = profile,
            savedCredentials = savedCredentialStore,
            profilesRepository = profilesRepository,
            sessionTerminator = NanoKvmPasswordChangeSessionTerminator(
                ::terminatePasswordChangeSession,
            ),
        )
        if (coordinator == null) {
            password.fill('\u0000')
            mutableState.update {
                it.copy(
                    errorMessage =
                        "The authenticated destination changed. Review the password change again.",
                )
            }
            return
        }

        activePasswordChangeCoordinator = coordinator
        mutableState.update {
            it.copy(passwordChangeInProgress = true, errorMessage = null)
        }
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
            mutableState.update { it.copy(passwordEntryProfile = null, errorMessage = null) }
            return
        }
        when (result) {
            is CredentialPromptResult.Authenticated -> completeAuthenticatedCredential(action)
            is CredentialPromptResult.Cancelled -> {
                clearPendingCredentialAction(action)
                mutableState.update {
                    it.copy(passwordEntryProfile = action.profile, errorMessage = null)
                }
            }
            is CredentialPromptResult.Failed -> {
                clearPendingCredentialAction(action)
                mutableState.update {
                    it.copy(passwordEntryProfile = action.profile, errorMessage = result.message)
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
        val message = when (result) {
            is NanoKvmPasswordChangeResult.Changed ->
                if (result.localFailures.isEmpty()) {
                    "NanoKVM credentials changed. Reconnect with the new credentials."
                } else {
                    passwordChangeLocalFailureMessage(result.localFailures)
                }
            is NanoKvmPasswordChangeResult.ManualVerificationRequired ->
                "The password-change outcome is unknown. The session ended and the saved password was invalidated; reconnect manually and verify which credentials are active."
            is NanoKvmPasswordChangeResult.Rejected ->
                "NanoKVM rejected the password change. The existing session and saved password were preserved."
            NanoKvmPasswordChangeResult.AuthenticationExpired ->
                "The NanoKVM session expired. Sign in again; the saved password was not changed."
            NanoKvmPasswordChangeResult.InvalidRequest ->
                "The username or password is not valid for NanoKVM. Nothing was sent."
            NanoKvmPasswordChangeResult.AuthenticationCancelled -> null
            NanoKvmPasswordChangeResult.AuthenticationFailed ->
                "Android authentication failed. Nothing was sent to NanoKVM."
            NanoKvmPasswordChangeResult.LocalPreparationFailed ->
                "Android could not prepare the protected replacement. Nothing was sent to NanoKVM."
            NanoKvmPasswordChangeResult.StaleSession ->
                "The authenticated destination changed. Review the password change again."
            NanoKvmPasswordChangeResult.Busy ->
                "Another password change is already in progress."
            NanoKvmPasswordChangeResult.IgnoredAuthenticationResult -> null
            is NanoKvmPasswordChangeResult.AuthenticationRequired -> null
        }
        mutableState.update {
            it.copy(
                credentialPrompt = null,
                passwordChangeInProgress = false,
                savedPasswordProfileIds = ids,
                errorMessage = message,
            )
        }
    }

    private fun passwordChangeLocalFailureMessage(
        failures: Set<NanoKvmPasswordChangeLocalFailure>,
    ): String = when {
        NanoKvmPasswordChangeLocalFailure.SESSION_END in failures ->
            "Credentials changed, but Android could not verify a clean disconnect. Reconnect manually before doing anything else."
        NanoKvmPasswordChangeLocalFailure.PROFILE_UPDATE in failures ->
            "Credentials changed and the session ended, but the saved profile could not be updated. Edit the username before reconnecting."
        NanoKvmPasswordChangeLocalFailure.CREDENTIAL_COMMIT in failures ||
            NanoKvmPasswordChangeLocalFailure.CREDENTIAL_DELETE in failures ->
            "Credentials changed and the session ended, but Android could not safely retain the replacement password. Enter it manually when reconnecting."
        else -> "Credentials changed and the session ended. Verify the local profile before reconnecting."
    }

    private suspend fun terminatePasswordChangeSession(
        reason: NanoKvmPasswordChangeSessionEndReason,
    ) {
        var disconnectFailure: Throwable? = null
        try {
            backend.disconnect()
        } catch (cancelled: CancellationException) {
            disconnectFailure = cancelled
        } catch (error: Throwable) {
            disconnectFailure = error
        } finally {
            persistRestorableScreen(AppScreen.Profiles)
            mutableState.update {
                it.copy(
                    screen = AppScreen.Profiles,
                    credentialPrompt = null,
                    passwordEntryProfile = null,
                    pendingSharedPaste = null,
                    errorMessage = if (
                        reason ==
                            NanoKvmPasswordChangeSessionEndReason.OUTCOME_REQUIRES_MANUAL_VERIFICATION
                    ) {
                        "Reconnect manually and verify which credentials are active."
                    } else {
                        it.errorMessage
                    },
                )
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
                    mutableState.update {
                        it.copy(
                            savedPasswordProfileIds = ids,
                            passwordEntryProfile = action.profile,
                            errorMessage = if (deletionSucceeded) {
                                "The saved password could not be unlocked. Enter it again."
                            } else {
                                "The saved password could not be unlocked or removed safely. Enter the password manually."
                            },
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
                    mutableState.update {
                        it.copy(
                            passwordEntryProfile = action.profile,
                            errorMessage = "The password could not be protected by Android Keystore.",
                        )
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
                    mutableState.update {
                        it.copy(screen = AppScreen.Profiles, errorMessage = null)
                    }
                }
                throw cancelled
            } catch (_: Throwable) {
                if (finishAttemptIfOwned(attempt, request)) {
                    mutableState.update {
                        it.copy(
                            screen = AppScreen.Profiles,
                            errorMessage = UNEXPECTED_CONNECTION_ERROR,
                        )
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
                        errorMessage = null,
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
                            errorMessage = null,
                        )
                    }
                    is TrustPreflightOutcome.Failed -> {
                        pendingTrustIntent = null
                        mutableState.update {
                            it.copy(screen = AppScreen.Profiles, errorMessage = outcome.userMessage)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (pendingTrustIntent === intent) {
                    pendingTrustIntent = null
                    mutableState.update {
                        it.copy(
                            screen = AppScreen.Profiles,
                            errorMessage = "The HTTPS certificate could not be checked.",
                        )
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
        mutableState.update {
            it.copy(screen = AppScreen.Connecting(request.profile), errorMessage = null)
        }
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
                mutableState.update {
                    it.copy(
                        screen = AppScreen.Console(request.profile),
                        savedPasswordProfileIds = savedPasswordProfileIds,
                        errorMessage = if (saveError == null) {
                            null
                        } else {
                            "Connected, but Android could not save the protected password."
                        },
                    )
                }
            }
            is ConnectOutcome.CertificateReviewRequired -> mutableState.update {
                it.copy(
                    screen = AppScreen.ReviewCertificate(request.profile, outcome.certificate),
                    errorMessage = null,
                )
            }
            is ConnectOutcome.Failed -> {
                // A failed replacement never overwrites the last working saved credential.
                if (!finishAttemptIfOwned(attempt, request)) return
                mutableState.update {
                    it.copy(screen = AppScreen.Profiles, errorMessage = outcome.userMessage)
                }
            }
        }
    }

    override fun onCleared() {
        invalidateWifiAccessPointOnboarding()
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

    private fun invalidateWifiAccessPointOnboarding() {
        activeWifiAccessPointOnboardingGeneration = null
        wifiAccessPointOnboardingGateway = null
        wifiAccessPointOnboardingJob?.cancel(
            CancellationException("Wi-Fi AP onboarding generation invalidated"),
        )
        wifiAccessPointOnboardingJob = null
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

    private companion object {
        const val UNEXPECTED_CONNECTION_ERROR =
            "The NanoKVM connection stopped unexpectedly. Please try again."
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
            AppScreen.WifiAccessPointOnboarding -> persist(savedStateHandle, AppScreen.Profiles)
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

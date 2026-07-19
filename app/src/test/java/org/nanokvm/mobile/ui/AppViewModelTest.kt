package org.nanokvm.mobile.ui

import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.clipboard.ClipboardPayloadAnalyzer
import org.nanokvm.mobile.data.AppSettings
import org.nanokvm.mobile.data.AppSettingsStore
import org.nanokvm.mobile.data.ProfileCatalogState
import org.nanokvm.mobile.data.ProfilesRepository
import org.nanokvm.mobile.data.ThemeMode
import org.nanokvm.mobile.platform.LocalNetworkAccess
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.ApprovedAdministrationDestination
import org.nanokvm.mobile.runtime.ApprovedPasteRequest
import org.nanokvm.mobile.runtime.CertificateDetails
import org.nanokvm.mobile.runtime.CertificateTrustSource
import org.nanokvm.mobile.runtime.ConnectOutcome
import org.nanokvm.mobile.runtime.ConnectRequest
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.NanoKvmAdministrationAccountSnapshot
import org.nanokvm.mobile.runtime.NanoKvmAdministrationError
import org.nanokvm.mobile.runtime.NanoKvmAdministrationGuidance
import org.nanokvm.mobile.runtime.NanoKvmAdministrationImpact
import org.nanokvm.mobile.runtime.NanoKvmAdministrationMutationResult
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeCoordinator
import org.nanokvm.mobile.runtime.NanoKvmPasswordChangeFeatureOwner
import org.nanokvm.mobile.runtime.NanoKvmPasswordMutation
import org.nanokvm.mobile.runtime.NanoKvmSessionBinding
import org.nanokvm.mobile.runtime.passwordChangeFactoryRequestOrNull
import org.nanokvm.mobile.runtime.PowerAction
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.TrustPreflightOutcome
import org.nanokvm.mobile.runtime.VideoSettings
import org.nanokvm.mobile.security.CredentialPromptKind
import org.nanokvm.mobile.security.CredentialPromptResult
import org.nanokvm.mobile.security.SavedCredentials
import org.nanokvm.mobile.security.StagedCredential

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun scrollSensitivityIsCollectedAndPersistedThroughAppSettings() = runTest(dispatcher) {
        val settings = FakeAppSettingsStore(AppSettings(scrollSensitivity = 1.5f))
        val viewModel = viewModel(
            FakeProfilesRepository(ProfileCatalogState.Ready(emptyList())),
            FakeSavedCredentials(),
            FakeConsoleBackend(),
            appSettings = settings,
        )
        advanceUntilIdle()

        assertEquals(1.5f, viewModel.state.value.scrollSensitivity)

        viewModel.setScrollSensitivity(2.5f)
        advanceUntilIdle()

        assertEquals(listOf(2.5f), settings.savedSensitivities)
        assertEquals(2.5f, viewModel.state.value.scrollSensitivity)
    }

    @Test
    fun appearanceIsCollectedAndPersistedThroughAppSettings() = runTest(dispatcher) {
        val settings = FakeAppSettingsStore(
            AppSettings(themeMode = ThemeMode.LIGHT, useDynamicColor = false),
        )
        val viewModel = viewModel(
            FakeProfilesRepository(ProfileCatalogState.Ready(emptyList())),
            FakeSavedCredentials(),
            FakeConsoleBackend(),
            appSettings = settings,
        )
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, viewModel.state.value.themeMode)
        assertFalse(viewModel.state.value.useDynamicColor)

        viewModel.setThemeMode(ThemeMode.DARK)
        viewModel.setUseDynamicColor(true)
        advanceUntilIdle()

        assertEquals(listOf(ThemeMode.DARK), settings.savedThemeModes)
        assertEquals(listOf(true), settings.savedDynamicColorValues)
        assertEquals(ThemeMode.DARK, viewModel.state.value.themeMode)
        assertTrue(viewModel.state.value.useDynamicColor)
    }

    @Test
    fun frameDetectionCollectionIsLocalAndEachUserChangeWritesAndPersistsOnce() =
        runTest(dispatcher) {
            val settings = FakeAppSettingsStore(
                AppSettings(mjpegFrameDetectionEnabled = true),
            )
            val backend = FakeConsoleBackend()
            val viewModel = viewModel(
                FakeProfilesRepository(ProfileCatalogState.Ready(emptyList())),
                FakeSavedCredentials(),
                backend,
                appSettings = settings,
            )
            advanceUntilIdle()

            assertTrue(viewModel.state.value.mjpegFrameDetectionEnabled)
            assertEquals(true, backend.frameDetectionPreferences.last())
            assertEquals(emptyList<Boolean>(), backend.frameDetectionWrites)

            viewModel.setMjpegFrameDetectionEnabled(false)
            advanceUntilIdle()
            viewModel.setMjpegFrameDetectionEnabled(false)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.mjpegFrameDetectionEnabled)
            assertEquals(listOf(false), settings.savedFrameDetectionValues)
            assertEquals(listOf(false), backend.frameDetectionWrites)
            assertEquals(false, backend.frameDetectionPreferences.last())
        }

    @Test
    fun localNetworkPermissionIsContextualAndConnectionResumesOnlyAfterGrant() =
        runTest(dispatcher) {
            val profile = HostProfile.Default
            val backend = FakeConsoleBackend()
            val localNetwork = FakeLocalNetworkAccess(granted = false)
            val viewModel = viewModel(
                FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile))),
                FakeSavedCredentials(),
                backend,
                localNetworkAccess = localNetwork,
            )
            advanceUntilIdle()

            viewModel.prepareConnection(profile)
            assertTrue(
                viewModel.state.value.localNetworkPermission is
                    LocalNetworkPermissionUiState.Rationale,
            )
            assertTrue(backend.events.isEmpty())

            viewModel.beginLocalNetworkPermissionRequest()
            assertTrue(
                viewModel.state.value.localNetworkPermission is
                    LocalNetworkPermissionUiState.Requesting,
            )
            localNetwork.granted = true
            viewModel.onLocalNetworkPermissionResult(granted = true, canRequestAgain = false)
            advanceUntilIdle()

            assertNull(viewModel.state.value.localNetworkPermission)
            assertEquals(listOf("preflight"), backend.events)
            assertEquals(profile, viewModel.state.value.passwordEntryProfile)
        }

    @Test
    fun localNetworkDenialIsRecoverableAndNeverStartsTransport() = runTest(dispatcher) {
        val profile = HostProfile.Default
        val backend = FakeConsoleBackend()
        val viewModel = viewModel(
            FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile))),
            FakeSavedCredentials(),
            backend,
            localNetworkAccess = FakeLocalNetworkAccess(granted = false),
        )
        advanceUntilIdle()

        viewModel.prepareConnection(profile)
        viewModel.beginLocalNetworkPermissionRequest()
        viewModel.onLocalNetworkPermissionResult(granted = false, canRequestAgain = true)

        val denied = viewModel.state.value.localNetworkPermission
            as LocalNetworkPermissionUiState.Denied
        assertTrue(denied.canRequestAgain)
        assertTrue(backend.events.isEmpty())

        viewModel.retryLocalNetworkPermission()
        assertTrue(
            viewModel.state.value.localNetworkPermission is LocalNetworkPermissionUiState.Rationale,
        )
    }

    @Test
    fun permissionRevokedBeforeTransportWipesPasswordAndReturnsToPermissionFlow() =
        runTest(dispatcher) {
            val profile = HostProfile.Default
            val backend = FakeConsoleBackend()
            val localNetwork = FakeLocalNetworkAccess(granted = true)
            val viewModel = viewModel(
                FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile))),
                FakeSavedCredentials(),
                backend,
                localNetworkAccess = localNetwork,
            )
            advanceUntilIdle()
            viewModel.prepareConnection(profile)
            advanceUntilIdle()
            val password = "revoked-secret".toCharArray()

            localNetwork.granted = false
            viewModel.submitPassword(profile, password, savePassword = false)
            advanceUntilIdle()

            assertTrue(password.all { it == '\u0000' })
            assertEquals(listOf("preflight"), backend.events)
            assertTrue(
                viewModel.state.value.localNetworkPermission is
                    LocalNetworkPermissionUiState.Rationale,
            )
            assertTrue(viewModel.state.value.screen is AppScreen.Profiles)
        }

    @Test
    fun permissionRevokedDuringConsoleClosesSessionAndShowsRecovery() = runTest(dispatcher) {
        val profile = HostProfile.Default
        val backend = FakeConsoleBackend()
        val localNetwork = FakeLocalNetworkAccess(granted = true)
        val viewModel = viewModel(
            FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile))),
            FakeSavedCredentials(),
            backend,
            localNetworkAccess = localNetwork,
        )
        advanceUntilIdle()
        viewModel.prepareConnection(profile)
        advanceUntilIdle()
        viewModel.submitPassword(profile, "test-password".toCharArray(), savePassword = false)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.screen is AppScreen.Console)

        localNetwork.granted = false
        viewModel.refreshLocalNetworkAccess(canRequestAgain = false)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.screen is AppScreen.Profiles)
        val denied = viewModel.state.value.localNetworkPermission
            as LocalNetworkPermissionUiState.Denied
        assertFalse(denied.canRequestAgain)
        assertEquals(listOf("preflight", "connect", "disconnect"), backend.events)
    }

    @Test
    fun certificatePreflightFinishesBeforeSavedPasswordUnlock() = runTest(dispatcher) {
        val profile = HostProfile.Default
        val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
        val credentials = FakeSavedCredentials(savedIds = mutableSetOf(profile.id))
        val certificate = certificate()
        val backend = FakeConsoleBackend(
            trustOutcome = TrustPreflightOutcome.CertificateReviewRequired(certificate),
        )
        val promptResults = MutableSharedFlow<CredentialPromptResult>(extraBufferCapacity = 1)
        val viewModel = viewModel(repository, credentials, backend, promptResults)
        advanceUntilIdle()

        viewModel.prepareConnection(profile)
        advanceUntilIdle()

        assertEquals(listOf("preflight"), backend.events)
        assertEquals(0, credentials.unlockCalls)
        assertTrue(viewModel.state.value.screen is AppScreen.ReviewCertificate)
        assertNull(viewModel.state.value.credentialPrompt)

        viewModel.trustCertificate(persist = false)
        advanceUntilIdle()
        val request = viewModel.state.value.credentialPrompt
        assertNotNull(request)
        assertEquals(CredentialPromptKind.Unlock, request?.kind)
        assertEquals(0, credentials.unlockCalls)

        promptResults.emit(CredentialPromptResult.Authenticated(checkNotNull(request).id))
        advanceUntilIdle()

        assertEquals(1, credentials.unlockCalls)
        assertEquals(listOf("preflight", "connect"), backend.events)
        assertTrue(viewModel.state.value.screen is AppScreen.Console)
    }

    @Test
    fun changedCertificateIsReplacedOnlyWhenRememberingTheDecision() = runTest(dispatcher) {
        val profile = HostProfile.Default.copy(trustedCertificateSha256 = "11:22")
        val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
        val certificate = certificate().copy(sha256 = "AA:BB")
        val viewModel = viewModel(
            repository,
            FakeSavedCredentials(),
            FakeConsoleBackend(
                trustOutcome = TrustPreflightOutcome.CertificateReviewRequired(certificate),
            ),
        )
        advanceUntilIdle()

        viewModel.prepareConnection(profile)
        advanceUntilIdle()
        viewModel.trustCertificate(persist = true)
        advanceUntilIdle()

        assertEquals(1, repository.upsertCalls)
        assertEquals("AA:BB", repository.upsertedProfiles.single().trustedCertificateSha256)
        assertEquals("AA:BB", viewModel.state.value.passwordEntryProfile?.trustedCertificateSha256)
    }

    @Test
    fun changedCertificateCanBeUsedOnceWithoutReplacingSavedPin() = runTest(dispatcher) {
        val profile = HostProfile.Default.copy(trustedCertificateSha256 = "11:22")
        val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
        val certificate = certificate().copy(sha256 = "AA:BB")
        val viewModel = viewModel(
            repository,
            FakeSavedCredentials(),
            FakeConsoleBackend(
                trustOutcome = TrustPreflightOutcome.CertificateReviewRequired(certificate),
            ),
        )
        advanceUntilIdle()

        viewModel.prepareConnection(profile)
        advanceUntilIdle()
        viewModel.trustCertificate(persist = false)
        advanceUntilIdle()

        assertEquals(0, repository.upsertCalls)
        assertEquals("AA:BB", viewModel.state.value.passwordEntryProfile?.trustedCertificateSha256)
    }

    @Test
    fun failedCredentialDeletionPreventsProfileDeletion() = runTest(dispatcher) {
        val profile = HostProfile.Default
        val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
        val credentials = FakeSavedCredentials(
            savedIds = mutableSetOf(profile.id),
            deleteFailure = IllegalStateException("keystore unavailable"),
        )
        val viewModel = viewModel(repository, credentials, FakeConsoleBackend())
        advanceUntilIdle()

        viewModel.deleteProfile(profile)
        advanceUntilIdle()

        assertEquals(0, repository.deleteCalls)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("not deleted"))
    }

    @Test
    fun corruptionIsExplicitAndResetAlsoRemovesOrphanableCredentials() = runTest(dispatcher) {
        val repository = FakeProfilesRepository(
            ProfileCatalogState.Corrupted("Saved connections are damaged."),
        )
        val credentials = FakeSavedCredentials(mutableSetOf("unknown-profile"))
        val viewModel = viewModel(repository, credentials, FakeConsoleBackend())
        advanceUntilIdle()

        assertTrue(viewModel.state.value.profiles.isEmpty())
        assertEquals(
            "Saved connections are damaged.",
            (viewModel.state.value.profileStorageIssue as ProfileStorageIssue.Corrupted).userMessage,
        )

        viewModel.resetProfileStorage()
        advanceUntilIdle()

        assertEquals(1, credentials.deleteAllCalls)
        assertEquals(1, repository.resetCalls)
        assertNull(viewModel.state.value.profileStorageIssue)
    }

    @Test
    fun unavailableStorageCanOnlyRetryAndNeverDeletesCredentials() = runTest(dispatcher) {
        val repository = FakeProfilesRepository(
            ProfileCatalogState.Unavailable("Saved connections cannot be read right now."),
        )
        val credentials = FakeSavedCredentials(mutableSetOf("existing"))
        val viewModel = viewModel(repository, credentials, FakeConsoleBackend())
        advanceUntilIdle()

        assertTrue(viewModel.state.value.profileStorageIssue is ProfileStorageIssue.Unavailable)
        viewModel.resetProfileStorage()
        advanceUntilIdle()
        assertEquals(0, credentials.deleteAllCalls)
        assertEquals(0, repository.resetCalls)

        viewModel.retryProfileStorage()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.profileStorageIssue is ProfileStorageIssue.Unavailable)
        assertEquals(0, credentials.deleteAllCalls)
    }

    @Test
    fun profileIdentityChangeRequiresExplicitSavedPasswordRemoval() = runTest(dispatcher) {
        val profile = HostProfile.Default
        val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
        val credentials = FakeSavedCredentials(mutableSetOf(profile.id))
        val viewModel = viewModel(repository, credentials, FakeConsoleBackend())
        advanceUntilIdle()

        viewModel.saveProfile(profile.copy(host = "nanokvm-new.local"))
        advanceUntilIdle()

        assertEquals(0, repository.upsertCalls)
        assertEquals(0, credentials.deleteCalls)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("Remove the saved password"))
    }

    @Test
    fun forgettingCertificateUpdatesOnlyPersistedPinAndKeepsEditorOpen() = runTest(dispatcher) {
        val profile = HostProfile.Default.copy(
            name = "Persisted profile",
            host = "nanokvm.persisted.example",
            trustedCertificateSha256 = "AA:BB:CC:DD",
        )
        val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
        val viewModel = viewModel(repository, FakeSavedCredentials(), FakeConsoleBackend())
        advanceUntilIdle()
        viewModel.editProfile(profile)

        viewModel.forgetProfileCertificate(profile.id)
        advanceUntilIdle()

        val expected = profile.copy(trustedCertificateSha256 = null)
        assertEquals(listOf(expected), repository.upsertedProfiles)
        val editor = viewModel.state.value.screen as AppScreen.EditProfile
        assertEquals(expected, editor.profile)
        assertFalse(editor.isNew)
    }

    @Test
    fun syntheticDefaultCannotConnectBeforeProfileStorageResolves() = runTest(dispatcher) {
        val profile = HostProfile.Default
        val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
        val backend = FakeConsoleBackend()
        val viewModel = viewModel(repository, FakeSavedCredentials(), backend)

        assertFalse(viewModel.state.value.profileCatalogResolved)
        assertTrue(viewModel.state.value.profiles.isEmpty())
        viewModel.prepareConnection(profile)
        assertTrue(backend.events.isEmpty())

        advanceUntilIdle()
        assertTrue(viewModel.state.value.profileCatalogResolved)
    }

    @Test
    fun authenticatedCredentialResultWhileBackgroundedWipesPasswordWithoutConnecting() =
        runTest(dispatcher) {
            val profile = HostProfile.Default
            val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
            val credentials = FakeSavedCredentials()
            val backend = FakeConsoleBackend()
            val promptResults = MutableSharedFlow<CredentialPromptResult>(extraBufferCapacity = 1)
            val viewModel = viewModel(repository, credentials, backend, promptResults)
            advanceUntilIdle()
            val password = "background-secret".toCharArray()

            viewModel.submitPassword(profile, password, savePassword = true)
            advanceUntilIdle()
            val request = checkNotNull(viewModel.state.value.credentialPrompt)
            viewModel.clearSensitiveWorkForBackground()
            promptResults.emit(CredentialPromptResult.Authenticated(request.id))
            advanceUntilIdle()

            assertTrue(password.all { it == '\u0000' })
            assertFalse(backend.events.contains("connect"))
            assertNull(viewModel.state.value.credentialPrompt)
        }

    @Test
    fun acknowledgedUnsavedPasswordChangeUpdatesProfileDeletesCredentialAndDisconnects() =
        runTest(dispatcher) {
            val profile = HostProfile.Default
            val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
            val credentials = FakeSavedCredentials()
            val backend = FakeConsoleBackend()
            val viewModel = viewModel(repository, credentials, backend)
            advanceUntilIdle()
            connectConsole(viewModel, profile)
            credentials.markSaved(profile.id)
            val password = "replacement-password".toCharArray()

            viewModel.changeAdministrationPassword(
                administrationDestination(profile),
                "operator",
                password,
                saveProtectedCredential = false,
            )
            advanceUntilIdle()

            assertTrue(password.all { it == '\u0000' })
            assertEquals("operator", repository.upsertedProfiles.last().username)
            assertEquals(1, credentials.deleteCalls)
            assertEquals(1, backend.passwordDispatches)
            assertTrue(backend.events.contains("disconnect"))
            assertTrue(viewModel.state.value.screen is AppScreen.Profiles)
            assertFalse(viewModel.state.value.passwordChangeInProgress)
        }

    @Test
    fun protectedPasswordChangeAuthenticatesAndStagesBeforeDispatch() = runTest(dispatcher) {
        val profile = HostProfile.Default
        val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
        val credentials = FakeSavedCredentials()
        val backend = FakeConsoleBackend()
        val promptResults = MutableSharedFlow<CredentialPromptResult>(extraBufferCapacity = 1)
        val viewModel = viewModel(repository, credentials, backend, promptResults)
        advanceUntilIdle()
        connectConsole(viewModel, profile)
        val password = "replacement-password".toCharArray()

        viewModel.changeAdministrationPassword(
            administrationDestination(profile),
            "operator",
            password,
            saveProtectedCredential = true,
        )
        advanceUntilIdle()

        val request = checkNotNull(viewModel.state.value.credentialPrompt)
        assertEquals(CredentialPromptKind.Save, request.kind)
        assertEquals(0, backend.passwordDispatches)
        assertEquals(1, credentials.prepareCalls)
        assertEquals(0, credentials.stageCalls)

        promptResults.emit(CredentialPromptResult.Authenticated(request.id))
        advanceUntilIdle()

        assertEquals(1, credentials.stageCalls)
        assertEquals(1, backend.passwordDispatches)
        assertEquals(1, credentials.commitCalls)
        assertTrue(password.all { it == '\u0000' })
        assertTrue(viewModel.state.value.screen is AppScreen.Profiles)
    }

    @Test
    fun rejectedPasswordChangePreservesCredentialProfileAndConsoleSession() =
        runTest(dispatcher) {
            val profile = HostProfile.Default
            val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
            val credentials = FakeSavedCredentials()
            val backend = FakeConsoleBackend().apply {
                passwordMutationResult = NanoKvmAdministrationMutationResult.Rejected(
                    NanoKvmAdministrationError(
                        NanoKvmAdministrationError.Kind.SERVER_REJECTED,
                    ),
                    NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
                )
            }
            val viewModel = viewModel(repository, credentials, backend)
            advanceUntilIdle()
            connectConsole(viewModel, profile)
            credentials.markSaved(profile.id)

            viewModel.changeAdministrationPassword(
                administrationDestination(profile),
                "operator",
                "replacement-password".toCharArray(),
                saveProtectedCredential = false,
            )
            advanceUntilIdle()

            assertEquals(0, repository.upsertCalls)
            assertEquals(0, credentials.deleteCalls)
            assertFalse(backend.events.contains("disconnect"))
            assertTrue(viewModel.state.value.screen is AppScreen.Console)
            assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("preserved"))
        }

    @Test
    fun indeterminatePasswordChangeInvalidatesCredentialAndDisconnects() = runTest(dispatcher) {
        val profile = HostProfile.Default
        val repository = FakeProfilesRepository(ProfileCatalogState.Ready(listOf(profile)))
        val credentials = FakeSavedCredentials()
        val backend = FakeConsoleBackend().apply {
            passwordMutationResult = NanoKvmAdministrationMutationResult.Indeterminate(
                state = null,
                dispatchError = NanoKvmAdministrationError(
                    NanoKvmAdministrationError.Kind.CONNECTION,
                ),
                refreshError = null,
                impact = NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
                guidance =
                    NanoKvmAdministrationGuidance.VERIFY_NEW_CREDENTIALS_AFTER_RECONNECT,
            )
        }
        val viewModel = viewModel(repository, credentials, backend)
        advanceUntilIdle()
        connectConsole(viewModel, profile)
        credentials.markSaved(profile.id)

        viewModel.changeAdministrationPassword(
            administrationDestination(profile),
            "operator",
            "replacement-password".toCharArray(),
            saveProtectedCredential = false,
        )
        advanceUntilIdle()

        assertEquals(0, repository.upsertCalls)
        assertEquals(1, credentials.deleteCalls)
        assertTrue(backend.events.contains("disconnect"))
        assertTrue(viewModel.state.value.screen is AppScreen.Profiles)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("unknown"))
    }

    @Test
    fun sharedPlainTextIsMemoryOnlyConsumedByIdentityAndClearedOnBackground() = runTest(dispatcher) {
        val viewModel = viewModel(
            FakeProfilesRepository(ProfileCatalogState.Ready(emptyList())),
            FakeSavedCredentials(),
            FakeConsoleBackend(),
        )
        val shared = ClipboardPayloadAnalyzer.analyzeDirectPlainText("share-only secret")
        val other = ClipboardPayloadAnalyzer.analyzeDirectPlainText("other")

        viewModel.receiveSharedPlainText(shared)
        assertSame(shared, viewModel.state.value.pendingSharedPaste)

        viewModel.consumeSharedPlainText(other)
        assertSame(shared, viewModel.state.value.pendingSharedPaste)

        viewModel.consumeSharedPlainText(shared)
        assertNull(viewModel.state.value.pendingSharedPaste)

        viewModel.receiveSharedPlainText(shared)
        viewModel.clearSensitiveWorkForBackground()
        assertNull(viewModel.state.value.pendingSharedPaste)
    }

    @Test
    fun accessPointOnboardingIsSeparateMemoryOnlyAndInvalidatedOnBackground() =
        runTest(dispatcher) {
            val viewModel = viewModel(
                FakeProfilesRepository(ProfileCatalogState.Ready(emptyList())),
                FakeSavedCredentials(),
                FakeConsoleBackend(),
                localNetworkAccess = FakeLocalNetworkAccess(granted = true),
            )
            advanceUntilIdle()

            viewModel.openWifiAccessPointOnboarding()
            assertEquals(AppScreen.WifiAccessPointOnboarding, viewModel.state.value.screen)
            val apPassword = "ap-secret".toCharArray()
            val targetPassword = "wifi-secret".toCharArray()
            viewModel.connectWifiAccessPoint(
                endpoint = "not a valid endpoint / path",
                apPassword = apPassword,
                targetSsid = "manual-ssid",
                targetPassword = targetPassword,
            )
            advanceUntilIdle()

            assertTrue(apPassword.all { it == '\u0000' })
            assertTrue(targetPassword.all { it == '\u0000' })
            assertEquals(
                WifiAccessPointOnboardingNoticeKind.Rejected,
                viewModel.state.value.wifiAccessPointOnboarding.noticeKind,
            )
            assertFalse(viewModel.state.value.toString().contains("ap-secret"))
            assertFalse(viewModel.state.value.toString().contains("wifi-secret"))

            viewModel.clearSensitiveWorkForBackground()
            assertEquals(AppScreen.Profiles, viewModel.state.value.screen)
        }

    private fun viewModel(
        profiles: FakeProfilesRepository,
        credentials: FakeSavedCredentials,
        backend: FakeConsoleBackend,
        promptResults: Flow<CredentialPromptResult> = MutableSharedFlow(),
        appSettings: FakeAppSettingsStore = FakeAppSettingsStore(),
        localNetworkAccess: LocalNetworkAccess = LocalNetworkAccess.Unrestricted,
    ) = AppViewModel(
        profilesRepository = profiles,
        appSettingsStore = appSettings,
        backend = backend,
        savedCredentialStore = credentials,
        credentialResults = promptResults,
        savedStateHandle = SavedStateHandle(),
        localNetworkAccess = localNetworkAccess,
    )

    private fun certificate() = CertificateDetails(
        sha256 = "AA:BB",
        subject = "CN=NanoKVM",
        issuer = "CN=NanoKVM",
        subjectAlternativeNames = listOf("192.0.2.250"),
        validFrom = "2026-01-01T00:00:00Z",
        validUntil = "2030-01-01T00:00:00Z",
    )

    private suspend fun TestScope.connectConsole(viewModel: AppViewModel, profile: HostProfile) {
        viewModel.prepareConnection(profile)
        advanceUntilIdle()
        viewModel.submitPassword(profile, "login-password".toCharArray(), savePassword = false)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.screen is AppScreen.Console)
    }

    private fun administrationDestination(profile: HostProfile) =
        ApprovedAdministrationDestination(profile.id, profile.authority, 0L)
}

private class FakeAppSettingsStore(initial: AppSettings = AppSettings()) : AppSettingsStore {
    private val mutableSettings = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = mutableSettings
    val savedSensitivities = mutableListOf<Float>()
    val savedThemeModes = mutableListOf<ThemeMode>()
    val savedDynamicColorValues = mutableListOf<Boolean>()
    val savedFrameDetectionValues = mutableListOf<Boolean>()

    override suspend fun setScrollSensitivity(sensitivity: Float) {
        savedSensitivities += sensitivity
        mutableSettings.value = mutableSettings.value.copy(scrollSensitivity = sensitivity)
    }

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        savedThemeModes += themeMode
        mutableSettings.value = mutableSettings.value.copy(themeMode = themeMode)
    }

    override suspend fun setUseDynamicColor(enabled: Boolean) {
        savedDynamicColorValues += enabled
        mutableSettings.value = mutableSettings.value.copy(useDynamicColor = enabled)
    }

    override suspend fun setMjpegFrameDetectionEnabled(enabled: Boolean) {
        savedFrameDetectionValues += enabled
        mutableSettings.value = mutableSettings.value.copy(mjpegFrameDetectionEnabled = enabled)
    }
}

private class FakeLocalNetworkAccess(var granted: Boolean) : LocalNetworkAccess {
    override fun isGranted(): Boolean = granted
}

private class FakeProfilesRepository(initial: ProfileCatalogState) : ProfilesRepository {
    private val mutableProfiles = MutableStateFlow(initial)
    override val profiles: Flow<ProfileCatalogState> = mutableProfiles
    var deleteCalls = 0
    var resetCalls = 0
    var upsertCalls = 0
    val upsertedProfiles = mutableListOf<HostProfile>()

    override suspend fun upsert(profile: HostProfile) {
        upsertCalls++
        upsertedProfiles += profile
        mutableProfiles.value = ProfileCatalogState.Ready(listOf(profile))
    }

    override suspend fun delete(profileId: String) {
        deleteCalls++
        mutableProfiles.value = ProfileCatalogState.Ready(listOf(HostProfile.Default))
    }

    override suspend fun reset() {
        resetCalls++
        mutableProfiles.value = ProfileCatalogState.Ready(listOf(HostProfile.Default))
    }
}

private class FakeSavedCredentials(
    private val savedIds: MutableSet<String> = mutableSetOf(),
    private val deleteFailure: Throwable? = null,
) : SavedCredentials {
    var unlockCalls = 0
    var deleteAllCalls = 0
    var deleteCalls = 0
    var prepareCalls = 0
    var stageCalls = 0
    var commitCalls = 0

    fun markSaved(profileId: String) {
        savedIds += profileId
    }

    override suspend fun hasCredential(profileId: String): Boolean = profileId in savedIds
    override suspend fun prepareToSave(profile: HostProfile) {
        prepareCalls++
    }
    override suspend fun stageCredential(profile: HostProfile, password: CharArray): StagedCredential {
        stageCalls++
        return StagedCredential(profile.id, byteArrayOf(1))
    }
    override suspend fun commit(stagedCredential: StagedCredential) {
        commitCalls++
        savedIds += stagedCredential.profileId
    }
    override suspend fun unlock(profile: HostProfile): CharArray {
        unlockCalls++
        return "test-only-password".toCharArray()
    }
    override suspend fun delete(profileId: String) {
        deleteCalls++
        deleteFailure?.let { throw it }
        savedIds -= profileId
    }
    override suspend fun deleteAll() {
        deleteAllCalls++
        savedIds.clear()
    }
}

private class FakeConsoleBackend(
    var trustOutcome: TrustPreflightOutcome = TrustPreflightOutcome.Trusted(
        CertificateTrustSource.System,
        CertificateDetails("AA", "subject", "issuer", emptyList(), "from", "to"),
    ),
) : ConsoleBackend, NanoKvmPasswordChangeFeatureOwner {
    private val mutableSession = MutableStateFlow(BackendSession())
    override val session = mutableSession
    val events = mutableListOf<String>()
    val frameDetectionPreferences = mutableListOf<Boolean>()
    val frameDetectionWrites = mutableListOf<Boolean>()
    var passwordDispatches = 0
    var passwordMutationResult:
        NanoKvmAdministrationMutationResult<NanoKvmAdministrationAccountSnapshot> =
        NanoKvmAdministrationMutationResult.CredentialsChanged

    override suspend fun preflightTrust(profile: HostProfile): TrustPreflightOutcome {
        events += "preflight"
        return trustOutcome
    }
    override suspend fun connect(request: ConnectRequest): ConnectOutcome {
        events += "connect"
        return ConnectOutcome.Connected
    }
    override suspend fun disconnect() {
        events += "disconnect"
    }
    override fun reconnect() = Unit
    override fun setForeground(isForeground: Boolean) = Unit
    override fun attachVideoSurface(surface: Surface, width: Int, height: Int) = Unit
    override fun resizeVideoSurface(width: Int, height: Int) = Unit
    override fun detachVideoSurface(surface: Surface) = Unit
    override fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton>) = Unit
    override fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) = Unit
    override fun mouseButton(button: MouseButton, pressed: Boolean) = Unit
    override fun scrollWheel(steps: Int) = Unit
    override fun scrollHorizontal(steps: Int) = Unit
    override fun typeCommittedText(text: String, layout: KeyboardLayout) = Unit
    override fun key(key: RemoteKey, pressed: Boolean) = Unit
    override fun releaseAllInput() = Unit
    override fun updateVideo(settings: VideoSettings) = Unit
    override fun setMjpegFrameDetectionPreference(enabled: Boolean) {
        frameDetectionPreferences += enabled
    }
    override fun setMjpegFrameDetectionEnabled(enabled: Boolean) {
        frameDetectionWrites += enabled
    }
    override fun resetHid() = Unit
    override fun power(action: PowerAction) = Unit
    override fun pasteText(request: ApprovedPasteRequest) = Unit
    override fun cancelPaste() = Unit

    override fun createPasswordChangeCoordinatorToken(requestToken: Any): Any? {
        val request = requestToken.passwordChangeFactoryRequestOrNull() ?: return null
        val binding = NanoKvmSessionBinding(
            request.destination.profileId,
            request.destination.authority,
            request.destination.sessionGeneration,
        )
        return NanoKvmPasswordChangeCoordinator(
            binding = binding,
            profile = request.profile,
            mutation = NanoKvmPasswordMutation { _, password ->
                passwordDispatches++
                password.fill('\u0000')
                passwordMutationResult
            },
            currentBinding = { binding },
            savedCredentials = request.savedCredentials,
            profilesRepository = request.profilesRepository,
            sessionTerminator = request.sessionTerminator,
        )
    }
}

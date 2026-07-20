package org.nanokvm.mobile.runtime

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.data.ProfileCatalogState
import org.nanokvm.mobile.data.ProfilesRepository
import org.nanokvm.mobile.security.CredentialPromptKind
import org.nanokvm.mobile.security.CredentialPromptRequest
import org.nanokvm.mobile.security.CredentialPromptResult
import org.nanokvm.mobile.security.SavedCredentials
import org.nanokvm.mobile.security.StagedCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class NanoKvmPasswordChangeCoordinatorTest {
    @Test
    fun typedRequestRedactsDestinationAndProfileDiagnostics() {
        val events = mutableListOf<String>()
        val profile = HostProfile(
            id = "private-profile-id",
            name = "Private NanoKVM",
            host = "private.nanokvm.test",
            username = "private-operator",
        )
        val request = NanoKvmPasswordChangeRequest(
            destination = ApprovedAdministrationDestination(
                profileId = profile.id,
                authority = profile.authority,
                sessionGeneration = 7L,
            ),
            profile = profile,
            savedCredentials = FakeSavedCredentials(events, failCommit = false),
            profilesRepository = FakeProfilesRepository(events),
            sessionTerminator = NanoKvmPasswordChangeSessionTerminator { _ -> },
        )

        val diagnostic = request.toString()

        assertEquals(
            "NanoKvmPasswordChangeRequest(destination=<redacted>, profile=<redacted>)",
            diagnostic,
        )
        listOf(profile.id, profile.name, profile.host, profile.username).forEach { value ->
            assertFalse(diagnostic.contains(value))
        }
    }

    @Test
    fun protectedReplacementStagesOnlyAfterAuthenticationThenCommitsAndEndsSession() = runTest {
        val fixture = Fixture()
        val password = "replacement-pass".toCharArray()
        val result = fixture.coordinator.begin(
            username = "operator",
            password = password,
            saveProtectedCredential = true,
            authenticationRequest = fixture.authRequest,
        )

        assertEquals(fixture.authRequest, (result as NanoKvmPasswordChangeResult.AuthenticationRequired).request)
        assertEquals(listOf("prepare:operator"), fixture.events)
        assertFalse(password.all { it == '\u0000' })

        val completed = fixture.coordinator.completeAuthentication(
            CredentialPromptResult.Authenticated(fixture.authRequest.id),
        )

        assertEquals(
            listOf(
                "prepare:operator",
                "stage:operator",
                "dispatch:operator",
                "profile:operator",
                "commit",
                "end:ACKNOWLEDGED",
            ),
            fixture.events,
        )
        assertTrue(password.all { it == '\u0000' })
        assertEquals(
            NanoKvmPasswordChangeResult.Changed(true, emptySet()),
            completed,
        )
    }

    @Test
    fun acknowledgedWithoutSavingUpdatesProfileDeletesOldCredentialAndEndsSession() = runTest {
        val fixture = Fixture()
        val password = "replacement-pass".toCharArray()

        val completed = fixture.coordinator.begin(
            username = "operator",
            password = password,
            saveProtectedCredential = false,
        )

        assertEquals(
            listOf("dispatch:operator", "profile:operator", "delete", "end:ACKNOWLEDGED"),
            fixture.events,
        )
        assertEquals(NanoKvmPasswordChangeResult.Changed(false, emptySet()), completed)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun definiteRejectionPreservesProfileCredentialAndSession() = runTest {
        val fixture = Fixture(
            mutationResult = NanoKvmAdministrationMutationResult.Rejected(
                error = NanoKvmAdministrationError(
                    NanoKvmAdministrationError.Kind.SERVER_REJECTED,
                ),
                impact = NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
            ),
        )
        val password = "replacement-pass".toCharArray()

        val completed = fixture.coordinator.begin("operator", password, false)

        assertEquals(
            NanoKvmPasswordChangeResult.Rejected(
                NanoKvmAdministrationError.Kind.SERVER_REJECTED,
            ),
            completed,
        )
        assertEquals(listOf("dispatch:operator"), fixture.events)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun authenticationExpiryInvokesGlobalTeardownOnceAndPreservesLocalCredential() = runTest {
        val fixture = Fixture(
            mutationResult = NanoKvmAdministrationMutationResult.Rejected(
                error = NanoKvmAdministrationError(
                    NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED,
                ),
                impact = NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
            ),
        )
        val password = "replacement-pass".toCharArray()

        val completed = fixture.coordinator.begin("operator", password, false)

        assertSame(NanoKvmPasswordChangeResult.AuthenticationExpired, completed)
        assertEquals(1, fixture.authenticationExpiredCalls)
        assertEquals(listOf("dispatch:operator", "authentication-expired"), fixture.events)
        assertFalse(fixture.events.contains("delete"))
        assertFalse(fixture.events.any { it.startsWith("profile:") })
        assertFalse(fixture.events.any { it.startsWith("end:") })
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun authenticationExpiryFromReplacedGenerationIsDiscardedAsStale() = runTest {
        val dispatchGate = CompletableDeferred<Unit>()
        val fixture = Fixture(
            mutationResult = NanoKvmAdministrationMutationResult.Rejected(
                error = NanoKvmAdministrationError(
                    NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED,
                ),
                impact = NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
            ),
            dispatchGate = dispatchGate,
        )
        val completed = async {
            fixture.coordinator.begin("operator", "replacement-pass".toCharArray(), false)
        }
        runCurrent()
        fixture.currentBinding.set(
            fixture.binding.copy(sessionGeneration = fixture.binding.sessionGeneration + 1),
        )
        dispatchGate.complete(Unit)

        assertSame(NanoKvmPasswordChangeResult.StaleSession, completed.await())
        assertEquals(0, fixture.authenticationExpiredCalls)
        assertFalse(fixture.events.contains("authentication-expired"))
    }

    @Test
    fun indeterminateOutcomeDeletesCredentialAndEndsForManualVerification() = runTest {
        val fixture = Fixture(
            mutationResult = NanoKvmAdministrationMutationResult.Indeterminate(
                state = null,
                dispatchError = NanoKvmAdministrationError(
                    NanoKvmAdministrationError.Kind.CONNECTION,
                ),
                refreshError = null,
                impact = NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
                guidance = NanoKvmAdministrationGuidance.VERIFY_NEW_CREDENTIALS_AFTER_RECONNECT,
            ),
        )
        val password = "replacement-pass".toCharArray()

        val completed = fixture.coordinator.begin("operator", password, false)

        assertEquals(
            NanoKvmPasswordChangeResult.ManualVerificationRequired(emptySet()),
            completed,
        )
        assertEquals(
            listOf(
                "dispatch:operator",
                "delete",
                "end:OUTCOME_REQUIRES_MANUAL_VERIFICATION",
            ),
            fixture.events,
        )
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun cancelledAuthenticationClearsPasswordWithoutDispatchOrLocalMutation() = runTest {
        val fixture = Fixture()
        val password = "replacement-pass".toCharArray()
        fixture.coordinator.begin(
            "operator",
            password,
            true,
            fixture.authRequest,
        )

        val completed = fixture.coordinator.completeAuthentication(
            CredentialPromptResult.Cancelled(fixture.authRequest.id),
        )

        assertSame(NanoKvmPasswordChangeResult.AuthenticationCancelled, completed)
        assertEquals(listOf("prepare:operator"), fixture.events)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun staleAuthenticationResultCannotConsumeOrReplayPendingMutation() = runTest {
        val fixture = Fixture()
        val password = "replacement-pass".toCharArray()
        fixture.coordinator.begin("operator", password, true, fixture.authRequest)

        assertSame(
            NanoKvmPasswordChangeResult.IgnoredAuthenticationResult,
            fixture.coordinator.completeAuthentication(CredentialPromptResult.Authenticated(999)),
        )
        assertFalse(password.all { it == '\u0000' })

        fixture.coordinator.completeAuthentication(
            CredentialPromptResult.Authenticated(fixture.authRequest.id),
        )
        val replay = fixture.coordinator.completeAuthentication(
            CredentialPromptResult.Authenticated(fixture.authRequest.id),
        )

        assertSame(NanoKvmPasswordChangeResult.IgnoredAuthenticationResult, replay)
        assertEquals(1, fixture.events.count { it == "dispatch:operator" })
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun generationChangeBeforeAuthenticationClearsPendingAndPreventsDispatch() = runTest {
        val fixture = Fixture()
        val password = "replacement-pass".toCharArray()
        fixture.coordinator.begin("operator", password, true, fixture.authRequest)
        fixture.currentBinding.set(null)

        val completed = fixture.coordinator.completeAuthentication(
            CredentialPromptResult.Authenticated(fixture.authRequest.id),
        )

        assertSame(NanoKvmPasswordChangeResult.StaleSession, completed)
        assertEquals(listOf("prepare:operator"), fixture.events)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun invalidationClearsPendingSecretAndAuthenticationCannotResumeIt() = runTest {
        val fixture = Fixture()
        val password = "replacement-pass".toCharArray()
        fixture.coordinator.begin("operator", password, true, fixture.authRequest)

        fixture.coordinator.invalidate()

        assertTrue(password.all { it == '\u0000' })
        assertSame(
            NanoKvmPasswordChangeResult.IgnoredAuthenticationResult,
            fixture.coordinator.completeAuthentication(
                CredentialPromptResult.Authenticated(fixture.authRequest.id),
            ),
        )
        assertEquals(listOf("prepare:operator"), fixture.events)
    }

    @Test
    fun simultaneousSecondSubmissionIsBusyAndItsPasswordIsCleared() = runTest {
        val dispatchGate = CompletableDeferred<Unit>()
        val fixture = Fixture(dispatchGate = dispatchGate)
        val firstPassword = "replacement-one".toCharArray()
        val secondPassword = "replacement-two".toCharArray()

        val first = async { fixture.coordinator.begin("operator", firstPassword, false) }
        runCurrent()
        val second = fixture.coordinator.begin("operator", secondPassword, false)

        assertSame(NanoKvmPasswordChangeResult.Busy, second)
        assertTrue(secondPassword.all { it == '\u0000' })
        dispatchGate.complete(Unit)
        first.await()
        assertEquals(1, fixture.events.count { it == "dispatch:operator" })
    }

    @Test
    fun commitFailureDeletesStaleCredentialAndReportsTypedLocalFailure() = runTest {
        val fixture = Fixture(failCommit = true)
        val password = "replacement-pass".toCharArray()
        fixture.coordinator.begin("operator", password, true, fixture.authRequest)

        val completed = fixture.coordinator.completeAuthentication(
            CredentialPromptResult.Authenticated(fixture.authRequest.id),
        )

        assertEquals(
            NanoKvmPasswordChangeResult.Changed(
                replacementCredentialRequested = true,
                localFailures = setOf(NanoKvmPasswordChangeLocalFailure.CREDENTIAL_COMMIT),
            ),
            completed,
        )
        assertEquals(
            listOf(
                "prepare:operator",
                "stage:operator",
                "dispatch:operator",
                "profile:operator",
                "commit",
                "delete",
                "end:ACKNOWLEDGED",
            ),
            fixture.events,
        )
    }

    @Test
    fun passwordAndPendingStateNeverAppearInResultsOrDiagnostics() = runTest {
        val fixture = Fixture()
        val secret = "never-print-this"
        val password = secret.toCharArray()

        val pending = fixture.coordinator.begin(
            "operator",
            password,
            true,
            fixture.authRequest,
        )

        assertFalse(pending.toString().contains(secret))
        assertFalse(fixture.coordinator.toString().contains(secret))
        fixture.coordinator.invalidate()
        assertArrayEquals(CharArray(secret.length), password)
    }

    private class Fixture(
        mutationResult: NanoKvmAdministrationMutationResult<NanoKvmAdministrationAccountSnapshot> =
            NanoKvmAdministrationMutationResult.CredentialsChanged,
        failCommit: Boolean = false,
        dispatchGate: CompletableDeferred<Unit>? = null,
    ) {
        val events = mutableListOf<String>()
        val binding = NanoKvmSessionBinding("profile-1", "nanokvm.test", 7)
        val currentBinding = AtomicReference<NanoKvmSessionBinding?>(binding)
        val authRequest = CredentialPromptRequest(41, CredentialPromptKind.Save, "Lab NanoKVM")
        var authenticationExpiredCalls = 0
        private val profile = HostProfile(
            id = binding.profileId,
            name = "Lab NanoKVM",
            host = "nanokvm.test",
            username = "admin",
        )
        private val credentials = FakeSavedCredentials(events, failCommit)
        private val profiles = FakeProfilesRepository(events)
        val coordinator = NanoKvmPasswordChangeCoordinator(
            binding = binding,
            profile = profile,
            mutation = NanoKvmPasswordMutation { username, password ->
                events += "dispatch:$username"
                dispatchGate?.await()
                password.fill('\u0000')
                mutationResult
            },
            currentBinding = currentBinding::get,
            savedCredentials = credentials,
            profilesRepository = profiles,
            sessionTerminator = NanoKvmPasswordChangeSessionTerminator { reason ->
                events += "end:$reason"
            },
            onAuthenticationExpired = {
                authenticationExpiredCalls++
                events += "authentication-expired"
            },
        )
    }
}

private class FakeSavedCredentials(
    private val events: MutableList<String>,
    private val failCommit: Boolean,
) : SavedCredentials {
    override suspend fun hasCredential(profileId: String): Boolean = true

    override suspend fun prepareToSave(profile: HostProfile) {
        events += "prepare:${profile.username}"
    }

    override suspend fun stageCredential(
        profile: HostProfile,
        password: CharArray,
    ): StagedCredential {
        events += "stage:${profile.username}"
        assertFalse(password.all { it == '\u0000' })
        return StagedCredential(profile.id, byteArrayOf(1, 2, 3))
    }

    override suspend fun commit(stagedCredential: StagedCredential) {
        events += "commit"
        if (failCommit) error("synthetic commit failure")
    }

    override suspend fun unlock(profile: HostProfile): CharArray = error("unused")

    override suspend fun delete(profileId: String) {
        events += "delete"
    }

    override suspend fun deleteAll() = error("unused")
}

private class FakeProfilesRepository(
    private val events: MutableList<String>,
) : ProfilesRepository {
    override val profiles: Flow<ProfileCatalogState> = flowOf(ProfileCatalogState.Ready(emptyList()))

    override suspend fun upsert(profile: HostProfile) {
        events += "profile:${profile.username}"
    }

    override suspend fun delete(profileId: String) = error("unused")

    override suspend fun reset() = error("unused")
}

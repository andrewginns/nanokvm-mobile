package org.nanokvm.mobile.security

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialAuthenticationCoordinatorTest {
    @Test
    fun configurationRebindKeepsOnlyTypedRequestAndRejectsStaleCallback() {
        val coordinator = CredentialAuthenticationCoordinator()
        val firstHost = coordinator.activateNewHost()
        val request = request(41)

        assertEquals(PromptBeginResult.Started, coordinator.begin(firstHost, request))
        val replacementHost = coordinator.activateNewHost()

        assertNull(coordinator.pendingRequestId(firstHost))
        assertEquals(41L, coordinator.pendingRequestId(replacementHost))
        assertFalse(
            coordinator.complete(firstHost, CredentialPromptResult.Authenticated(request.id)),
        )
        assertTrue(
            coordinator.complete(replacementHost, CredentialPromptResult.Authenticated(request.id)),
        )
    }

    @Test
    fun replacementHostRecognizesReattachedRequestWithoutStartingAnotherPrompt() {
        val coordinator = CredentialAuthenticationCoordinator()
        val firstHost = coordinator.activateNewHost()
        val request = request(7)
        assertEquals(PromptBeginResult.Started, coordinator.begin(firstHost, request))

        val replacementHost = coordinator.activateNewHost()

        assertEquals(PromptBeginResult.AlreadyActive, coordinator.begin(replacementHost, request))
        assertEquals(PromptBeginResult.Busy, coordinator.begin(replacementHost, request(8)))
    }

    @Test
    fun cancellationRemovesRequestAndAllowsFreshWork() {
        val coordinator = CredentialAuthenticationCoordinator()
        val host = coordinator.activateNewHost()
        assertEquals(PromptBeginResult.Started, coordinator.begin(host, request(1)))

        assertEquals(1L, coordinator.cancel(host)?.id)
        assertNull(coordinator.pendingRequestId(host))
        assertEquals(PromptBeginResult.Started, coordinator.begin(host, request(2)))
    }

    @Test
    fun authenticationFailureIsReportedAsClosedSemanticData() = runTest {
        val coordinator = CredentialAuthenticationCoordinator()
        val host = coordinator.activateNewHost()
        val request = request(51)
        assertEquals(PromptBeginResult.Started, coordinator.begin(host, request))
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.results.first()
        }

        coordinator.cancel(host, CredentialPromptFailure.AuthenticationStartFailed)

        assertEquals(
            CredentialPromptResult.Failed(
                requestId = request.id,
                failure = CredentialPromptFailure.AuthenticationStartFailed,
            ),
            result.await(),
        )
    }

    @Test
    fun aReplacedHostCannotRegisterAnotherOperation() {
        val coordinator = CredentialAuthenticationCoordinator()
        val staleHost = coordinator.activateNewHost()
        val activeHost = coordinator.activateNewHost()

        assertEquals(PromptBeginResult.StaleHost, coordinator.begin(staleHost, request(1)))
        assertEquals(PromptBeginResult.Started, coordinator.begin(activeHost, request(2)))
    }

    @Test
    fun olderHostCannotReclaimPromptRoutingAfterReplacement() {
        val coordinator = CredentialAuthenticationCoordinator()
        val staleHost = coordinator.activateNewHost()
        val activeHost = coordinator.activateNewHost()
        val activeRequest = request(91)
        assertEquals(PromptBeginResult.Started, coordinator.begin(activeHost, activeRequest))

        coordinator.activateHost(staleHost)

        assertNull(coordinator.pendingRequestId(staleHost))
        assertEquals(activeRequest.id, coordinator.pendingRequestId(activeHost))
        assertFalse(
            coordinator.complete(
                staleHost,
                CredentialPromptResult.Authenticated(activeRequest.id),
            ),
        )
        assertTrue(
            coordinator.complete(
                activeHost,
                CredentialPromptResult.Authenticated(activeRequest.id),
            ),
        )
    }

    @Test
    fun repeatedHostReplacementRejectsEveryLateAuthenticationCallback() {
        val coordinator = CredentialAuthenticationCoordinator()
        val hosts = buildList {
            repeat(64) { add(coordinator.activateNewHost()) }
        }
        val activeHost = hosts.last()
        val activeRequest = request(112)
        assertEquals(PromptBeginResult.Started, coordinator.begin(activeHost, activeRequest))

        hosts.dropLast(1).forEach { staleHost ->
            assertFalse(
                coordinator.complete(
                    staleHost,
                    CredentialPromptResult.Authenticated(activeRequest.id),
                ),
            )
        }

        assertEquals(activeRequest.id, coordinator.pendingRequestId(activeHost))
        assertTrue(
            coordinator.complete(
                activeHost,
                CredentialPromptResult.Authenticated(activeRequest.id),
            ),
        )
    }

    private fun request(id: Long) = CredentialPromptRequest(
        id = id,
        kind = CredentialPromptKind.Save,
        profileName = "Test KVM",
    )

    private fun CredentialAuthenticationCoordinator.activateNewHost(): Long {
        val token = reserveHostToken()
        activateHost(token)
        return token
    }
}

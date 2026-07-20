package org.nanokvm.mobile.runtime

import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.InMemorySessionTokenStore
import org.nanokvm.protocol.NanoKvmApplicationVersion
import org.nanokvm.protocol.NanoKvmCapability
import org.nanokvm.protocol.NanoKvmCapabilityEvidence
import org.nanokvm.protocol.NanoKvmCapabilitySupport
import org.nanokvm.protocol.NanoKvmClient
import org.nanokvm.protocol.NanoKvmEndpoint
import org.nanokvm.protocol.NanoKvmServerCapabilities
import org.nanokvm.protocol.VmInfo

class NanoKvmOfflineUpdateBackendIntegrationTest {
    @Test
    fun `offline update surface resolves only the exact foreground session generation`() =
        runBlocking {
            val authenticatedSession = AuthenticatedNanoKvmSession(
                client = NanoKvmClient.create(
                    endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:9"),
                    tokenStore = InMemorySessionTokenStore("memory-only-session-token"),
                ),
                profileId = "offline-profile",
                authority = "127.0.0.1:9",
                vmInfo = VmInfo(application = "2.4.3"),
                capabilities = reflectedOfflineUpdateCapabilities(),
            )
            val backend = NanoKvmConsoleBackend(
                workerDispatcher = Dispatchers.Unconfined,
                reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
            )
            backend.setOfflinePrivateField("authenticatedSession", authenticatedSession)
            backend.setOfflinePrivateField("acceptingCommands", true)
            @Suppress("UNCHECKED_CAST")
            val mutableSession = backend.offlinePrivateField("mutableSession") as
                MutableStateFlow<BackendSession>
            mutableSession.value = BackendSession(
                connection = ConnectionState.Connected,
                sessionGeneration = 9L,
            )
            backend.installOfflineUpdateGatewayForTest(authenticatedSession, 9L)
            val owner = requireNotNull(backend.features.offlineUpdate)

            assertNull(owner.currentOfflineUpdateGateway())

            owner.setOfflineUpdateSurfaceVisible(true)
            val generationNineGateway = requireNotNull(owner.currentOfflineUpdateGateway())
            assertEquals(9L, generationNineGateway.binding.sessionGeneration)
            assertEquals(NanoKvmOfflineUpdatePhase.EMPTY, generationNineGateway.state.value.phase)

            owner.setOfflineUpdateSurfaceVisible(false)
            assertNull(owner.currentOfflineUpdateGateway())
            assertEquals(
                NanoKvmOfflineUpdatePhase.HIDDEN,
                generationNineGateway.state.value.phase,
            )

            owner.setOfflineUpdateSurfaceVisible(true)
            assertSame(generationNineGateway, owner.currentOfflineUpdateGateway())
            mutableSession.value = mutableSession.value.copy(sessionGeneration = 10L)

            assertNull(owner.currentOfflineUpdateGateway())
            val staleSource = NanoKvmOfflineUpdateSource.create(
                fileName = "nanokvm_2.5.0.tar.gz",
                contentLength = 1L,
                opener = NanoKvmOfflineUpdateStreamOpener {
                    ByteArrayInputStream(byteArrayOf(1))
                },
            )
            assertFalse(generationNineGateway.select(staleSource))
            assertEquals(
                NanoKvmOfflineUpdatePhase.SESSION_CHANGED,
                generationNineGateway.state.value.phase,
            )

            // Installation happens inside the backend binding transaction. Activation must wait
            // until the new generation is published, otherwise the visible gateway observes 9.
            mutableSession.value = mutableSession.value.copy(sessionGeneration = 9L)
            backend.installOfflineUpdateGatewayForTest(authenticatedSession, 10L)
            assertNull(owner.currentOfflineUpdateGateway())
            mutableSession.value = mutableSession.value.copy(sessionGeneration = 10L)
            backend.activateOfflineUpdateGatewayForTest()
            val generationTenGateway = requireNotNull(owner.currentOfflineUpdateGateway())
            assertTrue(generationTenGateway !== generationNineGateway)
            assertEquals(10L, generationTenGateway.binding.sessionGeneration)
            assertEquals(NanoKvmOfflineUpdatePhase.EMPTY, generationTenGateway.state.value.phase)

            backend.closeAndAwait()
            assertNull(owner.currentOfflineUpdateGateway())
        }
}

private fun reflectedOfflineUpdateCapabilities(): NanoKvmServerCapabilities {
    val version = requireNotNull(NanoKvmApplicationVersion.parse("2.4.3"))
    val assessments = NanoKvmCapability.entries.associateWith {
        NanoKvmCapabilitySupport.Supported(
            NanoKvmCapabilityEvidence.VersionFloor(version, version),
        )
    }
    val constructor = NanoKvmServerCapabilities::class.java.declaredConstructors
        .single { it.parameterTypes.size == 2 }
        .apply { isAccessible = true }
    return constructor.newInstance(version, assessments) as NanoKvmServerCapabilities
}

private fun Any.setOfflinePrivateField(name: String, value: Any?) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private fun Any.offlinePrivateField(name: String): Any? =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

private fun NanoKvmConsoleBackend.installOfflineUpdateGatewayForTest(
    authenticatedSession: AuthenticatedNanoKvmSession,
    generation: Long,
) {
    val lock = offlinePrivateField("stateLock") ?: error("Missing backend state lock")
    val installer = javaClass.getDeclaredMethod(
        "installOfflineUpdateGatewayLocked",
        AuthenticatedNanoKvmSession::class.java,
        java.lang.Long.TYPE,
    ).apply { isAccessible = true }
    synchronized(lock) {
        installer.invoke(this, authenticatedSession, generation)
    }
}

private fun NanoKvmConsoleBackend.activateOfflineUpdateGatewayForTest() {
    javaClass.getDeclaredMethod("activateOfflineUpdateGatewayIfCurrent").apply {
        isAccessible = true
    }.invoke(this)
}

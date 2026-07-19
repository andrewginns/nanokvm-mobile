package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import kotlinx.coroutines.flow.MutableStateFlow
import org.nanokvm.protocol.InMemorySessionTokenStore
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.NanoKvmApplicationVersion
import org.nanokvm.protocol.NanoKvmClient
import org.nanokvm.protocol.NanoKvmEndpoint
import org.nanokvm.protocol.NanoKvmServerCapabilities
import org.nanokvm.protocol.VmInfo

class NanoKvmConsoleBackendTest {
    @Test
    fun `frame detection 401 uses global teardown and invalidates later writes`() = runBlocking {
        val tokenStore = InMemorySessionTokenStore("frame-detection-session-token")
        val client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:9"),
            tokenStore = tokenStore,
        )
        val authenticatedSession = AuthenticatedNanoKvmSession(
            client = client,
            profileId = "frame-401",
            authority = "127.0.0.1:9",
            vmInfo = VmInfo(application = "2.4.3"),
            capabilities = reflectedEmptyCapabilities(),
        )
        val backend = NanoKvmConsoleBackend(
            workerDispatcher = Dispatchers.Unconfined,
            reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
        )
        backend.setPrivateField("authenticatedSession", authenticatedSession)
        backend.setPrivateField("acceptingCommands", true)
        @Suppress("UNCHECKED_CAST")
        val mutableSession = backend.privateField("mutableSession") as
            MutableStateFlow<BackendSession>
        mutableSession.value = BackendSession(
            connection = ConnectionState.Connected,
            sessionGeneration = 31L,
        )
        val binding = NanoKvmSessionBinding("frame-401", "127.0.0.1:9", 31L)
        val port = BackendFrameDetectionPort(AuthenticationExpiredException())
        val coordinator = backend.privateField("mjpegFrameDetectionCoordinator") as
            NanoKvmMjpegFrameDetectionCoordinator
        coordinator.install(binding) {
            NanoKvmMjpegFrameDetectionGateway(port, binding) { binding }
        }

        backend.setMjpegFrameDetectionEnabled(true)
        backend.setMjpegFrameDetectionEnabled(false)

        assertEquals(listOf(true), port.enabledWrites)
        assertEquals(ConnectionState.Failed, backend.session.value.connection)
        assertEquals(31L, backend.session.value.sessionGeneration)
        assertEquals(null, tokenStore.read())
        assertTrue(backend.session.value.message.orEmpty().contains("authenticate again"))
        backend.closeAndAwait()
    }

    @Test
    fun `password change 401 uses exactly once global teardown`() = runBlocking {
        val tokenStore = InMemorySessionTokenStore("password-change-session-token")
        val client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:9"),
            tokenStore = tokenStore,
        )
        val authenticatedSession = AuthenticatedNanoKvmSession(
            client = client,
            profileId = "password-401",
            authority = "127.0.0.1:9",
            vmInfo = VmInfo(application = "2.4.3"),
            capabilities = reflectedEmptyCapabilities(),
        )
        val backend = NanoKvmConsoleBackend(
            workerDispatcher = Dispatchers.Unconfined,
            reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
        )
        backend.setPrivateField("authenticatedSession", authenticatedSession)
        backend.setPrivateField("acceptingCommands", true)
        @Suppress("UNCHECKED_CAST")
        val mutableSession = backend.privateField("mutableSession") as
            MutableStateFlow<BackendSession>
        mutableSession.value = BackendSession(
            connection = ConnectionState.Connected,
            sessionGeneration = 23L,
            administration = AdministrationUiState(available = true),
        )
        val binding = NanoKvmSessionBinding("password-401", "127.0.0.1:9", 23L)

        assertTrue(backend.expirePasswordChangeAuthentication(binding))
        assertFalse(backend.expirePasswordChangeAuthentication(binding))

        assertEquals(ConnectionState.Failed, backend.session.value.connection)
        assertEquals(23L, backend.session.value.sessionGeneration)
        assertEquals(null, tokenStore.read())
        assertTrue(backend.session.value.message.orEmpty().contains("authenticate again"))
        backend.closeAndAwait()
    }

    @Test
    fun `stale generation authentication expiry cannot tear down replacement session`() =
        runBlocking {
            val tokenStore = InMemorySessionTokenStore("replacement-session-token")
            val client = NanoKvmClient.create(
                endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:9"),
                tokenStore = tokenStore,
            )
            val authenticatedSession = AuthenticatedNanoKvmSession(
                client = client,
                profileId = "replacement",
                authority = "127.0.0.1:9",
                vmInfo = VmInfo(application = "2.4.3"),
                capabilities = reflectedEmptyCapabilities(),
            )
            val backend = NanoKvmConsoleBackend(
                workerDispatcher = Dispatchers.Unconfined,
                reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
            )
            backend.setPrivateField("authenticatedSession", authenticatedSession)
            backend.setPrivateField("acceptingCommands", true)
            @Suppress("UNCHECKED_CAST")
            val mutableSession = backend.privateField("mutableSession") as
                MutableStateFlow<BackendSession>
            mutableSession.value = BackendSession(
                connection = ConnectionState.Connected,
                sessionGeneration = 42L,
            )

            assertFalse(
                backend.expirePasswordChangeAuthentication(
                    NanoKvmSessionBinding("replacement", "127.0.0.1:9", 41L),
                ),
            )
            assertEquals(ConnectionState.Connected, backend.session.value.connection)
            assertTrue(tokenStore.read() != null)
            backend.closeAndAwait()
        }

    @Test
    fun `current input websocket 401 tears down once after command barrier closes`() = runBlocking {
        val tokenStore = InMemorySessionTokenStore("input-websocket-session-token")
        val client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:9"),
            tokenStore = tokenStore,
        )
        val authenticatedSession = AuthenticatedNanoKvmSession(
            client = client,
            profileId = "input-401",
            authority = "127.0.0.1:9",
            vmInfo = VmInfo(application = "2.4.3"),
            capabilities = reflectedEmptyCapabilities(),
        )
        val backend = NanoKvmConsoleBackend(
            workerDispatcher = Dispatchers.Unconfined,
            reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
        )
        backend.setPrivateField("authenticatedSession", authenticatedSession)
        // Reconnect preparation has already closed this barrier when a handshake 401 arrives.
        backend.setPrivateField("acceptingCommands", false)
        @Suppress("UNCHECKED_CAST")
        val mutableSession = backend.privateField("mutableSession") as
            MutableStateFlow<BackendSession>
        mutableSession.value = BackendSession(
            connection = ConnectionState.Reconnecting,
            sessionGeneration = 55L,
        )
        val binding = NanoKvmSessionBinding("input-401", "127.0.0.1:9", 55L)
        val scheduler = NanoKvmConsoleBackend::class.java.getDeclaredMethod(
            "scheduleReconnect",
            ReconnectFailure::class.java,
            java.lang.Boolean.TYPE,
            NanoKvmSessionBinding::class.java,
        ).apply { isAccessible = true }
        val failure = ReconnectFailure(IOException("upgrade rejected"), httpStatus = 401)

        scheduler.invoke(backend, failure, false, binding)
        scheduler.invoke(backend, failure, false, binding)

        assertEquals(ConnectionState.Failed, backend.session.value.connection)
        assertEquals(55L, backend.session.value.sessionGeneration)
        assertEquals(null, tokenStore.read())
        assertTrue(backend.session.value.message.orEmpty().contains("authenticate again"))
        backend.closeAndAwait()
    }

    @Test
    fun `classified feature 401 tears down session and stale approval cannot dispatch`() =
        runBlocking {
            val tokenStore = InMemorySessionTokenStore("memory-only-session-token")
            val client = NanoKvmClient.create(
                endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:9"),
                tokenStore = tokenStore,
            )
            val authenticatedSession = AuthenticatedNanoKvmSession(
                client = client,
                profileId = "profile-401",
                authority = "127.0.0.1:9",
                vmInfo = VmInfo(application = "2.4.3"),
                capabilities = reflectedEmptyCapabilities(),
            )
            val backend = NanoKvmConsoleBackend(
                workerDispatcher = Dispatchers.Unconfined,
                reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
            )
            backend.setPrivateField("authenticatedSession", authenticatedSession)
            backend.setPrivateField("acceptingCommands", true)
            @Suppress("UNCHECKED_CAST")
            val mutableSession = backend.privateField("mutableSession") as
                MutableStateFlow<BackendSession>
            mutableSession.value = BackendSession(
                connection = ConnectionState.Connected,
                sessionGeneration = 19L,
                administration = AdministrationUiState(available = true),
            )
            val expired = NanoKvmAdministrationError(
                NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED,
            )
            val classifier = NanoKvmConsoleBackend::class.java.getDeclaredMethod(
                "administrationErrorMessage",
                NanoKvmSessionBinding::class.java,
                NanoKvmAdministrationError::class.java,
            ).apply { isAccessible = true }
            val binding = NanoKvmSessionBinding("profile-401", "127.0.0.1:9", 19L)

            classifier.invoke(backend, binding, expired)

            assertEquals(ConnectionState.Failed, backend.session.value.connection)
            assertEquals(19L, backend.session.value.sessionGeneration)
            assertTrue(backend.session.value.message.orEmpty().contains("authenticate again"))
            assertEquals(null, tokenStore.read())

            // This was reviewed for generation 19 before the 401. The closed acceptance barrier
            // rejects it locally; no feature gateway or transport can dispatch it.
            backend.setAdministrationPreviewUpdates(
                ApprovedAdministrationDestination("profile-401", "127.0.0.1:9", 19L),
                enabled = true,
            )
            assertEquals(ConnectionState.Failed, backend.session.value.connection)
            assertEquals(null, tokenStore.read())
            backend.closeAndAwait()
        }
    @Test
    fun `accepts application 2 3 2 and newer`() {
        assertTrue(isSupportedNanoKvmApplication("2.3.2"))
        assertTrue(isSupportedNanoKvmApplication("v2.3.4"))
        assertTrue(isSupportedNanoKvmApplication("NanoKVM 3.0.0"))
    }

    @Test
    fun `rejects legacy malformed and missing versions`() {
        assertFalse(isSupportedNanoKvmApplication("2.3.1"))
        assertFalse(isSupportedNanoKvmApplication("2.2.99"))
        assertFalse(isSupportedNanoKvmApplication("development"))
        assertFalse(isSupportedNanoKvmApplication(""))
    }

    @Test
    fun `close and await is idempotent and makes later operations terminal`() = runBlocking {
        val backend = NanoKvmConsoleBackend(
            workerDispatcher = Dispatchers.Unconfined,
            reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
        )

        backend.closeAndAwait()
        backend.closeAndAwait()
        val trust = backend.preflightTrust(HostProfile.Default)
        val connect = backend.connect(ConnectRequest(HostProfile.Default, charArrayOf('x')))

        assertTrue(trust is TrustPreflightOutcome.Failed)
        assertFalse((trust as TrustPreflightOutcome.Failed).retryable)
        assertTrue(connect is ConnectOutcome.Failed)
        assertFalse((connect as ConnectOutcome.Failed).retryable)
        assertEquals(ConnectionState.Disconnected, backend.session.value.connection)
    }

    @Test
    fun `background backend rejects a new connect before network work`() = runBlocking {
        val backend = NanoKvmConsoleBackend(
            workerDispatcher = Dispatchers.Unconfined,
            reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
        )
        backend.setForeground(false)

        val outcome = backend.connect(ConnectRequest(HostProfile.Default, charArrayOf('x')))

        assertTrue(outcome is ConnectOutcome.Failed)
        outcome as ConnectOutcome.Failed
        assertFalse(outcome.retryable)
        assertTrue(outcome.userMessage.contains("background"))
        backend.closeAndAwait()
    }
}

private fun reflectedEmptyCapabilities(): NanoKvmServerCapabilities {
    val constructor = NanoKvmServerCapabilities::class.java.declaredConstructors
        .single { it.parameterTypes.size == 2 }
        .apply { isAccessible = true }
    return constructor.newInstance(
        NanoKvmApplicationVersion.parse("2.4.3"),
        emptyMap<Any, Any>(),
    ) as NanoKvmServerCapabilities
}

private fun Any.setPrivateField(name: String, value: Any?) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private fun Any.privateField(name: String): Any? =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

private class BackendFrameDetectionPort(
    private val failure: Throwable,
) : NanoKvmMjpegFrameDetectionPort {
    val enabledWrites = mutableListOf<Boolean>()

    override suspend fun setEnabled(enabled: Boolean) {
        enabledWrites += enabled
        throw failure
    }

    override suspend fun pause(durationSeconds: Int) = Unit
}

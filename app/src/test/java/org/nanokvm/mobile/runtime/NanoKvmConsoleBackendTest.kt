package org.nanokvm.mobile.runtime

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import kotlinx.coroutines.flow.MutableStateFlow
import org.nanokvm.protocol.InMemorySessionTokenStore
import org.nanokvm.protocol.ApiResponseException
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.InvalidApiResponseException
import org.nanokvm.protocol.NanoKvmApplicationVersion
import org.nanokvm.protocol.NanoKvmClient
import org.nanokvm.protocol.NanoKvmEndpoint
import org.nanokvm.protocol.NanoKvmServerCapabilities
import org.nanokvm.protocol.VmInfo
import org.nanokvm.video.H264FrameDropReason
import org.nanokvm.video.NanoKvmVideoListener
import org.nanokvm.video.NanoKvmVideoStatus
import org.nanokvm.video.NanoKvmVideoTransport

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
        assertEquals(ConsoleMessage.AuthenticationExpired, backend.session.value.status)
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
        assertEquals(ConsoleMessage.AuthenticationExpired, backend.session.value.status)
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
    fun `stale input failure cannot close replacement command barrier or schedule reconnect`() =
        runBlocking {
            val client = NanoKvmClient.create(
                endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:9"),
                tokenStore = InMemorySessionTokenStore("replacement-session-token"),
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
                reconnectPolicy = ReconnectPolicy(listOf(60_000L), jitterFraction = 0.0),
            )
            backend.setPrivateField("authenticatedSession", authenticatedSession)
            backend.setPrivateField("acceptingCommands", true)
            @Suppress("UNCHECKED_CAST")
            val mutableSession = backend.privateField("mutableSession") as
                MutableStateFlow<BackendSession>
            val replacement = BackendSession(
                connection = ConnectionState.Connected,
                sessionGeneration = 42L,
            )
            mutableSession.value = replacement
            val scheduler = NanoKvmConsoleBackend::class.java.getDeclaredMethod(
                "scheduleReconnect",
                ReconnectFailure::class.java,
                java.lang.Boolean.TYPE,
                NanoKvmSessionBinding::class.java,
            ).apply { isAccessible = true }

            scheduler.invoke(
                backend,
                ReconnectFailure(IOException("late input failure")),
                false,
                NanoKvmSessionBinding("replacement", "127.0.0.1:9", 41L),
            )

            assertEquals(replacement, backend.session.value)
            assertEquals(true, backend.privateField("acceptingCommands"))
            assertEquals(null, backend.privateField("reconnectJob"))
            backend.closeAndAwait()
        }

    @Test
    fun `terminal failure publication rechecks binding after replacement wins the race`() =
        runBlocking {
            val client = NanoKvmClient.create(
                endpoint = NanoKvmEndpoint.parse("https://127.0.0.1:9"),
                tokenStore = InMemorySessionTokenStore("replacement-session-token"),
            )
            val backend = NanoKvmConsoleBackend(
                workerDispatcher = Dispatchers.Unconfined,
                reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
            )
            backend.setPrivateField(
                "authenticatedSession",
                AuthenticatedNanoKvmSession(
                    client = client,
                    profileId = "replacement",
                    authority = "127.0.0.1:9",
                    vmInfo = VmInfo(application = "2.4.3"),
                    capabilities = reflectedEmptyCapabilities(),
                ),
            )
            backend.setPrivateField("acceptingCommands", true)
            @Suppress("UNCHECKED_CAST")
            val mutableSession = backend.privateField("mutableSession") as
                MutableStateFlow<BackendSession>
            val replacement = BackendSession(
                connection = ConnectionState.Connected,
                sessionGeneration = 42L,
            )
            mutableSession.value = replacement
            val publisher = NanoKvmConsoleBackend::class.java.getDeclaredMethod(
                "publishTerminalFailureWhenBindingStillCurrent",
                NanoKvmSessionBinding::class.java,
                ReconnectFailure::class.java,
            ).apply { isAccessible = true }

            publisher.invoke(
                backend,
                NanoKvmSessionBinding("replacement", "127.0.0.1:9", 41L),
                ReconnectFailure(IOException("late terminal input failure")),
            )

            assertEquals(replacement, backend.session.value)
            assertEquals(true, backend.privateField("acceptingCommands"))
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
        assertEquals(ConsoleMessage.AuthenticationExpired, backend.session.value.status)
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
                "administrationFailure",
                NanoKvmSessionBinding::class.java,
                NanoKvmAdministrationError::class.java,
            ).apply { isAccessible = true }
            val binding = NanoKvmSessionBinding("profile-401", "127.0.0.1:9", 19L)

            classifier.invoke(backend, binding, expired)

            assertEquals(ConnectionState.Failed, backend.session.value.connection)
            assertEquals(19L, backend.session.value.sessionGeneration)
            assertEquals(ConsoleMessage.AuthenticationExpired, backend.session.value.status)
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
        trust as TrustPreflightOutcome.Failed
        assertFalse(trust.retryable)
        assertEquals(ConnectionFailure.SessionClosed, trust.failure)
        assertTrue(connect is ConnectOutcome.Failed)
        connect as ConnectOutcome.Failed
        assertFalse(connect.retryable)
        assertEquals(ConnectionFailure.SessionClosed, connect.failure)
        assertEquals(ConnectionState.Disconnected, backend.session.value.connection)
    }

    @Test
    fun `late video callbacks cannot change terminal state after close`() = runBlocking {
        val backend = NanoKvmConsoleBackend(
            workerDispatcher = Dispatchers.Unconfined,
            reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
        )
        @Suppress("UNCHECKED_CAST")
        val mutableSession = backend.privateField("mutableSession") as
            MutableStateFlow<BackendSession>
        mutableSession.value = BackendSession(
            connection = ConnectionState.Connected,
            sessionGeneration = 73L,
            remoteWidth = 800,
            remoteHeight = 600,
            framesPerSecond = 30,
            droppedFrames = 4L,
            videoStallEvents = 2L,
            status = ConsoleMessage.ConnectedToNanoKvm,
        )
        val listener = backend.privateField("videoListener") as NanoKvmVideoListener

        backend.close()
        val terminal = BackendSession(connection = ConnectionState.Disconnected)
        assertEquals(terminal, backend.session.value)

        listener.onStatusChanged(
            NanoKvmVideoStatus.Connecting(NanoKvmVideoTransport.MJPEG),
        )
        listener.onStatusChanged(
            NanoKvmVideoStatus.Streaming(NanoKvmVideoTransport.H264),
        )
        listener.onStatusChanged(
            NanoKvmVideoStatus.FallingBack(
                from = NanoKvmVideoTransport.H264,
                to = NanoKvmVideoTransport.MJPEG,
                cause = IOException("late fallback"),
            ),
        )
        listener.onStatusChanged(
            NanoKvmVideoStatus.Error(
                NanoKvmVideoTransport.H264,
                IOException("late failure"),
            ),
        )
        listener.onVideoSizeChanged(3840, 2160)
        listener.onH264FrameRendered(1L)
        listener.onWebRtcFrameRendered(2L)
        listener.onFramesDropped(9, H264FrameDropReason.STALE_BACKLOG)
        listener.onVideoStalled(NanoKvmVideoTransport.H264)

        assertEquals(terminal, backend.session.value)
        assertEquals(null, backend.privateField("reconnectJob"))
        backend.closeAndAwait()
    }

    @Test
    fun `current video callbacks publish typed stream and status semantics`() = runBlocking {
        val backend = NanoKvmConsoleBackend(
            workerDispatcher = Dispatchers.Unconfined,
            reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
        )
        @Suppress("UNCHECKED_CAST")
        val mutableSession = backend.privateField("mutableSession") as
            MutableStateFlow<BackendSession>
        mutableSession.value = BackendSession(connection = ConnectionState.Connected)
            .withActionFeedback(ConsoleMessage.VideoSettingsApplied)
        val actionRevision = checkNotNull(
            mutableSession.value.lastActionFeedback,
        ).revision
        val listener = backend.privateField("videoListener") as NanoKvmVideoListener

        listener.onStatusChanged(NanoKvmVideoStatus.Connecting(NanoKvmVideoTransport.H264))

        assertEquals(VideoStreamDescriptor.DirectH264, backend.session.value.streamLabel)
        assertEquals(
            ConsoleMessage.ConnectingVideo(VideoTransportDescriptor.DirectH264),
            backend.session.value.status,
        )
        assertEquals(
            ConsoleMessage.VideoSettingsApplied,
            backend.session.value.lastActionFeedback?.content,
        )
        assertEquals(actionRevision, backend.session.value.lastActionFeedback?.revision)

        listener.onStatusChanged(
            NanoKvmVideoStatus.FallingBack(
                from = NanoKvmVideoTransport.H264,
                to = NanoKvmVideoTransport.MJPEG,
                cause = IOException("transport diagnostic"),
            ),
        )

        assertEquals(VideoStreamDescriptor.MjpegFallback, backend.session.value.streamLabel)
        assertEquals(
            ConsoleMessage.VideoFallback(
                from = VideoTransportDescriptor.DirectH264,
                to = VideoTransportDescriptor.Mjpeg,
            ),
            backend.session.value.status,
        )
        assertEquals(
            ConsoleMessage.VideoSettingsApplied,
            backend.session.value.lastActionFeedback?.content,
        )

        listener.onStatusChanged(NanoKvmVideoStatus.Streaming(NanoKvmVideoTransport.MJPEG))

        assertEquals(VideoStreamDescriptor.Mjpeg, backend.session.value.streamLabel)
        assertEquals(null, backend.session.value.status)
        assertEquals(
            ConsoleMessage.VideoSettingsApplied,
            backend.session.value.lastActionFeedback?.content,
        )
        backend.closeAndAwait()
    }

    @Test
    fun `late video callbacks from a replaced decoder cannot mutate replacement session`() =
        runBlocking {
            val backend = NanoKvmConsoleBackend(
                workerDispatcher = Dispatchers.Unconfined,
                reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
            )
            val listener = SessionBoundVideoListener(
                backend.privateField("videoListener") as NanoKvmVideoListener,
            )
            backend.setPrivateField("activeVideoListener", listener)
            backend.invokePrivateNoArgs("beginVideoCloseLocked")
            @Suppress("UNCHECKED_CAST")
            val mutableSession = backend.privateField("mutableSession") as
                MutableStateFlow<BackendSession>
            val replacement = BackendSession(
                connection = ConnectionState.Connected,
                sessionGeneration = 74L,
                streamLabel = VideoStreamDescriptor.DirectH264,
                remoteWidth = 1920,
                remoteHeight = 1080,
            )
            mutableSession.value = replacement

            listener.onStatusChanged(
                NanoKvmVideoStatus.Connecting(NanoKvmVideoTransport.MJPEG),
            )
            listener.onVideoSizeChanged(640, 480)

            assertEquals(replacement, backend.session.value)
            backend.closeAndAwait()
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
        assertEquals(ConnectionFailure.AppInBackground, outcome.failure)
        backend.closeAndAwait()
    }

    @Test
    fun `repeated background foreground reconnect and close cycles end terminally`() = runBlocking {
        val backend = NanoKvmConsoleBackend(
            workerDispatcher = Dispatchers.Unconfined,
            reconnectPolicy = ReconnectPolicy(listOf(0L), jitterFraction = 0.0),
        )

        repeat(64) {
            backend.setForeground(false)
            backend.reconnect()
            backend.setForeground(true)
        }
        backend.setForeground(false)
        backend.closeAndAwait()
        backend.closeAndAwait()

        assertEquals(BackendSession(connection = ConnectionState.Disconnected), backend.session.value)
        assertEquals(null, backend.privateField("activeConnectJob"))
        assertEquals(null, backend.privateField("reconnectJob"))
        assertEquals(null, backend.privateField("inputMonitor"))
        assertTrue(
            (backend.privateField("videoCallbacks") as ExecutorService).isShutdown,
        )
        val connect = backend.connect(
            ConnectRequest(HostProfile.Default, "must-be-cleared".toCharArray()),
        )
        assertTrue(connect is ConnectOutcome.Failed)
        connect as ConnectOutcome.Failed
        assertFalse(connect.retryable)
        assertEquals(ConnectionFailure.SessionClosed, connect.failure)
    }

    @Test
    fun `connection failure mapping is bounded and never carries exception text`() {
        assertEquals(
            ConnectionFailure.RequestRejected(19),
            ApiResponseException(19, "appliance-controlled secret").toConnectionFailure(),
        )
        assertEquals(
            ConnectionFailure.RequestRejected(23),
            IOException(
                "wrapper detail",
                ApiResponseException(23, "appliance-controlled secret"),
            ).toConnectionFailure(),
        )
        assertEquals(
            ConnectionFailure.ProtocolError,
            InvalidApiResponseException("response contained a secret").toConnectionFailure(),
        )
        assertEquals(
            ConnectionFailure.TimedOut,
            SocketTimeoutException("destination detail").toConnectionFailure(),
        )
        assertEquals(
            ConnectionFailure.Unreachable,
            IOException("destination detail").toConnectionFailure(),
        )
        assertEquals(
            ConnectionFailure.RequestRejected(503),
            IOException("destination detail").toConnectionFailure(responseCode = 503),
        )
        assertEquals(
            ConnectionFailure.Unexpected,
            IllegalStateException("implementation detail").toConnectionFailure(),
        )
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

private fun Any.invokePrivateNoArgs(name: String): Any? =
    javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)

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

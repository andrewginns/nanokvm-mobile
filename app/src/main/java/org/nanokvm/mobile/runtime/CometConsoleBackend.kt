package org.nanokvm.mobile.runtime

import android.view.Surface
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.protocol.CertificateFingerprint
import org.nanokvm.protocol.CertificateInspector
import org.nanokvm.protocol.CometClient
import org.nanokvm.protocol.CometHid
import org.nanokvm.protocol.CometHttpFailure
import org.nanokvm.protocol.NanoKvmEndpoint
import org.nanokvm.video.CometMedia
import org.nanokvm.video.H264DecoderConfig
import org.nanokvm.video.H264FrameDropReason
import org.nanokvm.video.H264RendererListener
import org.nanokvm.video.H264RendererStatus
import org.nanokvm.video.MediaCodecH264Renderer

/** Owns the selected origin and the two sockets for a single foreground console. */
internal class CometConsoleBackend : ConsoleBackend {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(BackendSession(supportsManualResolution = false))
    override val session = state.asStateFlow()
    override val features = ConsoleFeatureBundle(this)

    private var generation = 0L
    private var foreground = true
    private var closed = false
    private var profile: HostProfile? = null
    private var client: CometClient? = null
    private var control: WebSocket? = null
    private var media: WebSocket? = null
    @Volatile private var input: CometInput? = null
    private var controlClosed = CompletableDeferred(Unit)
    private var heartbeat: Job? = null
    private var negotiationTicks = 0
    private var controlPongsMissed = 0
    private var mediaPongsMissed = 0
    private var hdmiSignal: Boolean? = null
    private var sourceOnline: Boolean? = null
    private var negotiated = false
    private var surface: Surface? = null
    private val renderer = MediaCodecH264Renderer(object : H264RendererListener {
        override fun onVideoSizeChanged(width: Int, height: Int) {
            if (width > 0 && height > 0) state.update { it.copy(remoteWidth = width, remoteHeight = height) }
        }
        override fun onStatusChanged(status: H264RendererStatus) {
            if (status is H264RendererStatus.Error) synchronized(lock) {
                if (media != null) lost(generation, ProtocolException("Video decoder failed"))
            }
        }
    })

    override suspend fun preflightTrust(profile: HostProfile): TrustPreflightOutcome {
        val endpoint = try { NanoKvmEndpoint.parse(profile.baseUrl) } catch (_: IllegalArgumentException) {
            return TrustPreflightOutcome.Failed(ConnectionFailure.InvalidAddress, false)
        }
        if (!endpoint.isSecure) return TrustPreflightOutcome.Failed(ConnectionFailure.HttpsRequired, false)
        val cert = try { CertificateInspector.inspect(endpoint) } catch (e: IOException) {
            return TrustPreflightOutcome.Failed(failure(e), true)
        }
        if (!cert.currentlyValid) return TrustPreflightOutcome.Failed(ConnectionFailure.CertificateDateInvalid, false)
        val pin = try { profile.trustedCertificateSha256?.let(CertificateFingerprint::parse) }
        catch (_: IllegalArgumentException) { return TrustPreflightOutcome.Failed(ConnectionFailure.InvalidSavedCertificate, false) }
        val reason = when {
            pin == cert.fingerprint -> CertificatePresentationReason.MatchesSavedCertificate
            pin != null -> CertificatePresentationReason.DiffersFromSavedCertificate
            !cert.hostnameVerified -> CertificatePresentationReason.NameMismatch
            cert.systemTrusted -> CertificatePresentationReason.TrustedByAndroid
            else -> CertificatePresentationReason.PrivateCertificateNotTrusted
        }
        val details = cert.toCertificateDetails(reason)
        return if (pin == cert.fingerprint) TrustPreflightOutcome.Trusted(CertificateTrustSource.SavedLeafPin, details)
        else TrustPreflightOutcome.CertificateReviewRequired(details)
    }

    override suspend fun connect(request: ConnectRequest): ConnectOutcome = withContext(Dispatchers.IO) {
        val owner = synchronized(lock) {
            if (closed || !foreground) return@withContext ConnectOutcome.Failed(ConnectionFailure.AppInBackground, false)
            retire(forget = true)
            generation
        }
        val destination = request.profile.copy(trustedCertificateSha256 = request.acceptedCertificateSha256)
        val trust = preflightTrust(destination)
        when (trust) {
            is TrustPreflightOutcome.Failed -> return@withContext ConnectOutcome.Failed(trust.failure, trust.retryable)
            is TrustPreflightOutcome.CertificateReviewRequired -> return@withContext ConnectOutcome.CertificateReviewRequired(trust.certificate)
            is TrustPreflightOutcome.Trusted -> Unit
        }
        val next = CometClient(NanoKvmEndpoint.parse(destination.baseUrl), CertificateFingerprint.parse(trust.certificate.sha256))
        try {
            synchronized(lock) {
                if (owner != generation || closed || !foreground) {
                    next.close()
                    throw CancellationException()
                }
                profile = destination
                client = next
            }
            next.login(destination.username, request.password, request.totp)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (owner != generation || !foreground) throw CancellationException()
                startSockets(next, reconnecting = false)
            }
            ConnectOutcome.Connected
        } catch (e: Exception) {
            synchronized(lock) { if (client === next && generation == owner) retire(forget = true) }
            if (e is CancellationException) throw e
            ConnectOutcome.Failed(failure(e), e !is CometHttpFailure || e.status == 429)
        }
    }

    // Called under lock. Every callback and queued input belongs to this socket generation.
    private fun startSockets(target: CometClient, reconnecting: Boolean) {
        val owner = ++generation
        controlPongsMissed = 0
        negotiationTicks = 0
        mediaPongsMissed = 0
        negotiated = false
        hdmiSignal = null
        sourceOnline = null
        state.value = BackendSession(
            sessionGeneration = owner, supportsManualResolution = false,
            connection = if (reconnecting) ConnectionState.Reconnecting else ConnectionState.Connecting,
            status = ConsoleMessage.ConnectingVideo(VideoTransportDescriptor.DirectH264),
        )
        val previousClose = controlClosed
        scope.launch {
            try {
                previousClose.await()
                if (!synchronized(lock) { owner == generation }) return@launch
                target.enableDirectStream()
                synchronized(lock) {
                    if (owner == generation) {
                        val closed = CompletableDeferred<Unit>()
                        control = target.openSocket(false, listener(owner, target, video = false, closed))
                        controlClosed = closed
                    }
                }
            } catch (e: IOException) {
                synchronized(lock) { lost(owner, e) }
            }
        }
        heartbeat = scope.launch {
            while (true) {
                delay(1000)
                synchronized(lock) {
                    if (generation != owner) return@launch
                    if ((!negotiated && ++negotiationTicks >= 20) || ++controlPongsMissed >= 15 || (media != null && ++mediaPongsMissed >= 5) ||
                        control?.send(PING) == false || media?.send(PING) == false) {
                        lost(owner, SocketTimeoutException())
                    }
                }
            }
        }
    }

    private fun listener(owner: Long, target: CometClient, video: Boolean, closed: CompletableDeferred<Unit>? = null): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (owner != generation) {
                webSocket.cancel()
                return@synchronized
            }
            if (!video) {
                input = CometInput(scope, lock, webSocket::send, target::selectMouseOutput,
                    { lost(owner, it) },
                    { progress -> state.update { it.copy(pasteProgress = progress) } },
                    { message -> state.update { it.withActionFeedback(message) } },
                )
                media = target.openSocket(true, listener(owner, target, video = true))
                startDecoder()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (owner != generation) return@synchronized
            try {
                require(text.length <= 1024 * 1024)
                val message = Json.parseToJsonElement(text) as? JsonObject ?: return@synchronized
                val event = message["event"] as? JsonObject ?: return@synchronized
                when ((message["event_type"] as? JsonPrimitive)?.content) {
                    "media" -> if (video && !negotiated) {
                        require((event["video"] as? JsonObject)?.containsKey("h264") == true)
                        require(webSocket.send(CometMedia.START_H264))
                        negotiated = true
                        updateStreamStatus()
                    }
                    "streamer" -> if (!video) streamerState(event)
                    "hid" -> if (!video) {
                        val outputs = (event["mouse"] as? JsonObject)?.get("outputs") as? JsonObject
                        val active = (outputs?.get("active") as? JsonPrimitive)?.content
                        if (active != null) input?.relativeOutput = when (active) {
                            "usb" -> false
                            "usb_rel" -> true
                            else -> null
                        }
                    }
                }
            } catch (_: IllegalArgumentException) {
                lost(owner, ProtocolException("Invalid Comet state"))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(lock) {
            if (generation != owner) return@synchronized
            if (bytes == PONG) {
                if (video) mediaPongsMissed = 0 else controlPongsMissed = 0
                return@synchronized
            }
            if (!video || !negotiated || surface == null) return@synchronized
            try {
                val frame = CometMedia.frame(bytes, System.nanoTime() / 1000) ?: return@synchronized
                if (renderer.offer(frame).dropReason == H264FrameDropReason.STALE_BACKLOG) webSocket.send(KEYFRAME)
            } catch (_: IllegalArgumentException) {
                // Dropping a reference picture invalidates subsequent delta frames.
                startDecoder()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            synchronized(lock) { lost(owner, IOException("Socket closed")) }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed?.complete(Unit)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed?.complete(Unit)
            synchronized(lock) { lost(owner, response?.let { CometHttpFailure(it.code) } ?: t) }
        }
    }

    private fun streamerState(event: JsonObject) {
        val streamer = event["streamer"] as? JsonObject ?: return
        val source = streamer["source"] as? JsonObject
        val hdmi = streamer["hdmi"] as? JsonObject
        (hdmi?.get("signal") as? JsonPrimitive)?.booleanOrNull?.let { hdmiSignal = it }
        (source?.get("online") as? JsonPrimitive)?.booleanOrNull?.let { sourceOnline = it }
        val size = source?.get("resolution") as? JsonObject
        val width = (size?.get("width") as? JsonPrimitive)?.intOrNull
        val height = (size?.get("height") as? JsonPrimitive)?.intOrNull
        val old = state.value
        if (width != null && height != null && width in 1..8192 && height in 1..8192 &&
            (old.remoteWidth != width || old.remoteHeight != height)) {
            state.value = old.copy(remoteWidth = width, remoteHeight = height)
            startDecoder()
        }
        if (old.status == ConsoleMessage.NoHdmiSignal && (hdmiSignal ?: sourceOnline) == true) startDecoder()
        updateStreamStatus()
    }

    private fun updateStreamStatus() {
        state.update { current ->
            when {
                (hdmiSignal ?: sourceOnline) == false ->
                    current.copy(connection = ConnectionState.Degraded, status = ConsoleMessage.NoHdmiSignal)
                negotiated -> current.copy(connection = ConnectionState.Connected, status = ConsoleMessage.ConnectedToNanoKvm)
                current.status == ConsoleMessage.NoHdmiSignal ->
                    current.copy(connection = ConnectionState.Connecting, status = ConsoleMessage.ConnectingVideo(VideoTransportDescriptor.DirectH264))
                else -> current
            }
        }
    }

    private fun lost(owner: Long, cause: Throwable) = synchronized(lock) {
        if (owner != generation) return@synchronized
        val expired = cause is CometHttpFailure && cause.status in setOf(401, 403)
        retire(forget = expired, graceful = false)
        state.update { it.copy(connection = ConnectionState.Failed, status =
            if (expired) ConsoleMessage.AuthenticationExpired else ConsoleMessage.ConnectionFailed(failure(cause))) }
        // RM1 can close a socket without an HTTP status. Resolve expiry using the stock check.
        val target = client ?: return@synchronized
        val checkOwner = generation
        scope.launch {
            try { target.checkSession() } catch (e: CometHttpFailure) {
                if (e.status in setOf(401, 403)) synchronized(lock) {
                    if (checkOwner == generation) {
                        retire(forget = true)
                        state.update { it.copy(connection = ConnectionState.Failed, status = ConsoleMessage.AuthenticationExpired) }
                    }
                }
            } catch (_: IOException) { /* The transport error remains actionable. */ }
        }
        Unit
    }

    private fun failure(error: Throwable) = when (error) {
        is CometHttpFailure -> ConnectionFailure.RequestRejected(error.status)
        is SocketTimeoutException -> ConnectionFailure.TimedOut
        is ProtocolException -> ConnectionFailure.ProtocolError
        is javax.net.ssl.SSLException -> ConnectionFailure.CertificateInspectionFailed
        else -> ConnectionFailure.Unreachable
    }

    private fun retire(forget: Boolean, graceful: Boolean = true) {
        generation++
        heartbeat?.cancel()
        heartbeat = null
        input?.release(retire = true)
        input = null
        if (graceful) control?.close(1000, null) else control?.cancel()
        media?.cancel()
        media = null
        control = null
        renderer.stop()
        if (forget) {
            client?.close()
            client = null
            profile = null
        }
        state.update { it.copy(connection = ConnectionState.Disconnected, sessionGeneration = generation,
            pasteProgress = null, inputReleaseGeneration = it.inputReleaseGeneration + 1) }
    }

    override fun reconnect() = synchronized(lock) {
        val target = client ?: return@synchronized
        if (!foreground || closed) return@synchronized
        retire(forget = false)
        startSockets(target, reconnecting = true)
    }

    override fun cancelReconnect() = synchronized(lock) {
        if (state.value.connection == ConnectionState.Reconnecting) {
            retire(forget = false)
            state.update { it.copy(connection = ConnectionState.Failed, status = ConsoleMessage.ReconnectCancelled) }
        }
    }

    override fun setForeground(isForeground: Boolean) = synchronized(lock) {
        if (foreground == isForeground || closed) return@synchronized
        foreground = isForeground
        if (!foreground) {
            val connecting = client != null && profile != null && state.value.connection == ConnectionState.Disconnected
            retire(forget = connecting)
            if (client != null) state.update { it.copy(status = ConsoleMessage.PausedInBackground) }
        } else if (client != null && state.value.status == ConsoleMessage.PausedInBackground) reconnect()
    }

    override suspend fun disconnect() = synchronized(lock) { retire(forget = true) }
    override fun close() = synchronized(lock) {
        closed = true
        retire(forget = true)
        scope.cancel()
    }

    override suspend fun closeAndAwait() {
        close()
        scope.coroutineContext[Job]?.join()
        controlClosed.await()
        withContext(Dispatchers.IO) { renderer.stopAndAwaitRelease().get() }
    }

    private fun startDecoder() {
        val output = surface ?: return
        if (!output.isValid || media == null) return
        renderer.start(output, H264DecoderConfig(state.value.remoteWidth, state.value.remoteHeight))
        media?.send(KEYFRAME)
    }

    override fun attachVideoSurface(surface: Surface) = synchronized(lock) {
        this.surface = surface
        startDecoder()
    }
    override fun detachVideoSurface(surface: Surface) = synchronized(lock) {
        if (this.surface === surface) { this.surface = null; renderer.stop() }
    }

    override fun selectRelativePointer(relative: Boolean) { input?.pointer(relative) }
    override fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton>) {
        input?.pointer(false, CometHid.absolute(x, y), buttons.toSet())
    }
    override fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) {
        input?.pointer(true, CometHid.delta(false, deltaX, deltaY), buttons.toSet())
    }
    override fun mouseButton(button: MouseButton, pressed: Boolean) { input?.mouseButton(button, pressed) }
    override fun scrollWheel(steps: Int) { input?.wheel(0, steps) }
    override fun scrollHorizontal(steps: Int) { input?.wheel(steps, 0) }
    override fun key(key: RemoteKey, pressed: Boolean) { input?.key(key, pressed) }
    override fun key(key: RemoteKey, pressed: Boolean, context: RemoteTextEditContext) { input?.key(key, pressed, context) }
    override fun tapKey(key: RemoteKey, context: RemoteTextEditContext) { input?.tap(key, context) }
    override fun typeCommittedText(text: String, layout: KeyboardLayout) { input?.type(text, layout, false) }
    override suspend fun applyTextEdit(deleteBefore: Int, insertText: String, layout: KeyboardLayout, context: RemoteTextEditContext) =
        input?.edit(deleteBefore, insertText, layout, context) ?: RemoteTextEditResult.Stale

    override fun releaseAllInput() = synchronized(lock) {
        input?.release()
        state.update { it.copy(inputReleaseGeneration = it.inputReleaseGeneration + 1) }
    }

    override fun pasteText(request: ApprovedPasteRequest) = synchronized(lock) {
        val selected = profile
        if (selected != null && request.matchesDestination(selected.id, selected.authority, generation)) {
            input?.type(request.content, request.keyboardLayout, true)
        }
        Unit
    }
    override fun cancelPaste() { input?.cancelPaste() }

    private companion object {
        val PING = byteArrayOf(0).toByteString()
        val PONG = byteArrayOf(-1).toByteString()
        val KEYFRAME = byteArrayOf(1).toByteString()
    }
}

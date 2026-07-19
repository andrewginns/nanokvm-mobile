package org.nanokvm.video

import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal interface NanoKvmWebRtcRenderTarget

internal interface NanoKvmWebRtcPeer : AutoCloseable {
    fun start()
    fun applyRemoteAnswer(
        description: NanoKvmWebRtcSessionDescription,
        onApplied: () -> Unit,
        onFailure: (Throwable) -> Unit,
    )
    fun addRemoteCandidate(candidate: NanoKvmWebRtcIceCandidate): Boolean
}

internal interface NanoKvmWebRtcPeerListener {
    fun onLocalOffer(description: NanoKvmWebRtcSessionDescription)
    fun onLocalCandidate(candidate: NanoKvmWebRtcIceCandidate)
    fun onFrameRendered(timestampNs: Long)
    fun onVideoSizeChanged(width: Int, height: Int)
    fun onFailure(cause: Throwable)
}

internal fun interface NanoKvmWebRtcPeerFactory {
    fun create(
        iceServers: List<NanoKvmWebRtcIceServer>,
        target: NanoKvmWebRtcRenderTarget,
        listener: NanoKvmWebRtcPeerListener,
    ): NanoKvmWebRtcPeer
}

internal interface NanoKvmWebRtcSourceListener {
    fun onConnecting() = Unit
    fun onOpen() = Unit
    fun onFrameRendered(timestampNs: Long)
    fun onVideoSizeChanged(width: Int, height: Int) = Unit
    fun onClosed(code: Int, reason: String)
    fun onFailure(cause: Throwable, responseCode: Int?)
}

/**
 * One-shot, generation-bound NanoKVM 2.4.3 WebRTC signaling session.
 *
 * Offers and ICE candidates live only in this instance. Closing clears both candidate queues and
 * the peer before any replacement transport is constructed; nothing is retried or replayed.
 */
internal class NanoKvmWebRtcSource(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val token: String,
    private val target: NanoKvmWebRtcRenderTarget,
    private val peerFactory: NanoKvmWebRtcPeerFactory,
    private val listener: NanoKvmWebRtcSourceListener,
    private val heartbeatIntervalMillis: Long = HEARTBEAT_INTERVAL_MILLIS,
) : AutoCloseable {
    private val lock = Any()
    private val heartbeatExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "NanoKVM-WebRTC-heartbeat").apply { isDaemon = true }
    }.apply { setRemoveOnCancelPolicy(true) }

    private var started = false
    private var active = false
    private var socket: WebSocket? = null
    private var peer: NanoKvmWebRtcPeer? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var iceServersReceived = false
    private var offerDispatched = false
    private var answerApplied = false
    private var answerPending = false
    private var failureClaimed = false
    private val pendingLocalCandidates = ArrayDeque<NanoKvmWebRtcIceCandidate>()
    private val pendingRemoteCandidates = ArrayDeque<NanoKvmWebRtcIceCandidate>()

    init {
        require(heartbeatIntervalMillis >= MIN_HEARTBEAT_INTERVAL_MILLIS) {
            "WebRTC heartbeat interval is too short"
        }
    }

    fun start() {
        synchronized(lock) {
            check(!started) { "A WebRTC signaling source is one-shot" }
            started = true
            active = true
        }
        listener.onConnecting()
        val request = Request.Builder()
            .url(baseUrl.nanoKvmEndpoint("/api/stream/h264"))
            .header("Cookie", nanoKvmCookie(token))
            .build()
        val created = client.newWebSocket(request, SocketCallback())
        synchronized(lock) {
            if (active) {
                if (socket == null) socket = created
            } else {
                created.cancel()
            }
        }
    }

    fun stop() {
        val resources = synchronized(lock) { detachResourcesLocked() }
        resources.close(gracefully = true)
    }

    override fun close() {
        stop()
        heartbeatExecutor.shutdownNow()
    }

    internal fun pendingCandidateCountsForTest(): Pair<Int, Int> = synchronized(lock) {
        pendingLocalCandidates.size to pendingRemoteCandidates.size
    }

    private fun handleServerMessage(text: String) {
        try {
            when (val signal = NanoKvmWebRtcSignaling.parseServerMessage(text)) {
                is NanoKvmWebRtcServerSignal.IceServers -> handleIceServers(signal.servers)
                is NanoKvmWebRtcServerSignal.VideoAnswer -> handleAnswer(signal.description)
                is NanoKvmWebRtcServerSignal.VideoCandidate ->
                    handleRemoteCandidate(signal.candidate)
                NanoKvmWebRtcServerSignal.Heartbeat,
                is NanoKvmWebRtcServerSignal.Unknown -> Unit
            }
        } catch (error: Throwable) {
            fail(error, null)
        }
    }

    private fun handleIceServers(servers: List<NanoKvmWebRtcIceServer>) {
        val created = try {
            synchronized(lock) {
                if (!active || failureClaimed) return
                if (iceServersReceived) protocolFailure("WebRTC ICE servers were sent more than once")
                iceServersReceived = true
            }
            peerFactory.create(servers, target, PeerEvents())
        } catch (error: Throwable) {
            fail(error, null)
            return
        }
        synchronized(lock) {
            if (!active || failureClaimed) {
                created.close()
                return
            }
            peer = created
        }
        try {
            created.start()
        } catch (error: Throwable) {
            fail(error, null)
        }
    }

    private fun handleAnswer(description: NanoKvmWebRtcSessionDescription) {
        val activePeer = synchronized(lock) {
            if (!active || failureClaimed) return
            if (!offerDispatched) protocolFailure("WebRTC answer arrived before the offer")
            if (answerPending || answerApplied) protocolFailure("WebRTC answer was sent more than once")
            answerPending = true
            peer ?: protocolFailure("WebRTC answer arrived before peer creation")
        }
        activePeer.applyRemoteAnswer(
            description,
            onApplied = { onAnswerApplied(activePeer) },
            onFailure = { error -> fail(error, null) },
        )
    }

    private fun onAnswerApplied(expectedPeer: NanoKvmWebRtcPeer) {
        val candidates = synchronized(lock) {
            if (!active || failureClaimed || peer !== expectedPeer) return
            answerPending = false
            answerApplied = true
            pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        for (candidate in candidates) {
            if (!expectedPeer.addRemoteCandidate(candidate)) {
                fail(IOException("WebRTC rejected a remote ICE candidate"), null)
                return
            }
        }
    }

    private fun handleRemoteCandidate(candidate: NanoKvmWebRtcIceCandidate) {
        val activePeer: NanoKvmWebRtcPeer?
        val addNow: Boolean
        synchronized(lock) {
            if (!active || failureClaimed) return
            activePeer = peer ?: protocolFailure("WebRTC candidate arrived before peer creation")
            addNow = answerApplied
            if (!addNow) {
                if (pendingRemoteCandidates.size >= MAX_PENDING_CANDIDATES) {
                    protocolFailure("Too many pending WebRTC candidates")
                }
                pendingRemoteCandidates.addLast(candidate)
            }
        }
        if (addNow && activePeer?.addRemoteCandidate(candidate) != true) {
            fail(IOException("WebRTC rejected a remote ICE candidate"), null)
        }
    }

    private fun dispatchOffer(description: NanoKvmWebRtcSessionDescription) {
        val queued: List<NanoKvmWebRtcIceCandidate>
        val sent = synchronized(lock) {
            if (!active || failureClaimed) return
            if (offerDispatched) protocolFailure("WebRTC attempted a second offer")
            val activeSocket = socket ?: return
            val accepted = activeSocket.send(NanoKvmWebRtcSignaling.encodeOffer(description))
            if (accepted) {
                offerDispatched = true
                queued = pendingLocalCandidates.toList()
                pendingLocalCandidates.clear()
            } else {
                queued = emptyList()
            }
            accepted
        }
        if (!sent) {
            fail(IOException("WebRTC signaling socket rejected the offer"), null)
            return
        }
        queued.forEach(::dispatchLocalCandidate)
    }

    private fun dispatchLocalCandidate(candidate: NanoKvmWebRtcIceCandidate) {
        val sent = synchronized(lock) {
            if (!active || failureClaimed) return
            if (!offerDispatched) {
                if (pendingLocalCandidates.size >= MAX_PENDING_CANDIDATES) {
                    protocolFailure("Too many pending WebRTC candidates")
                }
                pendingLocalCandidates.addLast(candidate)
                return
            }
            socket?.send(NanoKvmWebRtcSignaling.encodeCandidate(candidate)) == true
        }
        if (!sent) fail(IOException("WebRTC signaling socket rejected an ICE candidate"), null)
    }

    private fun armHeartbeat(webSocket: WebSocket) {
        synchronized(lock) {
            if (!active || socket !== webSocket) return
            heartbeat?.cancel(false)
            heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                { sendHeartbeat(webSocket) },
                heartbeatIntervalMillis,
                heartbeatIntervalMillis,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun sendHeartbeat(expectedSocket: WebSocket) {
        val accepted = synchronized(lock) {
            active && !failureClaimed && socket === expectedSocket &&
                expectedSocket.send(NanoKvmWebRtcSignaling.encodeHeartbeat())
        }
        if (!accepted) fail(IOException("WebRTC heartbeat could not be sent"), null)
    }

    private fun fail(cause: Throwable, responseCode: Int?) {
        val resources = synchronized(lock) {
            if (!active || failureClaimed) return
            failureClaimed = true
            detachResourcesLocked()
        }
        resources.close(gracefully = false)
        listener.onFailure(sanitizeFailure(cause), responseCode)
    }

    private fun detachResourcesLocked(): DetachedResources {
        check(Thread.holdsLock(lock))
        active = false
        heartbeat?.cancel(false)
        heartbeat = null
        pendingLocalCandidates.clear()
        pendingRemoteCandidates.clear()
        answerPending = false
        answerApplied = false
        offerDispatched = false
        iceServersReceived = false
        return DetachedResources(socket.also { socket = null }, peer.also { peer = null })
    }

    private fun sanitizeFailure(cause: Throwable): Throwable = when (cause) {
        is NanoKvmWebRtcProtocolException -> cause
        is IOException -> cause
        else -> IOException("WebRTC negotiation or rendering failed", cause)
    }

    private fun protocolFailure(message: String): Nothing =
        throw NanoKvmWebRtcProtocolException(message)

    private inner class SocketCallback : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val current = synchronized(lock) {
                if (active && socket == null) socket = webSocket
                active && socket === webSocket
            }
            if (!current) {
                webSocket.cancel()
                return
            }
            listener.onOpen()
            armHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(lock) { active && socket === webSocket }) handleServerMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (synchronized(lock) { active && socket === webSocket }) {
                fail(NanoKvmWebRtcProtocolException("WebRTC signaling must be text"), null)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (synchronized(lock) { active && socket === webSocket }) webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val resources = synchronized(lock) {
                if (!active || socket !== webSocket) return
                detachResourcesLocked()
            }
            resources.close(gracefully = false)
            listener.onClosed(code, reason.take(MAX_CLOSE_REASON_CHARS))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (synchronized(lock) { active && socket === webSocket }) fail(t, response?.code)
        }
    }

    private inner class PeerEvents : NanoKvmWebRtcPeerListener {
        override fun onLocalOffer(description: NanoKvmWebRtcSessionDescription) =
            dispatchOffer(description)

        override fun onLocalCandidate(candidate: NanoKvmWebRtcIceCandidate) =
            dispatchLocalCandidate(candidate)

        override fun onFrameRendered(timestampNs: Long) {
            if (synchronized(lock) { active && !failureClaimed }) listener.onFrameRendered(timestampNs)
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            if (synchronized(lock) { active && !failureClaimed }) {
                listener.onVideoSizeChanged(width, height)
            }
        }

        override fun onFailure(cause: Throwable) = fail(cause, null)
    }

    private data class DetachedResources(
        val socket: WebSocket?,
        val peer: NanoKvmWebRtcPeer?,
    ) {
        fun close(gracefully: Boolean) {
            runCatching { peer?.close() }
            if (gracefully) {
                if (socket?.close(NORMAL_CLOSE_CODE, "client stopped") == false) socket.cancel()
            } else {
                socket?.cancel()
            }
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MILLIS = 60_000L
        const val MIN_HEARTBEAT_INTERVAL_MILLIS = 10L
        const val MAX_PENDING_CANDIDATES = 64
        const val MAX_CLOSE_REASON_CHARS = 256
        const val NORMAL_CLOSE_CODE = 1000
    }
}

package org.nanokvm.video

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

interface DirectH264SourceListener {
    fun onConnecting() = Unit
    fun onOpen() = Unit
    fun onFrame(frame: H264AccessUnit)
    fun onMalformedFrame(cause: IllegalArgumentException) = Unit
    fun onClosed(code: Int, reason: String) = Unit
    fun onFailure(cause: Throwable, responseCode: Int?)
}

/** Lifecycle-safe OkHttp source for `/api/stream/h264/direct`. */
class DirectH264WebSocketSource(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val token: String,
    private val listener: DirectH264SourceListener,
    private val maxAccessUnitBytes: Int = NanoKvmH264FrameParser.DEFAULT_MAX_ACCESS_UNIT_BYTES,
) : AutoCloseable {
    private val generation = AtomicLong(0)
    private val lock = Any()
    private val webSocketClient = client.newBuilder()
        // Bound teardown after rejecting a complete oversized message. This does not claim a
        // pre-allocation bound: OkHttp has already materialized the ByteString at this point.
        .webSocketCloseTimeout(WEBSOCKET_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(UncompressedDirectWebSocketInterceptor)
        .build()
    private var socket: WebSocket? = null

    init {
        require(maxAccessUnitBytes > 0) { "Maximum H.264 access-unit size must be positive" }
    }

    fun start() {
        val run = generation.incrementAndGet()
        val previous = synchronized(lock) {
            val old = socket
            socket = null
            old
        }
        previous?.cancel()
        listener.onConnecting()

        val request = Request.Builder()
            .url(baseUrl.nanoKvmEndpoint("/api/stream/h264/direct"))
            .header("Cookie", nanoKvmCookie(token))
            .build()
        val callback = Callback(run)
        val created = webSocketClient.newWebSocket(request, callback)
        synchronized(lock) {
            if (generation.get() == run) {
                socket = created
            } else {
                created.cancel()
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        val current = synchronized(lock) {
            val value = socket
            socket = null
            value
        }
        if (current?.close(NORMAL_CLOSE_CODE, "client stopped") == false) {
            current.cancel()
        }
    }

    override fun close() = stop()

    // Generation is authoritative. Avoid checking [socket] here because OkHttp may invoke a
    // callback immediately after newWebSocket() and before its return value is assigned.
    private fun isCurrent(run: Long): Boolean = generation.get() == run

    private inner class Callback(
        private val run: Long,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isCurrent(run)) listener.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(run)) return
            if (bytes.size.toLong() > maxAccessUnitBytes.toLong() + NanoKvmH264FrameParser.HEADER_SIZE) {
                rejectOversizedMessage(webSocket, run)
                return
            }
            try {
                listener.onFrame(NanoKvmH264FrameParser.parse(bytes, maxAccessUnitBytes))
            } catch (error: IllegalArgumentException) {
                listener.onMalformedFrame(error)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrent(run)) webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(run)) return
            synchronized(lock) { if (socket === webSocket) socket = null }
            listener.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(run)) return
            synchronized(lock) { if (socket === webSocket) socket = null }
            listener.onFailure(t, response?.code)
        }
    }

    private fun rejectOversizedMessage(webSocket: WebSocket, run: Long) {
        val claimed = synchronized(lock) {
            if (generation.get() != run) return@synchronized false
            generation.incrementAndGet()
            if (socket === webSocket) socket = null
            true
        }
        if (!claimed) return

        // Unlike a corrupt in-range packet, an oversize packet is a transport-level availability
        // violation. Cancel immediately so the peer cannot force another complete allocation while
        // a graceful close is pending, then let the session's normal failure policy choose fallback.
        webSocket.cancel()
        listener.onMalformedFrame(
            IllegalArgumentException(
                "NanoKVM H.264 access unit exceeds the $maxAccessUnitBytes-byte limit",
            ),
        )
        listener.onFailure(IOException("H.264 WebSocket message exceeded its configured limit"), null)
    }

    private companion object {
        const val NORMAL_CLOSE_CODE = 1000
        const val WEBSOCKET_CLOSE_TIMEOUT_SECONDS = 2L
    }
}

/** See the protocol transport's equivalent policy; direct video is also safe when used alone. */
private object UncompressedDirectWebSocketInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.header("Upgrade").equals("websocket", ignoreCase = true)) {
            return chain.proceed(request)
        }
        val response = chain.proceed(
            request.newBuilder().removeHeader("Sec-WebSocket-Extensions").build(),
        )
        if (response.header("Sec-WebSocket-Extensions") != null) {
            response.close()
            throw ProtocolException("WebSocket compression negotiation is not permitted")
        }
        return response
    }
}

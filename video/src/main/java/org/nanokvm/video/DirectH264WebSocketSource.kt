package org.nanokvm.video

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicLong

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
        val created = client.newWebSocket(request, callback)
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

    private companion object {
        const val NORMAL_CLOSE_CODE = 1000
    }
}

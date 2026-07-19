package org.nanokvm.video

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

interface MjpegStreamListener {
    fun onConnecting() = Unit
    fun onOpen(boundary: String) = Unit
    /** The byte array belongs to the receiver after this callback returns. */
    fun onJpegFrame(jpeg: ByteArray)
    fun onClosed() = Unit
    fun onFailure(cause: Throwable, responseCode: Int?)
}

/** Streaming OkHttp reader for NanoKVM's Content-Length based MJPEG endpoint. */
class MjpegStreamReader(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val token: String,
    private val listener: MjpegStreamListener,
    private val maxFrameBytes: Int = MjpegMultipartParser.DEFAULT_MAX_FRAME_BYTES,
) : AutoCloseable {
    private val generation = AtomicLong(0)
    private val lock = Any()
    private var call: Call? = null

    fun start() {
        val run = generation.incrementAndGet()
        val request = Request.Builder()
            .url(baseUrl.nanoKvmEndpoint("/api/stream/mjpeg"))
            .header("Cookie", nanoKvmCookie(token))
            .build()
        val created = client.newCall(request)
        val old = synchronized(lock) {
            val value = call
            call = created
            value
        }
        old?.cancel()
        listener.onConnecting()
        created.enqueue(StreamCallback(run, created))
    }

    fun stop() {
        generation.incrementAndGet()
        synchronized(lock) {
            call?.cancel()
            call = null
        }
    }

    override fun close() = stop()

    private fun isCurrent(run: Long, candidate: Call): Boolean =
        generation.get() == run && synchronized(lock) { call === candidate }

    private inner class StreamCallback(
        private val run: Long,
        private val candidate: Call,
    ) : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!isCurrent(run, candidate)) return
            synchronized(lock) { if (this@MjpegStreamReader.call === candidate) this@MjpegStreamReader.call = null }
            listener.onFailure(e, null)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!isCurrent(run, candidate)) return
                if (!response.isSuccessful) {
                    synchronized(lock) { if (this@MjpegStreamReader.call === candidate) this@MjpegStreamReader.call = null }
                    listener.onFailure(IOException("MJPEG HTTP ${response.code}"), response.code)
                    return
                }

                val contentType = response.header("Content-Type")
                val boundary = MjpegMultipartParser.boundaryFromContentType(contentType) ?: "frame"
                listener.onOpen(boundary)
                try {
                    MjpegMultipartParser(boundary, maxFrameBytes).read(response.body.byteStream()) { jpeg ->
                        if (!isCurrent(run, candidate)) return@read false
                        listener.onJpegFrame(jpeg)
                        true
                    }
                    if (isCurrent(run, candidate)) {
                        synchronized(lock) { if (this@MjpegStreamReader.call === candidate) this@MjpegStreamReader.call = null }
                        listener.onClosed()
                    }
                } catch (error: Throwable) {
                    if (isCurrent(run, candidate)) {
                        synchronized(lock) { if (this@MjpegStreamReader.call === candidate) this@MjpegStreamReader.call = null }
                        listener.onFailure(error, response.code)
                    }
                }
            }
        }
    }
}

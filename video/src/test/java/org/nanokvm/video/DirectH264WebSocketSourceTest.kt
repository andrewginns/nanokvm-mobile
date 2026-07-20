package org.nanokvm.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectH264WebSocketSourceTest {
    @Test
    fun `oversized access unit cancels transport while exact boundary remains renderable`() {
        val server = MockWebServer()
        server.start()
        val peer = RecordingPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val listener = RecordingListener()
        val maximum = 1_024
        val source = DirectH264WebSocketSource(
            client = client,
            baseUrl = server.url("/"),
            token = "video-token",
            listener = listener,
            maxAccessUnitBytes = maximum,
        )

        try {
            source.start()
            assertTrue(peer.opened.await(2, TimeUnit.SECONDS))
            assertTrue(listener.opened.await(2, TimeUnit.SECONDS))
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/api/stream/h264/direct", request.path)
            assertEquals("nano-kvm-token=video-token", request.getHeader("Cookie"))
            assertNull(request.getHeader("Sec-WebSocket-Extensions"))

            val boundary = ByteArray(NanoKvmH264FrameParser.HEADER_SIZE + maximum)
            boundary[0] = 1
            assertTrue(requireNotNull(peer.socket.get()).send(boundary.toByteString()))
            assertTrue(listener.frame.await(2, TimeUnit.SECONDS))
            assertEquals(maximum, listener.lastFrame.get()?.data?.size)

            val oversized = ByteArray(NanoKvmH264FrameParser.HEADER_SIZE + maximum + 1)
            assertTrue(requireNotNull(peer.socket.get()).send(oversized.toByteString()))
            assertTrue(listener.malformed.await(2, TimeUnit.SECONDS))
            assertTrue(listener.failed.await(2, TimeUnit.SECONDS))
            assertEquals(1, listener.frameCount.get())
            assertTrue(listener.failure.get() is java.io.IOException)
            assertTrue(peer.failed.await(2, TimeUnit.SECONDS))
        } finally {
            source.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    private class RecordingPeer : WebSocketListener() {
        val socket = AtomicReference<WebSocket?>()
        val opened = CountDownLatch(1)
        val failed = CountDownLatch(1)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket.set(webSocket)
            opened.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failed.countDown()
            response?.close()
        }
    }

    private class RecordingListener : DirectH264SourceListener {
        val opened = CountDownLatch(1)
        val frame = CountDownLatch(1)
        val malformed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val frameCount = AtomicInteger(0)
        val lastFrame = AtomicReference<H264AccessUnit?>()
        val failure = AtomicReference<Throwable?>()

        override fun onOpen() {
            opened.countDown()
        }

        override fun onFrame(frame: H264AccessUnit) {
            lastFrame.set(frame)
            frameCount.incrementAndGet()
            this.frame.countDown()
        }

        override fun onMalformedFrame(cause: IllegalArgumentException) {
            malformed.countDown()
        }

        override fun onFailure(cause: Throwable, responseCode: Int?) {
            failure.set(cause)
            failed.countDown()
        }
    }
}

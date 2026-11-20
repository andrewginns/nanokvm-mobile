package org.nanokvm.video

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NanoKvmMjpegSessionInstrumentedTest {
    @Test
    fun acceptedBitmapTransfersOwnershipAndReportsStreaming() {
        MjpegFixture { true }.use { fixture ->
            fixture.start()
            assertTrue("Accepted bitmap did not report streaming", fixture.streaming.await(5, TimeUnit.SECONDS))
            val bitmap = checkNotNull(fixture.bitmap.get())
            assertEquals(4, bitmap.width)
            assertEquals(4, bitmap.height)
            assertFalse("The receiver owns an accepted bitmap", bitmap.isRecycled)

            fixture.session.stop()
            fixture.awaitCallbacks()
            assertFalse("Stopping must not recycle a transferred bitmap", bitmap.isRecycled)
        }
    }

    @Test
    fun rejectedBitmapIsRecycledAndDoesNotSatisfyFirstFrameWatchdog() {
        MjpegFixture { false }.use { fixture ->
            fixture.start(firstFrameTimeoutMillis = 1_000L)
            assertTrue("A rejected display must eventually time out", fixture.failed.await(5, TimeUnit.SECONDS))
            fixture.awaitCallbacks()
            assertTrue("The session must recycle a rejected bitmap", checkNotNull(fixture.bitmap.get()).isRecycled)
            assertFalse(fixture.statuses.any { it is NanoKvmVideoStatus.Streaming })
            assertTrue(fixture.stalls.isEmpty())
            assertEquals(1, fixture.startupTimeouts.get())
            assertEquals(1, fixture.mjpegRequests.get())
            val error = fixture.statuses.filterIsInstance<NanoKvmVideoStatus.Error>().single()
            assertEquals(NanoKvmVideoFailureReason.FirstFrameTimeout, error.reason)
        }
    }

    @Test
    fun stopInsideDisplayCallbackCannotPublishStaleStreaming() {
        lateinit var fixture: MjpegFixture
        fixture = MjpegFixture {
            fixture.session.stop()
            true
        }
        fixture.use {
            fixture.start()
            assertTrue("Display callback did not stop the session", fixture.stopped.await(5, TimeUnit.SECONDS))
            fixture.awaitCallbacks()
            assertFalse(fixture.statuses.any { it is NanoKvmVideoStatus.Streaming })
            assertFalse("Accepted bitmap remains caller-owned after synchronous stop", checkNotNull(fixture.bitmap.get()).isRecycled)
        }
    }

    @Test
    fun autoFallsBackToRenderedMjpegAfterDirectH264Failure() {
        ImageReader.newInstance(1_920, 1_080, ImageFormat.PRIVATE, 2).use { target ->
            MjpegFixture { true }.use { fixture ->
                fixture.startAuto(target.surface)
                assertTrue("AUTO did not render its MJPEG fallback", fixture.streaming.await(10, TimeUnit.SECONDS))
                val fallback = fixture.statuses.filterIsInstance<NanoKvmVideoStatus.FallingBack>().single()
                assertEquals(NanoKvmVideoTransport.H264, fallback.from)
                assertEquals(NanoKvmVideoTransport.MJPEG, fallback.to)
                assertEquals(NanoKvmVideoFailureReason.HttpRejected(503), fallback.reason)
                assertEquals(1, fixture.h264Requests.get())
                val streaming = fixture.statuses.filterIsInstance<NanoKvmVideoStatus.Streaming>().single()
                assertEquals(NanoKvmVideoTransport.MJPEG, streaming.transport)
                assertTrue(fixture.statuses.indexOf(fallback) < fixture.statuses.indexOf(streaming))
                assertFalse(checkNotNull(fixture.bitmap.get()).isRecycled)
            }
        }
    }

    @Test
    fun expiredLoginStopsFallbackChain() {
        ImageReader.newInstance(1_920, 1_080, ImageFormat.PRIVATE, 2).use { target ->
            MjpegFixture(h264ResponseCode = 401) { true }.use { fixture ->
                fixture.startAuto(target.surface)
                assertTrue(fixture.failed.await(5, TimeUnit.SECONDS))
                val error = fixture.statuses.filterIsInstance<NanoKvmVideoStatus.Error>().single()
                assertEquals(NanoKvmVideoFailureReason.HttpRejected(401), error.reason)
                assertEquals(1, fixture.h264Requests.get())
                assertEquals(0, fixture.mjpegRequests.get())
                assertFalse(fixture.statuses.any { it is NanoKvmVideoStatus.FallingBack })
            }
        }
    }

    @Test
    fun retriesSilentDirectH264ThenRendersFromFreshMjpegRequest() {
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(64, 64) }
        val surface = Surface(texture)
        try {
            MjpegFixture(h264ResponseCode = null, silentMjpegRequests = 1) { bitmap ->
                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                true
            }.use { fixture ->
                fixture.startAuto(surface, firstFrameTimeoutMillis = 1_000L)
                assertTrue("Second MJPEG attempt did not render", fixture.streaming.await(10, TimeUnit.SECONDS))
                assertEquals(2, fixture.mjpegRequests.get())
                assertFalse(fixture.statuses.any { it is NanoKvmVideoStatus.Error })
                val fallback = fixture.statuses.filterIsInstance<NanoKvmVideoStatus.FallingBack>().single()
                assertEquals(NanoKvmVideoFailureReason.FirstFrameTimeout, fallback.reason)
                assertEquals(2, fixture.h264Requests.get())
                assertEquals(List(2) { NanoKvmVideoTransport.H264 }, fixture.stalls)
                assertEquals(1, fixture.startupTimeouts.get())
            }
        } finally {
            surface.release()
            texture.release()
        }
    }

    @Test
    fun reportsFirstFrameTimeoutAfterExactlyOneMjpegFallbackRetry() {
        ImageReader.newInstance(1_920, 1_080, ImageFormat.PRIVATE, 2).use { target ->
            MjpegFixture(silentMjpegRequests = 2) { true }.use { fixture ->
                fixture.startAuto(target.surface, firstFrameTimeoutMillis = 1_000L)
                assertTrue("Second MJPEG timeout did not stop video", fixture.failed.await(10, TimeUnit.SECONDS))
                assertEquals(2, fixture.mjpegRequests.get())
                assertFalse(fixture.statuses.any { it is NanoKvmVideoStatus.Streaming })
                val error = fixture.statuses.filterIsInstance<NanoKvmVideoStatus.Error>().single()
                assertEquals(NanoKvmVideoTransport.MJPEG, error.transport)
                assertEquals(NanoKvmVideoFailureReason.FirstFrameTimeout, error.reason)
                assertTrue(fixture.stalls.isEmpty())
                assertEquals(2, fixture.startupTimeouts.get())
            }
        }
    }

    @Test
    fun explicitMjpegRetriesOneSilentRequestWithoutReportingAPlaybackStall() {
        MjpegFixture(silentMjpegRequests = 1) { true }.use { fixture ->
            fixture.start(firstFrameTimeoutMillis = 1_000L)
            assertTrue(fixture.streaming.await(5, TimeUnit.SECONDS))
            assertEquals(2, fixture.mjpegRequests.get())
            assertEquals(1, fixture.startupTimeouts.get())
            assertTrue(fixture.stalls.isEmpty())
        }
    }

    private class MjpegFixture(
        private val h264ResponseCode: Int? = 503,
        private val silentMjpegRequests: Int = 0,
        onBitmap: (Bitmap) -> Boolean,
    ) : AutoCloseable {
        private val server = MockWebServer().apply { start() }
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        private val callbacks = Executors.newSingleThreadExecutor()
        val bitmap = AtomicReference<Bitmap?>()
        val statuses = CopyOnWriteArrayList<NanoKvmVideoStatus>()
        val stalls = CopyOnWriteArrayList<NanoKvmVideoTransport>()
        val h264Requests = AtomicInteger()
        val mjpegRequests = AtomicInteger()
        val startupTimeouts = AtomicInteger()
        val streaming = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val session = NanoKvmVideoSession(
            client = client,
            baseUrl = server.url("/"),
            token = "mjpeg-test-token",
            callbackExecutor = callbacks,
            listener = object : NanoKvmVideoListener {
                override fun onMjpegBitmapFrame(bitmap: Bitmap): Boolean {
                    this@MjpegFixture.bitmap.set(bitmap)
                    return onBitmap(bitmap)
                }

                override fun onStatusChanged(status: NanoKvmVideoStatus) {
                    statuses.add(status)
                    when (status) {
                        is NanoKvmVideoStatus.Streaming -> streaming.countDown()
                        is NanoKvmVideoStatus.Error -> failed.countDown()
                        NanoKvmVideoStatus.Stopped -> stopped.countDown()
                        else -> Unit
                    }
                }

                override fun onMjpegStartupTimedOut() {
                    startupTimeouts.incrementAndGet()
                }

                override fun onVideoStalled(transport: NanoKvmVideoTransport) {
                    stalls.add(transport)
                }
            },
        )

        fun start(firstFrameTimeoutMillis: Long = 5_000L) {
            prepareStream()
            session.startMjpeg(
                NanoKvmVideoConfig(
                    preference = NanoKvmVideoPreference.MJPEG,
                    mjpegFirstFrameTimeoutMillis = firstFrameTimeoutMillis,
                ),
            )
        }

        fun startAuto(surface: Surface, firstFrameTimeoutMillis: Long = 5_000L) {
            prepareStream()
            session.start(
                surface,
                NanoKvmVideoConfig(
                    h264FirstFrameTimeoutMillis = firstFrameTimeoutMillis,
                    mjpegFirstFrameTimeoutMillis = firstFrameTimeoutMillis,
                ),
            )
        }

        private fun prepareStream() {
            val jpeg = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { source ->
                try {
                    source.eraseColor(Color.BLUE)
                    ByteArrayOutputStream().use { output ->
                        check(source.compress(Bitmap.CompressFormat.JPEG, 90, output))
                        output.toByteArray()
                    }
                } finally {
                    source.recycle()
                }
            }
            val body = Buffer()
                .writeUtf8("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n")
                .write(jpeg)
                .writeUtf8("\r\n")
            val response = MockResponse()
                .setHeader("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                .setBody(body)
                // Keep the HTTP body pending after the JPEG so only the rendered-frame
                // watchdog or an explicit stop ends the stream, never fixture EOF.
                .setHeader("Content-Length", body.size + 1L)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/stream/mjpeg" -> if (mjpegRequests.incrementAndGet() <= silentMjpegRequests) {
                        MockResponse()
                            .setHeader("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                            .setHeader("Content-Length", 1)
                    } else {
                        response
                    }
                    "/api/stream/h264/direct" -> {
                        h264Requests.incrementAndGet()
                        if (h264ResponseCode == null) {
                            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                    webSocket.close(code, reason)
                                }
                            })
                        } else {
                            MockResponse().setResponseCode(h264ResponseCode)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        fun awaitCallbacks() {
            callbacks.submit {}.get(5, TimeUnit.SECONDS)
        }

        override fun close() {
            try {
                session.closeAndAwaitDecoderRelease().get(5, TimeUnit.SECONDS)
                awaitCallbacks()
            } finally {
                callbacks.shutdownNow()
                bitmap.get()?.let { if (!it.isRecycled) it.recycle() }
                client.dispatcher.cancelAll()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
                server.shutdown()
            }
        }
    }
}

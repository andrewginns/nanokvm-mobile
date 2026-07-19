package org.nanokvm.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PacedCommittedTextTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient
    private val inputs = mutableListOf<NanoKvmInputSocket>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
        )
    }

    @After
    fun tearDown() {
        inputs.forEach(NanoKvmInputSocket::close)
        client.close()
        server.shutdown()
    }

    @Test
    fun `unsupported code points reject all text before any frame is sent`() = runBlocking {
        val frames = LinkedBlockingQueue<ObservedFrame>()
        val input = openInput(frames)

        val result = input.sendPacedCommittedText("a😃b")

        assertEquals(
            PacedCommittedTextResult.Unsupported(
                listOf(UnsupportedCodePoint(utf16Index = 1, codePoint = 0x1F603)),
            ),
            result,
        )
        assertNull(frames.poll(150, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `paced text reports progress and restores held modifiers after every character`() =
        runBlocking {
            val frames = LinkedBlockingQueue<ObservedFrame>()
            val input = openInput(frames)
            val progress = mutableListOf<PacedCommittedTextProgress>()
            val startedNanos = System.nanoTime()

            val result = input.sendPacedCommittedText(
                text = "aB",
                heldModifiers = setOf(HidModifier.LEFT_CONTROL),
                pacing = CommittedTextPacing(intervalMillis = 40),
                onProgress = progress::add,
            )
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

            assertEquals(PacedCommittedTextResult.Completed(2), result)
            assertEquals(
                listOf(
                    PacedCommittedTextProgress(0, 2),
                    PacedCommittedTextProgress(1, 2),
                    PacedCommittedTextProgress(2, 2),
                ),
                progress,
            )
            assertTrue("Expected the configured inter-character delay", elapsedMillis >= 30)
            assertFrame(
                frames,
                HidKeyboardReport.create(
                    modifiers = setOf(HidModifier.LEFT_CONTROL),
                    keys = listOf(HidUsage.A),
                ).toWireFrame(),
            )
            val firstRelease = assertFrame(
                frames,
                HidKeyboardReport.create(setOf(HidModifier.LEFT_CONTROL)).toWireFrame(),
            )
            val secondPress = assertFrame(
                frames,
                HidKeyboardReport.create(
                    modifiers = setOf(HidModifier.LEFT_CONTROL, HidModifier.LEFT_SHIFT),
                    keys = listOf(HidUsage.B),
                ).toWireFrame(),
            )
            assertFrame(
                frames,
                HidKeyboardReport.create(setOf(HidModifier.LEFT_CONTROL)).toWireFrame(),
            )
            val observedGapMillis = TimeUnit.NANOSECONDS.toMillis(
                secondPress.receivedAtNanos - firstRelease.receivedAtNanos,
            )
            assertTrue("WebSocket frames were not paced", observedGapMillis >= 30)
        }

    @Test
    fun `coroutine cancellation after a pair prevents the next character`() = runBlocking {
        val frames = LinkedBlockingQueue<ObservedFrame>()
        val input = openInput(frames)
        val firstPairComplete = CompletableDeferred<Unit>()

        val operation = async {
            input.sendPacedCommittedText(
                text = "ab",
                pacing = CommittedTextPacing(intervalMillis = 250),
                onProgress = { progress ->
                    if (progress.sentKeystrokes == 1) firstPairComplete.complete(Unit)
                },
            )
        }
        withTimeout(5_000) { firstPairComplete.await() }
        operation.cancelAndJoin()
        delay(300)

        assertTrue(operation.isCancelled)
        assertFrame(
            frames,
            HidKeyboardReport.create(keys = listOf(HidUsage.A)).toWireFrame(),
        )
        assertFrame(frames, HidKeyboardReport.released().toWireFrame())
        assertNull(frames.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `connection loss after a pair returns partial progress without retrying`() = runBlocking {
        val frames = LinkedBlockingQueue<ObservedFrame>()
        val frameCount = intArrayOf(0)
        val input = openInput(frames) { webSocket, _ ->
            frameCount[0]++
            if (frameCount[0] == 2) webSocket.cancel()
        }

        val result = input.sendPacedCommittedText(
            text = "ab",
            pacing = CommittedTextPacing(intervalMillis = 250),
        )

        assertEquals(PacedCommittedTextResult.ConnectionLost(1), result)
        assertFrame(
            frames,
            HidKeyboardReport.create(keys = listOf(HidUsage.A)).toWireFrame(),
        )
        assertFrame(frames, HidKeyboardReport.released().toWireFrame())
        assertNull(frames.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `pacing interval is bounded`() {
        assertEquals(30L, CommittedTextPacing().intervalMillis)
        assertThrows(IllegalArgumentException::class.java) {
            CommittedTextPacing(intervalMillis = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommittedTextPacing(intervalMillis = 251)
        }
    }

    private suspend fun openInput(
        frames: LinkedBlockingQueue<ObservedFrame>,
        afterFrame: (WebSocket, ByteArray) -> Unit = { _, _ -> },
    ): NanoKvmInputSocket {
        val opened = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val frame = bytes.toByteArray()
                    frames.offer(ObservedFrame(frame, System.nanoTime()))
                    afterFrame(webSocket, frame)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }),
        )
        val input = client.newInputSocket(heartbeatIntervalMillis = 10_000).also(inputs::add)
        assertTrue(input.connect())
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) {
            input.state.first { it is InputConnectionState.Connected }
        }
        return input
    }

    private fun assertFrame(
        frames: LinkedBlockingQueue<ObservedFrame>,
        expected: ByteArray,
    ): ObservedFrame {
        val observed = requireNotNull(frames.poll(5, TimeUnit.SECONDS)) {
            "Timed out waiting for an input WebSocket frame"
        }
        assertArrayEquals(expected, observed.bytes)
        return observed
    }

    private data class ObservedFrame(
        val bytes: ByteArray,
        val receivedAtNanos: Long,
    )
}

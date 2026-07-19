package org.nanokvm.video

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NanoKvmWebRtcSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val sources = mutableListOf<NanoKvmWebRtcSource>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    }

    @After
    fun tearDown() {
        sources.forEach(NanoKvmWebRtcSource::close)
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `authenticated 2_4_3 negotiation is one offer recvonly peer and ordered candidates`() {
        val serverPeer = RecordingServerPeer(sendIceServers = true, expectedClientMessages = 2)
        server.enqueue(MockResponse().withWebSocketUpgrade(serverPeer))
        val peer = FakePeer(emitCandidateBeforeOffer = true)
        val factory = RecordingPeerFactory(peer)
        val listener = RecordingSourceListener()
        val source = newSource(factory, listener)

        source.start()
        assertTrue(serverPeer.opened.await(2, TimeUnit.SECONDS))
        assertTrue(factory.created.await(2, TimeUnit.SECONDS))
        assertTrue(peer.started.await(2, TimeUnit.SECONDS))
        assertTrue(serverPeer.clientMessages.await(2, TimeUnit.SECONDS))

        val handshake = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/stream/h264", handshake.path)
        assertEquals("nano-kvm-token=trusted-token", handshake.getHeader("Cookie"))
        assertEquals(1, factory.createCount.get())
        assertEquals(listOf("stun:stun.example:3478"), factory.servers.single().urls)

        val events = serverPeer.receivedText.map(::eventName)
        assertEquals(listOf("video-offer", "video-candidate"), events)
        val offerData = innerData(serverPeer.receivedText.first())
        assertEquals("offer", offerData.getValue("type").jsonPrimitive.content)
        assertTrue(offerData.getValue("sdp").jsonPrimitive.content.startsWith("v=0"))
        assertEquals(1, peer.startCount.get())

        requireNotNull(serverPeer.webSocket.get()).send(
            outer(
                "video-candidate",
                """{"candidate":"candidate:server 1 UDP 1 192.0.2.4 5000 typ host","sdpMid":"0","sdpMLineIndex":0}""",
            ),
        )
        requireNotNull(serverPeer.webSocket.get()).send(
            outer("video-answer", """{"type":"answer","sdp":"v=0\\r\\n"}"""),
        )
        assertTrue(peer.answerReceived.await(2, TimeUnit.SECONDS))
        assertEquals(0 to 1, source.pendingCandidateCountsForTest())
        assertTrue(peer.remoteCandidates.isEmpty())

        peer.completeAnswer()
        awaitCondition { peer.remoteCandidates.size == 1 }
        assertEquals(0 to 0, source.pendingCandidateCountsForTest())

        peer.emitRenderedFrame(timestampNs = 55L)
        assertTrue(listener.frameRendered.await(2, TimeUnit.SECONDS))
        assertEquals(55L, listener.lastTimestamp.get())
        assertNull(listener.failure.get())
    }

    @Test
    fun `teardown clears candidates and ignores all late peer callbacks`() {
        val serverPeer = RecordingServerPeer(sendIceServers = true)
        server.enqueue(MockResponse().withWebSocketUpgrade(serverPeer))
        val peer = FakePeer()
        val listener = RecordingSourceListener()
        val source = newSource(RecordingPeerFactory(peer), listener)

        source.start()
        assertTrue(peer.started.await(2, TimeUnit.SECONDS))
        peer.emitLocalCandidate()
        requireNotNull(serverPeer.webSocket.get()).send(
            outer(
                "video-candidate",
                """{"candidate":"candidate:queued 1 UDP 1 192.0.2.5 5000 typ host","sdpMid":"0","sdpMLineIndex":0}""",
            ),
        )
        awaitCondition { source.pendingCandidateCountsForTest().second == 1 }

        source.stop()
        assertEquals(0 to 0, source.pendingCandidateCountsForTest())
        assertEquals(1, peer.closeCount.get())
        val priorFrames = listener.frameCount.get()
        peer.emitRenderedFrame(99L)
        peer.emitLocalCandidate()
        Thread.sleep(50)

        assertEquals(priorFrames, listener.frameCount.get())
        assertEquals(0 to 0, source.pendingCandidateCountsForTest())
        assertThrows(IllegalStateException::class.java) { source.start() }
    }

    @Test
    fun `heartbeat is empty-data event and no negotiation message is replayed`() {
        val serverPeer = RecordingServerPeer(sendIceServers = false, expectedClientMessages = 2)
        server.enqueue(MockResponse().withWebSocketUpgrade(serverPeer))
        val source = newSource(
            factory = RecordingPeerFactory(FakePeer()),
            listener = RecordingSourceListener(),
            heartbeatIntervalMillis = 25L,
        )

        source.start()
        assertTrue(serverPeer.clientMessages.await(2, TimeUnit.SECONDS))
        val heartbeatMessages = serverPeer.receivedText.filter { eventName(it) == "heartbeat" }
        assertTrue(heartbeatMessages.size >= 2)
        heartbeatMessages.forEach {
            assertEquals("", Json.parseToJsonElement(it).jsonObject.getValue("data").jsonPrimitive.content)
        }
        assertFalse(serverPeer.receivedText.any { eventName(it) == "video-offer" })
    }

    @Test
    fun `duplicate ice-server bootstrap fails once and never creates a second peer`() {
        val serverPeer = RecordingServerPeer(sendIceServers = false)
        server.enqueue(MockResponse().withWebSocketUpgrade(serverPeer))
        val factory = RecordingPeerFactory(FakePeer())
        val listener = RecordingSourceListener()
        val source = newSource(factory, listener)

        source.start()
        assertTrue(serverPeer.opened.await(2, TimeUnit.SECONDS))
        val ice = outer("ice-servers", "[]")
        requireNotNull(serverPeer.webSocket.get()).send(ice)
        assertTrue(factory.created.await(2, TimeUnit.SECONDS))
        requireNotNull(serverPeer.webSocket.get()).send(ice)

        assertTrue(listener.failed.await(2, TimeUnit.SECONDS))
        assertEquals(1, factory.createCount.get())
        assertTrue(listener.failure.get() is NanoKvmWebRtcProtocolException)
    }

    private fun newSource(
        factory: NanoKvmWebRtcPeerFactory,
        listener: RecordingSourceListener,
        heartbeatIntervalMillis: Long = 60_000L,
    ): NanoKvmWebRtcSource = NanoKvmWebRtcSource(
        client = client,
        baseUrl = server.url("/"),
        token = "trusted-token",
        target = FakeRenderTarget,
        peerFactory = factory,
        listener = listener,
        heartbeatIntervalMillis = heartbeatIntervalMillis,
    ).also(sources::add)

    private fun eventName(message: String): String =
        Json.parseToJsonElement(message).jsonObject.getValue("event").jsonPrimitive.content

    private fun innerData(message: String) = Json.parseToJsonElement(
        Json.parseToJsonElement(message).jsonObject.getValue("data").jsonPrimitive.content,
    ).jsonObject

    private fun outer(event: String, data: String): String =
        """{"event":${JsonPrimitive(event)},"data":${JsonPrimitive(data)}}"""

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("Condition was not reached")
            Thread.sleep(5)
        }
    }

    private data object FakeRenderTarget : NanoKvmWebRtcRenderTarget

    private class RecordingPeerFactory(
        private val peer: FakePeer,
    ) : NanoKvmWebRtcPeerFactory {
        val created = CountDownLatch(1)
        val createCount = AtomicInteger()
        var servers: List<NanoKvmWebRtcIceServer> = emptyList()

        override fun create(
            iceServers: List<NanoKvmWebRtcIceServer>,
            target: NanoKvmWebRtcRenderTarget,
            listener: NanoKvmWebRtcPeerListener,
        ): NanoKvmWebRtcPeer {
            createCount.incrementAndGet()
            servers = iceServers
            peer.listener = listener
            created.countDown()
            return peer
        }
    }

    private class FakePeer(
        private val emitCandidateBeforeOffer: Boolean = false,
    ) : NanoKvmWebRtcPeer {
        lateinit var listener: NanoKvmWebRtcPeerListener
        val started = CountDownLatch(1)
        val answerReceived = CountDownLatch(1)
        val startCount = AtomicInteger()
        val closeCount = AtomicInteger()
        val remoteCandidates = ConcurrentLinkedQueue<NanoKvmWebRtcIceCandidate>()
        private val answerApplied = AtomicReference<(() -> Unit)?>(null)

        override fun start() {
            startCount.incrementAndGet()
            if (emitCandidateBeforeOffer) emitLocalCandidate()
            listener.onLocalOffer(
                NanoKvmWebRtcSessionDescription(
                    type = "offer",
                    sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n",
                ),
            )
            started.countDown()
        }

        override fun applyRemoteAnswer(
            description: NanoKvmWebRtcSessionDescription,
            onApplied: () -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            answerApplied.set(onApplied)
            answerReceived.countDown()
        }

        override fun addRemoteCandidate(candidate: NanoKvmWebRtcIceCandidate): Boolean {
            remoteCandidates.add(candidate)
            return true
        }

        fun completeAnswer() {
            requireNotNull(answerApplied.getAndSet(null)).invoke()
        }

        fun emitLocalCandidate() {
            listener.onLocalCandidate(
                NanoKvmWebRtcIceCandidate(
                    candidate = "candidate:client 1 UDP 1 192.0.2.3 4000 typ host",
                    sdpMid = "0",
                    sdpMLineIndex = 0,
                ),
            )
        }

        fun emitRenderedFrame(timestampNs: Long) {
            listener.onFrameRendered(timestampNs)
        }

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class RecordingSourceListener : NanoKvmWebRtcSourceListener {
        val frameRendered = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val frameCount = AtomicInteger()
        val lastTimestamp = AtomicReference<Long>()
        val failure = AtomicReference<Throwable>()

        override fun onFrameRendered(timestampNs: Long) {
            frameCount.incrementAndGet()
            lastTimestamp.set(timestampNs)
            frameRendered.countDown()
        }

        override fun onClosed(code: Int, reason: String) = Unit

        override fun onFailure(cause: Throwable, responseCode: Int?) {
            failure.compareAndSet(null, cause)
            failed.countDown()
        }
    }

    private class RecordingServerPeer(
        private val sendIceServers: Boolean,
        expectedClientMessages: Int = 0,
    ) : WebSocketListener() {
        val webSocket = AtomicReference<WebSocket>()
        val opened = CountDownLatch(1)
        val clientMessages = CountDownLatch(expectedClientMessages)
        val receivedText = ConcurrentLinkedQueue<String>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            this.webSocket.set(webSocket)
            opened.countDown()
            if (sendIceServers) {
                webSocket.send(
                    outerStatic(
                        "ice-servers",
                        """[{"urls":["stun:stun.example:3478"]}]""",
                    ),
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            receivedText.add(text)
            clientMessages.countDown()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        private companion object {
            fun outerStatic(event: String, data: String): String =
                """{"event":${JsonPrimitive(event)},"data":${JsonPrimitive(data)}}"""
        }
    }
}

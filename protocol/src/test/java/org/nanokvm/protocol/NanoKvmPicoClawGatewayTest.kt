package org.nanokvm.protocol

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NanoKvmPicoClawGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient
    private val gateways = mutableListOf<NanoKvmPicoClawGateway>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("gateway-cookie"),
        )
    }

    @After
    fun tearDown() {
        gateways.forEach(NanoKvmPicoClawGateway::close)
        client.close()
        server.shutdown()
    }

    @Test
    fun `gateway is generation-scoped authenticated typed and release restores manual HID`() =
        runBlocking {
            val peer = RecordingPicoPeer(expectedMessages = 2)
            server.enqueue(MockResponse().withWebSocketUpgrade(peer))
            server.enqueue(success("{\"released\":true,\"current_session\":\"\"}"))
            val gateway = newGateway(generation = 42)

            assertEquals(42L, gateway.scope.generation)
            assertEquals(SESSION_ID, gateway.scope.session.value)
            assertNull(gateway.sendMessage("must not queue"))
            assertFalse(gateway.cancelRun())
            assertTrue(gateway.connect())
            assertFalse(gateway.connect())
            assertTrue(peer.opened.await(2, TimeUnit.SECONDS))
            awaitState(gateway) { it is NanoKvmPicoClawGatewayState.Open }

            val handshake = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/api/picoclaw/gateway/ws?session_id=$SESSION_ID", handshake.path)
            assertEquals("nano-kvm-token=gateway-cookie", handshake.getHeader("Cookie"))
            assertTrue(gateway.manualHidLock.value is NanoKvmPicoClawManualHidLockState.Held)

            val receipt = requireNotNull(
                gateway.sendMessage(
                    " inspect the attached host ",
                    NanoKvmPicoClawMessageOptions(maxSteps = 7, maxRuntimeMillis = 45_000),
                ),
            )
            assertTrue(gateway.cancelRun())
            assertTrue(peer.messages.await(2, TimeUnit.SECONDS))
            val send = Json.parseToJsonElement(peer.textFrames.remove()).jsonObject
            assertEquals("message.send", send.getValue("type").jsonPrimitive.content)
            assertEquals(receipt.id, send.getValue("id").jsonPrimitive.content)
            assertEquals(SESSION_ID, send.getValue("session_id").jsonPrimitive.content)
            val payload = send.getValue("payload").jsonObject
            assertEquals("inspect the attached host", payload.getValue("content").jsonPrimitive.content)
            assertEquals(7, payload.getValue("max_steps").jsonPrimitive.int)
            assertEquals(45_000, payload.getValue("max_runtime_ms").jsonPrimitive.int)
            val cancel = Json.parseToJsonElement(peer.textFrames.remove()).jsonObject
            assertEquals("message.cancel", cancel.getValue("type").jsonPrimitive.content)
            assertEquals(SESSION_ID, cancel.getValue("session_id").jsonPrimitive.content)
            assertTrue(cancel.getValue("payload").jsonObject.isEmpty())

            val release = gateway.closeAndRelease()
            assertTrue(release.released)
            val releaseRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", releaseRequest.method)
            assertEquals("/api/picoclaw/runtime/session", releaseRequest.path)
            assertEquals(SESSION_ID, releaseRequest.getHeader("X-PicoClaw-Session-ID"))
            awaitLockState(gateway) {
                it is NanoKvmPicoClawManualHidLockState.Released
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { gateway.closeAndRelease() }
            }
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `inbound assistant observation action and errors are parsed without retaining raw JSON`() =
        runBlocking {
            val peer = RecordingPicoPeer()
            server.enqueue(MockResponse().withWebSocketUpgrade(peer))
            val gateway = newGateway()
            gateway.connect()
            assertTrue(peer.opened.await(2, TimeUnit.SECONDS))
            awaitState(gateway) { it is NanoKvmPicoClawGatewayState.Open }

            val typing = awaitMessage(gateway) {
                it is NanoKvmPicoClawInboundMessage.TypingStarted
            }
            requireNotNull(peer.webSocket.get()).send("{\"type\":\"typing.start\"}")
            assertEquals(NanoKvmPicoClawInboundMessage.TypingStarted, typing.await())

            val assistant = awaitMessage(gateway) {
                it is NanoKvmPicoClawInboundMessage.AssistantMessage
            }
            requireNotNull(peer.webSocket.get()).send(
                """{"type":"message.create","id":"answer-1","payload":{"content":[{"text":"done"}]}}""",
            )
            val assistantMessage = assistant.await() as NanoKvmPicoClawInboundMessage.AssistantMessage
            assertEquals(NanoKvmPicoClawAssistantMessageKind.CREATED, assistantMessage.kind)
            assertEquals("answer-1", assistantMessage.id)
            assertEquals("done", assistantMessage.text)

            val observation = awaitMessage(gateway) {
                it is NanoKvmPicoClawInboundMessage.Observation
            }
            requireNotNull(peer.webSocket.get()).send(
                """{"type":"observation","id":"obs-1","payload":{"text":"screen","data":{"image_base64":"YWJj"}}}""",
            )
            val observationMessage = observation.await() as NanoKvmPicoClawInboundMessage.Observation
            assertEquals("screen", observationMessage.text)
            assertEquals("YWJj", observationMessage.imageBase64)

            val action = awaitMessage(gateway) {
                it is NanoKvmPicoClawInboundMessage.ToolAction
            }
            requireNotNull(peer.webSocket.get()).send(
                """{"type":"tool.call","id":"tool-1","payload":{"action":"click","x":0.25,"y":0.75}}""",
            )
            val tool = action.await() as NanoKvmPicoClawInboundMessage.ToolAction
            assertEquals("click", tool.action)
            assertEquals(0.25, tool.x)
            assertEquals(0.75, tool.y)

            val error = awaitMessage(gateway) {
                it is NanoKvmPicoClawInboundMessage.Error
            }
            requireNotNull(peer.webSocket.get()).send(
                """{"type":"error","code":"MODEL_FAILED","message":"provider unavailable"}""",
            )
            val typedError = error.await() as NanoKvmPicoClawInboundMessage.Error
            assertEquals("MODEL_FAILED", typedError.code)
            assertEquals("provider unavailable", typedError.message)
        }

    @Test
    fun `gateway rejects queueing reconnect and invalid outbound bounds locally`() {
        val gateway = newGateway(generation = 9)
        assertNull(gateway.sendMessage("not queued"))
        assertFalse(gateway.cancelRun())
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPicoClawMessageOptions(maxSteps = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPicoClawMessageOptions(maxSteps = 51)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPicoClawMessageOptions(maxRuntimeMillis = 999)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPicoClawMessageOptions(maxRuntimeMillis = MAX_PICOCLAW_RUNTIME_MILLIS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            gateway.sendMessage("x".repeat(64 * 1_024 + 1))
        }
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        assertTrue(gateway.connect())
        awaitState(gateway) { it is NanoKvmPicoClawGatewayState.Failed }
        assertFalse(gateway.connect())
        assertNull(gateway.sendMessage("never replay"))
        Thread.sleep(150)
        assertEquals(1, server.requestCount)
        assertEquals(0, client.transport.pingIntervalMillis)
    }

    @Test
    fun `server binary and over-one-MiB frames close with protocol-specific codes`() {
        val binaryPeer = RecordingPicoPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(binaryPeer))
        val binaryGateway = newGateway(generation = 1)
        binaryGateway.connect()
        assertTrue(binaryPeer.opened.await(2, TimeUnit.SECONDS))
        awaitState(binaryGateway) { it is NanoKvmPicoClawGatewayState.Open }
        requireNotNull(binaryPeer.webSocket.get()).send(byteArrayOf(1, 2, 3).toByteString())
        assertTrue(binaryPeer.closing.await(2, TimeUnit.SECONDS))
        assertEquals(1003, binaryPeer.closeCode.get())

        val largePeer = RecordingPicoPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(largePeer))
        val largeGateway = newGateway(generation = 2)
        largeGateway.connect()
        assertTrue(largePeer.opened.await(2, TimeUnit.SECONDS))
        awaitState(largeGateway) { it is NanoKvmPicoClawGatewayState.Open }
        requireNotNull(largePeer.webSocket.get()).send(
            "x".repeat(MAX_PICOCLAW_GATEWAY_MESSAGE_BYTES + 1),
        )
        assertTrue(largePeer.closing.await(3, TimeUnit.SECONDS))
        assertEquals(1009, largePeer.closeCode.get())
    }

    @Test
    fun `official close codes map to typed cause and release lock`() = runBlocking {
        val peer = RecordingPicoPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        val gateway = newGateway()
        gateway.connect()
        assertTrue(peer.opened.await(2, TimeUnit.SECONDS))
        awaitState(gateway) { it is NanoKvmPicoClawGatewayState.Open }

        val closed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) {
                gateway.events.filterIsInstance<NanoKvmPicoClawGatewayEvent.Closed>().first()
            }
        }
        requireNotNull(peer.webSocket.get()).close(4004, "session lock lost")
        val close = closed.await().close

        assertEquals(4004, close.code)
        assertEquals(NanoKvmPicoClawCloseCause.SessionTakenOver, close.cause)
        assertTrue(gateway.manualHidLock.value is NanoKvmPicoClawManualHidLockState.Released)
        assertNull(gateway.sendMessage("must not replay"))
    }

    private fun newGateway(generation: Long = 1): NanoKvmPicoClawGateway =
        NanoKvmPicoClaw.enter(client, NanoKvmApplicationVersion(2, 4, 3)).newGateway(
            generation = generation,
            approval = NanoKvmPicoClawControlApproval
                .afterUserApprovedBroadDeviceAndHostControl(),
            session = NanoKvmPicoClawRuntimeSessionId.parse(SESSION_ID),
        ).also(gateways::add)

    private suspend fun awaitMessage(
        gateway: NanoKvmPicoClawGateway,
        predicate: (NanoKvmPicoClawInboundMessage) -> Boolean,
    ) = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async(
        start = CoroutineStart.UNDISPATCHED,
    ) {
        withTimeout(2_000) {
            gateway.events.filterIsInstance<NanoKvmPicoClawGatewayEvent.Message>()
                .first { predicate(it.message) }
                .message
        }
    }

    private fun awaitState(
        gateway: NanoKvmPicoClawGateway,
        predicate: (NanoKvmPicoClawGatewayState) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate(gateway.state.value) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Gateway state did not converge: ${gateway.state.value}", predicate(gateway.state.value))
    }

    private fun awaitLockState(
        gateway: NanoKvmPicoClawGateway,
        predicate: (NanoKvmPicoClawManualHidLockState) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate(gateway.manualHidLock.value) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(
            "Manual HID lock state did not converge: ${gateway.manualHidLock.value}",
            predicate(gateway.manualHidLock.value),
        )
    }

    private fun success(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"code\":0,\"msg\":\"success\",\"data\":$data}")

    private class RecordingPicoPeer(expectedMessages: Int = 0) : WebSocketListener() {
        val webSocket = AtomicReference<WebSocket?>()
        val opened = CountDownLatch(1)
        val messages = CountDownLatch(expectedMessages)
        val closing = CountDownLatch(1)
        val textFrames = ConcurrentLinkedQueue<String>()
        val closeCode = AtomicInteger(-1)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            this.webSocket.set(webSocket)
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            textFrames += text
            messages.countDown()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            messages.countDown()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode.set(code)
            closing.countDown()
            webSocket.close(code, reason)
        }
    }

    private companion object {
        const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}

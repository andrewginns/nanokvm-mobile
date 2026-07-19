package org.nanokvm.protocol

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class NanoKvmHidShortcutApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("hid-shortcut-token"),
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `saved shortcut list preserves bounded unknown keys as read only`() = runBlocking {
        server.enqueue(
            envelope(
                """{"shortcuts":[
                    {"id":"known-id","keys":[
                        {"code":"ControlLeft","label":"Ctrl"},
                        {"code":"KeyA","label":"A"}
                    ],"future":"ignored"},
                    {"id":"future-id","keys":[
                        {"code":"HyperFuture","label":"Future key"}
                    ]},
                    {"id":"future-length-id","keys":[
                        {"code":"KeyA","label":"A"},
                        {"code":"KeyB","label":"B"},
                        {"code":"KeyC","label":"C"},
                        {"code":"KeyD","label":"D"},
                        {"code":"KeyE","label":"E"},
                        {"code":"KeyF","label":"F"},
                        {"code":"KeyG","label":"G"}
                    ]}
                ],"future":true}""",
            ),
        )

        val catalog = client.api.savedHidShortcuts()

        assertEquals(3, catalog.shortcuts.size)
        val known = requireNotNull(catalog.findById("known-id"))
        assertTrue(known.isRunnable)
        assertEquals("ControlLeft", known.keys.first().knownCode?.wireValue)
        val future = requireNotNull(catalog.findById("future-id"))
        assertFalse(future.isRunnable)
        assertEquals("HyperFuture", future.keys.single().code)
        assertNull(future.keys.single().knownCode)
        val futureLength = requireNotNull(catalog.findById("future-length-id"))
        assertEquals(7, futureLength.keys.size)
        assertFalse(futureLength.isRunnable)
        takeAuthenticated("GET", "/api/hid/shortcuts")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `nil saved shortcut slice decodes as an empty catalog`() = runBlocking {
        server.enqueue(envelope("""{"shortcuts":null}"""))

        assertTrue(client.api.savedHidShortcuts().shortcuts.isEmpty())

        takeAuthenticated("GET", "/api/hid/shortcuts")
        Unit
    }

    @Test
    fun `record and add use the exact allowlisted one request body and consume prior snapshot`() =
        runBlocking {
            server.enqueue(singleShortcutEnvelope("old-id"))
            server.enqueue(successWithoutData())
            val previous = client.api.savedHidShortcuts()
            val previousShortcut = previous.shortcuts.single()
            val draft = NanoKvmHidShortcutDraft.record(
                listOf(
                    NanoKvmHidKeyCode.known("ControlLeft"),
                    NanoKvmHidKeyCode.known("AltLeft"),
                    NanoKvmHidKeyCode.known("Delete"),
                ),
            )

            client.api.addSavedHidShortcut(draft)

            takeAuthenticated("GET", "/api/hid/shortcuts")
            assertJsonBody(
                takeAuthenticated("POST", "/api/hid/shortcut"),
                """{"keys":[
                    {"code":"ControlLeft","label":"Ctrl"},
                    {"code":"AltLeft","label":"Alt"},
                    {"code":"Delete","label":"Del"}
                ]}""",
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.api.deleteSavedHidShortcut(previous, previousShortcut) }
            }
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `record rejects unknown duplicate empty and oversized key sets before transport`() {
        assertEquals(190, knownHidShortcutKeyCodeCount)
        assertEquals("MediaFastForward", NanoKvmHidKeyCode.known("MediaFastForward").wireValue)
        assertEquals("IntlHash", NanoKvmHidKeyCode.known("IntlHash").wireValue)
        assertEquals("F24", NanoKvmHidKeyCode.known("F24").wireValue)
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmHidKeyCode.known("FutureKey")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmHidShortcutDraft.record(emptyList())
        }
        val a = NanoKvmHidKeyCode.known("KeyA")
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmHidShortcutDraft.record(listOf(a, a))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmHidShortcutDraft.record(
                listOf("KeyA", "KeyB", "KeyC", "KeyD", "KeyE", "KeyF", "KeyG")
                    .map(NanoKvmHidKeyCode::known),
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ambiguous shortcut mutation loss is surfaced and never replayed`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val draft = NanoKvmHidShortcutDraft.record(
            listOf(NanoKvmHidKeyCode.known("KeyA")),
        )

        assertThrows(IOException::class.java) {
            runBlocking { client.api.addSavedHidShortcut(draft) }
        }

        Thread.sleep(150)
        assertEquals(1, server.requestCount)
        assertFalse(client.transport.retryOnConnectionFailure)
    }

    @Test
    fun `delete requires the latest exact handle and sends one JSON DELETE`() = runBlocking {
        server.enqueue(singleShortcutEnvelope("delete-id"))
        server.enqueue(successWithoutData())
        val catalog = client.api.savedHidShortcuts()
        val shortcut = catalog.shortcuts.single()

        client.api.deleteSavedHidShortcut(catalog, shortcut)

        takeAuthenticated("GET", "/api/hid/shortcuts")
        assertJsonBody(
            takeAuthenticated("DELETE", "/api/hid/shortcut"),
            """{"id":"delete-id"}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.deleteSavedHidShortcut(catalog, shortcut) }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `leader key reads disabled known and unknown while writes are known only`() = runBlocking {
        server.enqueue(envelope("""{"key":""}"""))
        server.enqueue(envelope("""{"key":"ShiftRight","future":true}"""))
        server.enqueue(envelope("""{"key":"FutureLeader"}"""))
        server.enqueue(successWithoutData())
        server.enqueue(successWithoutData())

        val disabled = client.api.leaderKey()
        val known = client.api.leaderKey()
        val future = client.api.leaderKey()
        client.api.setLeaderKey(NanoKvmHidKeyCode.known("ControlRight"))
        client.api.disableLeaderKey()

        assertFalse(disabled.enabled)
        assertEquals("", disabled.code)
        assertNull(disabled.knownCode)
        assertTrue(known.enabled)
        assertEquals("ShiftRight", known.knownCode?.wireValue)
        assertTrue(future.enabled)
        assertEquals("FutureLeader", future.code)
        assertNull(future.knownCode)

        repeat(3) { takeAuthenticated("GET", "/api/hid/shortcut/leader-key") }
        assertJsonBody(
            takeAuthenticated("POST", "/api/hid/shortcut/leader-key"),
            """{"key":"ControlRight"}""",
        )
        assertJsonBody(
            takeAuthenticated("POST", "/api/hid/shortcut/leader-key"),
            """{"key":""}""",
        )
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `shortcut and leader reads enforce bounded unambiguous state`() {
        val tooManyShortcuts = List(MAX_SAVED_HID_SHORTCUTS + 1) { index ->
            """{"id":"id-$index","keys":[{"code":"KeyA","label":"A"}]}"""
        }.joinToString(",")
        val tooManyKeys = List(MAX_SERVER_HID_SHORTCUT_KEYS + 1) {
            """{"code":"KeyA","label":"A"}"""
        }.joinToString(",")
        val invalidResponses = listOf(
            """{"shortcuts":[$tooManyShortcuts]}""",
            """{"shortcuts":[
                {"id":"same","keys":[{"code":"KeyA","label":"A"}]},
                {"id":"same","keys":[{"code":"KeyB","label":"B"}]}
            ]}""",
            """{"shortcuts":[{"id":"id","keys":[]}]}""",
            """{"shortcuts":[{"id":"id","keys":[$tooManyKeys]}]}""",
            """{"shortcuts":[{"id":"id","keys":[
                {"code":"${"x".repeat(MAX_HID_KEY_CODE_UTF8_BYTES + 1)}","label":"x"}
            ]}]}""",
            """{"shortcuts":[{"id":"id","keys":[
                {"code":"KeyA","label":"bad\nlabel"}
            ]}]}""",
        )
        invalidResponses.forEach { response ->
            server.enqueue(envelope(response))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { client.api.savedHidShortcuts() }
            }
        }
        server.enqueue(envelope("""{"key":"${"x".repeat(MAX_HID_KEY_CODE_UTF8_BYTES + 1)}"}"""))
        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.leaderKey() }
        }
        assertEquals(invalidResponses.size + 1, server.requestCount)
    }

    @Test
    fun `saved shortcut run matches incremental WebUI reports and rejects before send`() {
        val opened = CountDownLatch(1)
        val frames = LinkedBlockingQueue<ByteArray>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    frames.offer(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }),
        )
        val input = client.newInputSocket(heartbeatIntervalMillis = 10_000)
        try {
            assertTrue(input.connect())
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            runBlocking {
                withTimeout(5_000) { input.state.first { it is InputConnectionState.Connected } }
            }
            val handshake = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("/api/ws", handshake?.path)
            assertEquals("nano-kvm-token=hid-shortcut-token", handshake?.getHeader("Cookie"))

            val shortcut = savedShortcut("ControlLeft", "AltLeft", "Delete")
            assertEquals(
                NanoKvmHidShortcutRunResult.Completed(reportsSent = 4),
                input.sendSavedHidShortcut(shortcut),
            )
            assertFrame(frames, byteArrayOf(1, 0x01, 0, 0, 0, 0, 0, 0, 0))
            assertFrame(frames, byteArrayOf(1, 0x05, 0, 0, 0, 0, 0, 0, 0))
            assertFrame(frames, byteArrayOf(1, 0x05, 0, 0x4c, 0, 0, 0, 0, 0))
            assertFrame(frames, HidKeyboardReport.released().toWireFrame())

            val unknown = NanoKvmSavedHidShortcut(
                id = "future",
                keys = listOf(NanoKvmSavedHidShortcutKey("FutureKey", "Future", null)),
            )
            assertEquals(
                NanoKvmHidShortcutRunResult.Rejected(
                    NanoKvmHidShortcutRunRejectionReason.UNKNOWN_KEY_CODE,
                    unknownCodes = listOf("FutureKey"),
                ),
                input.sendSavedHidShortcut(unknown),
            )
            val oversized = savedShortcut(
                "KeyA", "KeyB", "KeyC", "KeyD", "KeyE", "KeyF", "KeyG",
            )
            assertEquals(
                NanoKvmHidShortcutRunResult.Rejected(
                    NanoKvmHidShortcutRunRejectionReason.TOO_MANY_KEYS,
                ),
                input.sendSavedHidShortcut(oversized),
            )
            assertNull(frames.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            input.close()
        }
    }

    private fun savedShortcut(vararg codes: String): NanoKvmSavedHidShortcut =
        NanoKvmSavedHidShortcut(
            id = "shortcut-id",
            keys = codes.map { code ->
                NanoKvmSavedHidShortcutKey(
                    code = code,
                    label = code,
                    knownCode = NanoKvmHidKeyCode.known(code),
                )
            },
        )

    private fun assertFrame(frames: LinkedBlockingQueue<ByteArray>, expected: ByteArray) {
        assertArrayEquals(expected, frames.poll(5, TimeUnit.SECONDS))
    }

    private fun takeAuthenticated(method: String, path: String): RecordedRequest {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals("nano-kvm-token=hid-shortcut-token", request.getHeader("Cookie"))
        return request
    }

    private fun assertJsonBody(request: RecordedRequest, expected: String) {
        val actualJson = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val expectedJson: JsonObject = Json.parseToJsonElement(expected).jsonObject
        assertEquals(expectedJson, actualJson)
    }

    private fun singleShortcutEnvelope(id: String): MockResponse = envelope(
        """{"shortcuts":[{"id":"$id","keys":[{"code":"KeyA","label":"A"}]}]}""",
    )

    private fun envelope(data: String): MockResponse = jsonResponse(
        """{"code":0,"msg":"success","data":$data}""",
    )

    private fun successWithoutData(): MockResponse = jsonResponse(
        """{"code":0,"msg":"success","data":null}""",
    )

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody(body)
}

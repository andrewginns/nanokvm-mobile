package org.nanokvm.protocol

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NanoKvmApiTest {
    private lateinit var server: MockWebServer
    private lateinit var tokenStore: InMemorySessionTokenStore
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = InMemorySessionTokenStore()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = tokenStore,
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `login encrypts password stores token and authenticates following requests by cookie`() = runBlocking {
        server.enqueue(jsonResponse("""{"code":0,"msg":"success","data":{"token":"jwt.value"}}"""))
        server.enqueue(
            jsonResponse(
                """{"code":0,"msg":"success","data":{"ips":[{"name":"eth0","addr":"192.0.2.250","version":"IPv4","type":"wired"}],"mdns":"nanokvm.local","image":"v1.4.2","application":"2.3.4","deviceKey":"device"}}""",
            ),
        )

        val token = client.api.login("admin", "correct horse battery staple".toCharArray())
        val info = client.api.vmInfo()

        assertEquals("jwt.value", token.token)
        assertEquals("jwt.value", tokenStore.read())
        assertEquals("2.3.4", info.application)
        assertEquals("192.0.2.250", info.ips.single().addr)

        val loginRequest = server.takeRequest()
        assertEquals("/api/auth/login", loginRequest.path)
        assertNull(loginRequest.getHeader("Cookie"))
        val loginJson = Json.parseToJsonElement(loginRequest.body.readUtf8()).jsonObject
        assertEquals("admin", loginJson.getValue("username").jsonPrimitive.content)
        val encryptedPassword = loginJson.getValue("password").jsonPrimitive.content
        assertFalse(encryptedPassword.contains("correct horse"))
        assertEquals(
            "correct horse battery staple",
            NanoKvmPasswordCipher.decryptForCompatibilityTest(encryptedPassword),
        )

        val infoRequest = server.takeRequest()
        assertEquals("nano-kvm-token=jwt.value", infoRequest.getHeader("Cookie"))
    }

    @Test
    fun `token length boundary is stored and an oversized login token cannot replace it`() {
        val boundaryToken = "a".repeat(MAX_SESSION_TOKEN_LENGTH)
        server.enqueue(
            jsonResponse("""{"code":0,"msg":"success","data":{"token":"$boundaryToken"}}"""),
        )

        val accepted = runBlocking {
            client.api.login("admin", "boundary password".toCharArray())
        }

        assertEquals(boundaryToken, accepted.token)
        assertEquals(boundaryToken, tokenStore.read())

        val oversizedToken = "b".repeat(MAX_SESSION_TOKEN_LENGTH + 1)
        server.enqueue(
            jsonResponse("""{"code":0,"msg":"success","data":{"token":"$oversizedToken"}}"""),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.login("admin", "oversized password".toCharArray()) }
        }
        assertEquals(boundaryToken, tokenStore.read())
    }

    @Test
    fun `oversized token from an external store is rejected before cookie use`() {
        tokenStore.write("x".repeat(MAX_SESSION_TOKEN_LENGTH + 1))

        assertThrows(IllegalArgumentException::class.java) {
            client.webSocketRequest("/api/ws")
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `nil VM interface slice decodes as an empty list`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":0,"msg":"success","data":{"ips":null,"application":"2.4.3"}}""",
            ),
        )

        val info = client.api.vmInfo()

        assertTrue(info.ips.isEmpty())
        assertEquals("2.4.3", info.application)
        assertEquals("/api/vm/info", server.takeRequest().path)
    }

    @Test
    fun `nonzero API envelope becomes typed exception`() {
        server.enqueue(jsonResponse("""{"code":-2,"msg":"invalid event","data":null}"""))

        val error = assertThrows(ApiResponseException::class.java) {
            runBlocking { client.api.gpioStatus() }
        }

        assertEquals(-2, error.code)
        assertEquals("", error.serverMessage)
        assertEquals("NanoKVM API error -2", error.message)
        assertFalse(error.toString().contains("invalid event"))
    }

    @Test
    fun `HTTP exception does not retain response body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("server-body-must-not-survive"),
        )

        val error = assertThrows(HttpResponseException::class.java) {
            runBlocking { client.api.gpioStatus() }
        }

        assertEquals(503, error.statusCode)
        assertFalse(error.toString().contains("server-body-must-not-survive"))
    }

    @Test
    fun `HTTP 401 clears local session`() {
        tokenStore.write("expired")
        server.enqueue(MockResponse().setResponseCode(401))

        assertThrows(AuthenticationExpiredException::class.java) {
            runBlocking { client.api.vmInfo() }
        }
        assertNull(tokenStore.read())
    }

    @Test
    fun `all redirect statuses are surfaced without contacting the foreign origin`() {
        val foreign = MockWebServer()
        foreign.start()
        try {
            tokenStore.write("host-scoped-token")
            listOf(301, 302, 303, 307, 308).forEach { statusCode ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(statusCode)
                        .addHeader("Location", foreign.url("/redirected/$statusCode")),
                )

                val error = assertThrows(HttpResponseException::class.java) {
                    runBlocking { client.api.vmInfo() }
                }

                assertEquals(statusCode, error.statusCode)
                assertEquals(
                    "nano-kvm-token=host-scoped-token",
                    server.takeRequest().getHeader("Cookie"),
                )
            }
            assertEquals(0, foreign.requestCount)
        } finally {
            foreign.shutdown()
        }
    }

    @Test
    fun `login credentials are never replayed through a redirect`() {
        val foreign = MockWebServer()
        foreign.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", foreign.url("/capture-login")),
            )

            val error = assertThrows(HttpResponseException::class.java) {
                runBlocking { client.api.login("admin", "do-not-forward".toCharArray()) }
            }

            assertEquals(307, error.statusCode)
            assertEquals("/api/auth/login", server.takeRequest().path)
            assertEquals(0, foreign.requestCount)
            assertNull(tokenStore.read())
        } finally {
            foreign.shutdown()
        }
    }

    @Test
    fun `screen GPIO HID and paste methods use exact wire fields`() = runBlocking {
        repeat(4) { server.enqueue(jsonResponse("""{"code":0,"msg":"success","data":null}""")) }

        client.api.updateScreen(ScreenSetting.FPS, 30)
        client.api.pressGpio(GpioAction.POWER, 800)
        client.api.resetHid()
        client.api.paste("Hello", PasteLanguage.ENGLISH)

        assertEquals(
            mapOf("type" to "fps", "value" to "30"),
            flatJson(server.takeRequest().body.readUtf8()),
        )
        assertEquals(
            mapOf("type" to "power", "duration" to "800"),
            flatJson(server.takeRequest().body.readUtf8()),
        )
        assertEquals("/api/hid/reset", server.takeRequest().path)
        assertEquals(
            mapOf("content" to "Hello", "langue" to "en"),
            flatJson(server.takeRequest().body.readUtf8()),
        )
    }

    @Test
    fun `MJPEG difference detection uses bounded exact one-shot routes`() = runBlocking {
        repeat(2) { server.enqueue(jsonResponse("""{"code":0,"msg":"success","data":null}""")) }

        client.api.setMjpegFrameDetectionEnabled(true)
        client.api.temporarilyPauseMjpegFrameDetection(10)

        val enabled = server.takeRequest()
        assertEquals("/api/stream/mjpeg/detect", enabled.path)
        assertEquals(mapOf("enabled" to "true"), flatJson(enabled.body.readUtf8()))
        val pause = server.takeRequest()
        assertEquals("/api/stream/mjpeg/detect/stop", pause.path)
        assertEquals(mapOf("duration" to "10"), flatJson(pause.body.readUtf8()))
    }

    @Test
    fun `invalid control values fail before network I-O`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.updateScreen(ScreenSetting.FPS, 120) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.pressGpio(GpioAction.RESET, 30_001) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.paste("x".repeat(1025)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.temporarilyPauseMjpegFrameDetection(31) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `REST response at one MiB boundary is accepted`() = runBlocking {
        server.enqueue(jsonResponse(validVmInfoResponseOfSize(MAX_REST_RESPONSE_BYTES)))

        assertEquals("2.3.4", client.api.vmInfo().application)
    }

    @Test
    fun `chunked REST response over one MiB is rejected`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setChunkedBody(
                    validVmInfoResponseOfSize(MAX_REST_RESPONSE_BYTES + 1),
                    8 * 1024,
                ),
        )

        val error = assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.vmInfo() }
        }

        assertTrue(error.message.orEmpty().contains("$MAX_REST_RESPONSE_BYTES-byte limit"))
    }

    @Test
    fun `declared oversized REST response is rejected before decoding`() {
        server.enqueue(jsonResponse(validVmInfoResponseOfSize(MAX_REST_RESPONSE_BYTES + 1)))

        val error = assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.vmInfo() }
        }

        assertTrue(error.message.orEmpty().contains("limit is $MAX_REST_RESPONSE_BYTES"))
    }

    @Test
    fun `truncated REST envelope is rejected as invalid response`() {
        server.enqueue(jsonResponse("""{"code":0,"msg":"ok","data":"""))

        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.vmInfo() }
        }
    }

    @Test
    fun `cancelling while awaiting REST headers still cancels the call`() {
        server.enqueue(
            jsonResponse("""{"code":0,"msg":"ok","data":{"application":"2.3.4"}}""")
                .setHeadersDelay(5, TimeUnit.SECONDS),
        )

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(100) { client.api.vmInfo() }
            }
        }
    }

    @Test
    fun `response bodies are read away from the caller dispatcher`() {
        server.enqueue(
            jsonResponse("""{"code":0,"msg":"ok","data":{"application":"2.3.4"}}"""),
        )
        val readThread = AtomicReference<String>()
        val supplied = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = requireNotNull(response.body)
                response.newBuilder()
                    .body(object : ResponseBody() {
                        override fun contentType() = body.contentType()

                        override fun contentLength(): Long = body.contentLength()

                        override fun source(): BufferedSource = object : ForwardingSource(
                            body.source(),
                        ) {
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                readThread.compareAndSet(null, Thread.currentThread().name)
                                return super.read(sink, byteCount)
                            }
                        }.buffer()
                    })
                    .build()
            }
            .build()
        val scoped = NanoKvmClient.using(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            httpClient = supplied,
        )
        val callerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "api-caller-dispatcher")
        }
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()

        try {
            val info = runBlocking {
                withContext(callerDispatcher) { scoped.api.vmInfo() }
            }

            assertEquals("2.3.4", info.application)
            assertFalse(readThread.get().orEmpty().contains("api-caller-dispatcher"))
        } finally {
            callerDispatcher.close()
            callerExecutor.shutdownNow()
            scoped.close()
            supplied.dispatcher.executorService.shutdown()
            supplied.connectionPool.evictAll()
        }
    }

    @Test
    fun `cancelling while reading a REST body cancels the OkHttp call`() {
        val canceled = CountDownLatch(1)
        val supplied = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    canceled.countDown()
                }
            })
            .build()
        val scoped = NanoKvmClient.using(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            httpClient = supplied,
        )
        server.enqueue(
            jsonResponse("""{"code":0,"msg":"ok","data":{"application":"2.3.4"}}""")
                .throttleBody(1, 1, TimeUnit.SECONDS),
        )

        try {
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(150) { scoped.api.vmInfo() }
                }
            }
            assertTrue(canceled.await(2, TimeUnit.SECONDS))
        } finally {
            scoped.close()
            supplied.dispatcher.executorService.shutdown()
            supplied.connectionPool.evictAll()
        }
    }

    @Test
    fun `input WebSocket handshake carries cookie and disconnect sends safe releases`() {
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
        tokenStore.write("websocket-token")
        val input = client.newInputSocket(heartbeatIntervalMillis = 10_000)

        try {
            assertTrue(input.connect())
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            runBlocking {
                withTimeout(5_000) {
                    input.state.first { it is InputConnectionState.Connected }
                }
            }
            val handshake = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("/api/ws", handshake?.path)
            assertEquals("nano-kvm-token=websocket-token", handshake?.getHeader("Cookie"))
            assertNull(handshake?.getHeader("Sec-WebSocket-Extensions"))

            assertTrue(input.sendKeyboard(HidKeyboardReport.create(keys = listOf(HidUsage.A))))
            assertArrayEquals(
                byteArrayOf(1, 0, 0, 0x04, 0, 0, 0, 0, 0),
                frames.poll(5, TimeUnit.SECONDS),
            )

            val committed = input.sendCommittedText(
                text = "c",
                heldModifiers = setOf(HidModifier.LEFT_CONTROL),
            )
            assertEquals(1, committed.sentKeystrokes)
            assertArrayEquals(
                HidKeyboardReport.create(
                    modifiers = setOf(HidModifier.LEFT_CONTROL),
                    keys = listOf(HidUsage.C),
                ).toWireFrame(),
                frames.poll(5, TimeUnit.SECONDS),
            )
            assertArrayEquals(
                HidKeyboardReport.create(setOf(HidModifier.LEFT_CONTROL)).toWireFrame(),
                frames.poll(5, TimeUnit.SECONDS),
            )

            assertTrue(
                input.sendKeyboardChord(
                    modifiers = setOf(HidModifier.LEFT_CONTROL, HidModifier.LEFT_ALT),
                    keys = listOf(HidUsage.DELETE_FORWARD),
                ),
            )
            assertArrayEquals(
                HidKeyboardReport.create(
                    modifiers = setOf(HidModifier.LEFT_CONTROL, HidModifier.LEFT_ALT),
                    keys = listOf(HidUsage.DELETE_FORWARD),
                ).toWireFrame(),
                frames.poll(5, TimeUnit.SECONDS),
            )
            assertArrayEquals(
                HidKeyboardReport.released().toWireFrame(),
                frames.poll(5, TimeUnit.SECONDS),
            )

            val shiftedKeyboard = HidKeyboardReport.create(
                setOf(HidModifier.LEFT_CONTROL, HidModifier.LEFT_SHIFT),
            )
            val restoredKeyboard = HidKeyboardReport.create(setOf(HidModifier.LEFT_CONTROL))
            val horizontalWheel = RelativeMouseReport.create(wheel = 2)
            assertTrue(
                input.sendShiftWheel(
                    shiftedKeyboard = shiftedKeyboard,
                    mouse = horizontalWheel,
                    restoredKeyboard = restoredKeyboard,
                ),
            )
            assertArrayEquals(
                shiftedKeyboard.toWireFrame(),
                frames.poll(5, TimeUnit.SECONDS),
            )
            assertArrayEquals(
                horizontalWheel.toWireFrame(),
                frames.poll(5, TimeUnit.SECONDS),
            )
            assertArrayEquals(
                restoredKeyboard.toWireFrame(),
                frames.poll(5, TimeUnit.SECONDS),
            )

            input.disconnect()
            assertArrayEquals(
                HidKeyboardReport.released().toWireFrame(),
                frames.poll(5, TimeUnit.SECONDS),
            )
            assertArrayEquals(
                RelativeMouseReport.create().toWireFrame(),
                frames.poll(5, TimeUnit.SECONDS),
            )
        } finally {
            input.close()
        }
    }

    @Test
    fun `oversized input message stops commands releases HID and uses bounded close policy`() {
        val opened = CountDownLatch(1)
        val closeReceived = CountDownLatch(1)
        val closeCode = AtomicInteger(-1)
        val peer = AtomicReference<WebSocket?>()
        val clientFrames = LinkedBlockingQueue<ByteArray>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    peer.set(webSocket)
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    clientFrames.offer(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closeCode.set(code)
                    closeReceived.countDown()
                    webSocket.close(code, reason)
                }

            }),
        )
        tokenStore.write("websocket-token")
        val input = client.newInputSocket(heartbeatIntervalMillis = 10_000)

        try {
            assertEquals(2_000, client.transport.webSocketCloseTimeout)
            assertTrue(input.connect())
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            runBlocking {
                withTimeout(2_000) { input.state.first { it is InputConnectionState.Connected } }
            }

            assertTrue(
                requireNotNull(peer.get()).send(
                    ByteArray(MAX_INPUT_SERVER_MESSAGE_BYTES + 1).toByteString(),
                ),
            )
            assertTrue(closeReceived.await(2, TimeUnit.SECONDS))
            assertFalse(input.sendKeyboard(HidKeyboardReport.create(keys = listOf(HidUsage.A))))
            assertArrayEquals(
                HidKeyboardReport.released().toWireFrame(),
                clientFrames.poll(2, TimeUnit.SECONDS),
            )
            assertArrayEquals(
                RelativeMouseReport.create().toWireFrame(),
                clientFrames.poll(2, TimeUnit.SECONDS),
            )
            assertEquals(1009, closeCode.get())
            runBlocking { withTimeout(2_000) { input.state.first { it is InputConnectionState.Disconnected } } }
        } finally {
            input.close()
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun validVmInfoResponseOfSize(size: Int): String {
        val envelope = """{"code":0,"msg":"","data":{"application":"2.3.4"}}"""
        require(size >= envelope.length)
        return envelope + " ".repeat(size - envelope.length)
    }

    private fun flatJson(value: String): Map<String, String> =
        Json.parseToJsonElement(value).jsonObject.mapValues { (_, element) ->
            element.jsonPrimitive.content
        }
}

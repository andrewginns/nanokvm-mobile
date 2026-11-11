package org.nanokvm.protocol

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class NanoKvmOsCompatibilityTest {
    @Test fun osDiscoveryUsesContractInsteadOfSipeedVersionFloors() = runBlocking {
        withClient { server, client ->
            enqueueOs(server)
            val caps = client.api.probeCapabilities().capabilities
            assertEquals(NanoKvmFirmware.NANOKVM_OS, caps.firmware)
            assertNull(caps.applicationVersion)
            val os = checkNotNull(caps.nanoKvmOs)
            assertTrue(os.canConfigureVideo)
            assertEquals(listOf(0, 600, 720, 1080), os.video.supportedHeights)
            assertTrue(caps[NanoKvmCapability.DIRECT_H264] is NanoKvmCapabilitySupport.Supported)
            assertFalse(caps[NanoKvmCapability.OFFLINE_UPDATE] is NanoKvmCapabilitySupport.Supported)
            assertEquals(5, server.requestCount)
            assertTrue((1..5).map { server.takeRequest().method }.all { it == "GET" })
        }
    }

    @Test fun labelsAndMalformedContractsNeverGrantCompatibility() = runBlocking {
        for (response in listOf(MockResponse().setResponseCode(404), envelope("{}"))) {
            withClient { server, client ->
                enqueueBase(server, "v3.0-a1")
                server.enqueue(response)
                assertEquals(NanoKvmFirmware.UNKNOWN, client.api.probeCapabilities().capabilities.firmware)
                assertEquals(4, server.requestCount)
            }
        }
        withClient { server, client ->
            enqueueBase(server, "2.4.3")
            assertEquals(NanoKvmFirmware.SIPEED, client.api.probeCapabilities().capabilities.firmware)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun nativeLoginOptsInOnceAndOnlyOnLogin() = runBlocking {
        withClient { server, client ->
            server.enqueue(envelope("""{"token":"test-token"}"""))
            client.api.login("admin", "test-password".toCharArray())
            val login = server.takeRequest()
            assertEquals("/api/auth/login", login.path)
            assertEquals("true", login.getHeader("X-NanoKVM-Return-Token"))
            server.enqueue(envelope("""{"application":"2.4.3"}"""))
            client.api.vmInfo()
            assertNull(server.takeRequest().getHeader("X-NanoKVM-Return-Token"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun osVideoUsesOneBatchAndBlocksLegacyMutations() = runBlocking {
        withClient { server, client ->
            enqueueOs(server)
            client.api.probeCapabilities()
            repeat(5) { server.takeRequest() }
            server.enqueue(envelope(VIDEO))
            server.enqueue(envelope("{}"))
            client.api.applyOsVideoSettings(NanoKvmOsVideoSettings(720, 30, 3000, 80))
            assertEquals("/api/vm/video/capabilities", server.takeRequest().path)
            val write = server.takeRequest()
            assertEquals("/api/vm/video", write.path)
            assertEquals("POST", write.method)
            assertEquals(
                Json.parseToJsonElement("""{"height":720,"fps":30,"bitRate":3000,"quality":80}"""),
                Json.parseToJsonElement(write.body.readUtf8()),
            )
            val requests = server.requestCount
            rejects { client.api.startOnlineUpdate() }
            rejects { client.api.updateScreen(ScreenSetting.GOP, 30) }
            rejects { client.api.resetHid() }
            assertEquals(requests, server.requestCount)
        }
    }

    @Test fun missingOrNonAdminRoleDoesNotAllowConfiguration() = runBlocking {
        for (account in listOf(
            """{"role":"user","mustChangePassword":false}""",
            """{"username":"admin"}""",
            """{"role":"admin","mustChangePassword":true}""",
        )) {
            withClient { server, client ->
                enqueueOs(server, account)
                assertFalse(client.api.probeCapabilities().capabilities.nanoKvmOs!!.canConfigureVideo)
                rejects { client.api.applyOsVideoSettings(NanoKvmOsVideoSettings(720, 30, 3000, 80)) }
                assertEquals(5, server.requestCount)
            }
        }
    }

    @Test fun ownershipGatesHidAndScopesHttpLeaseUntilRevocation() = runBlocking {
        withClient { server, client ->
            client.api.firmware = NanoKvmFirmware.NANOKVM_OS
            val peers = LinkedBlockingQueue<WebSocket>()
            val frames = LinkedBlockingQueue<ByteString>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { peers.put(webSocket) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames.put(bytes) }
            }))
            client.newInputSocket().use { input ->
                input.connect()
                withTimeout(3000) { input.state.first { it == InputConnectionState.Connected } }
                val peer = checkNotNull(peers.poll(3, TimeUnit.SECONDS))
                server.takeRequest()
                assertEquals(NanoKvmInputControl.WAITING, input.control.value)
                assertFalse(input.sendKeyboard(HidKeyboardReport.create(keys = listOf(HidUsage.A))))
                peer.send(controlEvent(false))
                withTimeout(3000) { input.control.first { it == NanoKvmInputControl.VIEW_ONLY } }
                assertFalse(input.sendKeyboardChord(emptySet(), listOf(HidUsage.A)))
                assertTrue(input.requestControl())
                assertEquals(listOf<Byte>(3, 1), frames.poll(3, TimeUnit.SECONDS)!!.toByteArray().toList())
                val lease = "a".repeat(32)
                peer.send(controlEvent(true, lease))
                withTimeout(3000) { input.control.first { it == NanoKvmInputControl.CONTROLLING } }
                assertTrue(input.sendKeyboard(HidKeyboardReport.released()))
                assertNotNull(frames.poll(3, TimeUnit.SECONDS))
                server.enqueue(envelope("{}"))
                client.api.pressGpio(GpioAction.POWER)
                assertEquals(lease, server.takeRequest().getHeader("X-NanoKVM-Input-Lease"))
                peer.send(controlEvent(false))
                withTimeout(3000) { input.control.first { it == NanoKvmInputControl.VIEW_ONLY } }
                assertFalse(input.sendKeyboard(HidKeyboardReport.released()))
                rejects { client.api.pressGpio(GpioAction.POWER) }
                assertNull(frames.poll(100, TimeUnit.MILLISECONDS))
                peer.close(1000, "done")
            }
        }
    }

    @Test fun replacementSocketCannotInheritOrLoseLeaseFromStaleCallbacks() {
        val store = NanoKvmInputLease()
        val old = Any()
        val replacement = Any()
        store.begin(old)
        store.update(old, true, "a".repeat(32))
        assertTrue(store.allows(old))
        store.begin(replacement)
        assertNull(store.read())
        store.update(old, true, "a".repeat(32))
        assertNull(store.read())
        store.update(replacement, true, "b".repeat(32))
        store.clear(old)
        assertTrue(store.allows(replacement))
        store.receive(replacement, controlEvent(true, "invalid-lease"))
        assertNull(store.read())
        assertEquals(NanoKvmInputControl.VIEW_ONLY, store.control.value)
        store.reset()
        assertFalse(store.allows(replacement))
    }

    @Test fun runtimeVideoLimitsExcludeUnavailableResolutionsAndFrameRates() {
        val caps = FIXTURE_JSON.decodeFromString<NanoKvmOsVideoCapabilities>(VIDEO).validated()
        val settings = NanoKvmOsVideoSettings(720, 60, 3000, 80)
        assertTrue(caps.accepts(settings))
        assertFalse(caps.copy(input = caps.input.copy(maxFps = 30)).accepts(settings))
        assertFalse(caps.copy(stream = caps.stream.copy(
            limits = caps.stream.limits.map { it.copy(available = false) },
        )).accepts(settings))
    }

    private suspend fun withClient(block: suspend (MockWebServer, NanoKvmClient) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            NanoKvmClient.create(
                NanoKvmEndpoint.parse(server.url("/").toString()),
                tokenStore = InMemorySessionTokenStore("fixture-token"),
            ).use { client -> block(server, client) }
        }
    }

    private suspend fun rejects(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected local contract rejection before dispatch")
        } catch (_: IllegalStateException) { }
    }

    private fun enqueueBase(server: MockWebServer, version: String) {
        server.enqueue(envelope(buildJsonObject { put("application", version) }.toString()))
        server.enqueue(envelope("""{"version":"NanoKVM PCIe"}"""))
        server.enqueue(envelope("""{"pwr":true,"hdd":false}"""))
    }

    private fun enqueueOs(
        server: MockWebServer,
        account: String = """{"role":"admin","mustChangePassword":false}""",
    ) {
        enqueueBase(server, "v3.0-a1")
        server.enqueue(envelope(VIDEO))
        server.enqueue(envelope(account))
    }

    private fun envelope(data: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(buildJsonObject {
            put("code", 0); put("msg", "success"); put("data", Json.parseToJsonElement(data))
        }.toString())

    private fun controlEvent(enabled: Boolean, lease: String? = null): String {
        val payload = buildJsonObject {
            put("enabled", enabled)
            if (lease != null) put("lease", lease)
        }
        return buildJsonObject { put("type", "control"); put("data", payload.toString()) }.toString()
    }

    companion object {
        private val FIXTURE_JSON = Json { ignoreUnknownKeys = true }
        private val VIDEO = """
            {
              "input":{"width":1920,"height":1080,"fps":60,"maxFps":120},
              "stream":{
                "limits":[
                  {"height":0,"width":0,"available":true},
                  {"height":600,"width":800,"available":true},
                  {"height":720,"width":1280,"available":true},
                  {"height":1080,"width":1920,"available":true},
                  {"height":2160,"width":3840,"available":false}
                ],
                "minFps":10,"maxFps":120,
                "mjpeg":{"available":true,"maxSide":2560}
              },
              "transports":{"direct":["h264","h265"],"webrtc":["h264","h265"],"mjpeg":["mjpeg"]}
            }
        """.trimIndent()
    }
}

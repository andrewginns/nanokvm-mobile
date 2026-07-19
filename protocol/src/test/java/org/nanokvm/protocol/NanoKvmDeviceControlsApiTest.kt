package org.nanokvm.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NanoKvmDeviceControlsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("device-controls-token"),
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `HDMI state enable disable and reset use explicit 2_4_3 routes`() = runBlocking {
        server.enqueue(envelope("""{"enabled":true,"future":"ignored"}"""))
        repeat(3) { server.enqueue(successWithoutData()) }

        assertEquals(NanoKvmHdmiState(enabled = true), client.api.hdmiState())
        client.api.setHdmiEnabled(true)
        client.api.setHdmiEnabled(false)
        client.api.resetHdmi()

        takeAuthenticated("GET", "/api/vm/hdmi")
        assertEmptyPost(takeAuthenticated("POST", "/api/vm/hdmi/enable"))
        assertEmptyPost(takeAuthenticated("POST", "/api/vm/hdmi/disable"))
        assertEmptyPost(takeAuthenticated("POST", "/api/vm/hdmi/reset"))
    }

    @Test
    fun `mouse jiggler preserves bounded future modes and writes only known exact values`() =
        runBlocking {
            server.enqueue(envelope("""{"enabled":true,"mode":"absolute"}"""))
            server.enqueue(envelope("""{"enabled":true,"mode":"future-mode"}"""))
            repeat(3) { server.enqueue(successWithoutData()) }

            val current = client.api.mouseJigglerState()
            val future = client.api.mouseJigglerState()
            assertEquals(NanoKvmMouseJigglerState(true, NanoKvmMouseJigglerMode.Absolute), current)
            assertEquals("future-mode", future.mode.wireValue)
            assertTrue(future.mode is NanoKvmMouseJigglerMode.Other)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.api.enableMouseJiggler(future.mode) }
            }
            client.api.enableMouseJiggler(NanoKvmMouseJigglerMode.Relative)
            client.api.enableMouseJiggler(NanoKvmMouseJigglerMode.Absolute)
            client.api.disableMouseJiggler()

            takeAuthenticated("GET", "/api/vm/mouse-jiggler")
            takeAuthenticated("GET", "/api/vm/mouse-jiggler")
            assertJsonBody(
                takeAuthenticated("POST", "/api/vm/mouse-jiggler/"),
                """{"enabled":true,"mode":"relative"}""",
            )
            assertJsonBody(
                takeAuthenticated("POST", "/api/vm/mouse-jiggler/"),
                """{"enabled":true,"mode":"absolute"}""",
            )
            assertJsonBody(
                takeAuthenticated("POST", "/api/vm/mouse-jiggler/"),
                """{"enabled":false,"mode":"relative"}""",
            )
            assertEquals(5, server.requestCount)
        }

    @Test
    fun `mouse jiggler rejects blank control and oversized modes`() {
        val invalidModes =
            listOf(
                "",
                "bad\\nmode",
                "x".repeat(MAX_MOUSE_JIGGLER_MODE_UTF8_BYTES + 1),
            )
        invalidModes.forEach { mode ->
            server.enqueue(envelope("""{"enabled":true,"mode":"$mode"}"""))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { client.api.mouseJigglerState() }
            }
        }
        assertEquals(invalidModes.size, server.requestCount)
    }

    @Test
    fun `memory limit reads preserve bounded non-WebUI values and writes exact preset states`() =
        runBlocking {
            server.enqueue(envelope("""{"enabled":true,"limit":75}"""))
            server.enqueue(envelope("""{"enabled":true,"limit":96}"""))
            repeat(2) { server.enqueue(successWithoutData()) }

            val known = client.api.memoryLimitState()
            val future = client.api.memoryLimitState()
            assertEquals(75L, known.limitMegabytes)
            assertSame(NanoKvmMemoryLimitPreset.TAILSCALE_RECOMMENDED, known.preset)
            assertEquals(96L, future.limitMegabytes)
            assertNull(future.preset)

            client.api.setMemoryLimit(NanoKvmMemoryLimitPreset.TAILSCALE_RECOMMENDED)
            client.api.disableMemoryLimit()

            takeAuthenticated("GET", "/api/vm/memory/limit")
            takeAuthenticated("GET", "/api/vm/memory/limit")
            assertJsonBody(
                takeAuthenticated("POST", "/api/vm/memory/limit"),
                """{"enabled":true,"limit":75}""",
            )
            assertJsonBody(
                takeAuthenticated("POST", "/api/vm/memory/limit"),
                """{"enabled":false,"limit":0}""",
            )
        }

    @Test
    fun `memory and swap reads reject negative and implausibly large sizes`() {
        listOf(-1L, MAX_REPORTED_DEVICE_MEMORY_MEGABYTES + 1L).forEach { size ->
            server.enqueue(envelope("""{"enabled":true,"limit":$size}"""))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { client.api.memoryLimitState() }
            }
            server.enqueue(envelope("""{"size":$size}"""))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { client.api.swapState() }
            }
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `swap read keeps bounded future size and setter uses the exact WebUI allowlist`() =
        runBlocking {
            server.enqueue(envelope("""{"size":384,"future":true}"""))
            NanoKvmSwapSizePreset.entries.forEach { _ -> server.enqueue(successWithoutData()) }

            val state = client.api.swapState()
            assertEquals(384L, state.sizeMegabytes)
            assertNull(state.preset)

            for (preset in NanoKvmSwapSizePreset.entries) {
                client.api.setSwapSize(preset)
            }

            takeAuthenticated("GET", "/api/vm/swap")
            NanoKvmSwapSizePreset.entries.forEach { preset ->
                assertJsonBody(
                    takeAuthenticated("POST", "/api/vm/swap"),
                    """{"size":${preset.megabytes}}""",
                )
            }
        }

    @Test
    fun `TLS surface can only enable and sends exact true request`() = runBlocking {
        server.enqueue(successWithoutData())

        client.api.enableApplianceTls()

        assertJsonBody(
            takeAuthenticated("POST", "/api/vm/tls"),
            """{"enabled":true}""",
        )
        assertFalse(
            NanoKvmApi::class.java.methods.any {
                it.name.contains("disable", ignoreCase = true) &&
                    it.name.contains("tls", ignoreCase = true)
            },
        )
    }

    @Test
    fun `virtual-device read exposes media but mutation enum remains network and disk only`() =
        runBlocking {
            server.enqueue(envelope("""{"network":true,"media":true,"disk":false}"""))

            val state = client.api.virtualDevices()

            assertTrue(state.isEnabled(NanoKvmVirtualDeviceComponent.NETWORK))
            assertTrue(state.isEnabled(NanoKvmVirtualDeviceComponent.MEDIA))
            assertFalse(state.isEnabled(NanoKvmVirtualDeviceComponent.DISK))
            assertEquals(
                setOf("disk", "network"),
                NanoKvmVirtualDevice.entries.map(NanoKvmVirtualDevice::wireName).toSet(),
            )
            takeAuthenticated("GET", "/api/vm/device/virtual")
            assertEquals(1, server.requestCount)
        }

    private fun takeAuthenticated(method: String, path: String): RecordedRequest {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals(
            "nano-kvm-token=device-controls-token",
            request.getHeader("Cookie"),
        )
        return request
    }

    private fun assertEmptyPost(request: RecordedRequest) {
        assertEquals(0L, request.bodySize)
    }

    private fun assertJsonBody(request: RecordedRequest, expected: String) {
        val actualJson = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val expectedJson: JsonObject = Json.parseToJsonElement(expected).jsonObject
        assertEquals(expectedJson, actualJson)
    }

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

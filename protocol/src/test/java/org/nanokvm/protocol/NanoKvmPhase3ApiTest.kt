package org.nanokvm.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NanoKvmPhase3ApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("phase-three-token"),
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `image discovery validates paths and decodes mounted and CD-ROM state`() = runBlocking {
        server.enqueue(imageListResponse(listOf("/data/linux.iso", "/data/nested/disk.img")))
        server.enqueue(envelope("""{"file":"/data/linux.iso"}"""))
        server.enqueue(envelope("""{"cdrom":1}"""))

        val catalog = client.api.listImages()
        val mounted = client.api.mountedImage()
        val cdrom = client.api.cdRomState()

        assertEquals(listOf("linux.iso", "disk.img"), catalog.images.map { it.fileName })
        assertEquals("/data/linux.iso", mounted?.path)
        assertTrue(cdrom.enabled)
        takeAuthenticated("GET", "/api/storage/image")
        takeAuthenticated("GET", "/api/storage/image/mounted")
        takeAuthenticated("GET", "/api/storage/cdrom")
        Unit
    }

    @Test
    fun `nil image and Wake-on-LAN slices decode as empty collections`() = runBlocking {
        server.enqueue(envelope("""{"files":null}"""))
        server.enqueue(envelope("""{"macs":null}"""))

        assertTrue(client.api.listImages().images.isEmpty())
        assertTrue(client.api.wakeOnLanHistory().isEmpty())

        takeAuthenticated("GET", "/api/storage/image")
        takeAuthenticated("GET", "/api/network/wol/mac")
        Unit
    }

    @Test
    fun `mount restore and delete use stable 2_4_3 golden requests`() = runBlocking {
        server.enqueue(imageListResponse(listOf("/data/installer.iso")))
        repeat(3) { server.enqueue(successWithoutData()) }
        val catalog = client.api.listImages()
        val image = catalog.images.single()
        takeAuthenticated("GET", "/api/storage/image")

        client.api.mountImage(catalog, image, NanoKvmImageMountMode.CD_ROM)
        client.api.restorePhysicalMedia()
        client.api.deleteImage(catalog, image)

        assertEquals(
            mapOf("file" to "/data/installer.iso", "cdrom" to true),
            flatJson(takeAuthenticated("POST", "/api/storage/image/mount")),
        )
        assertEquals(
            mapOf("file" to "", "cdrom" to false),
            flatJson(takeAuthenticated("POST", "/api/storage/image/mount")),
        )
        assertEquals(
            mapOf("file" to "/data/installer.iso"),
            flatJson(takeAuthenticated("POST", "/api/storage/image/delete")),
        )
    }

    @Test
    fun `mount and delete reject forged or cross-snapshot handles before network IO`() {
        server.enqueue(imageListResponse(listOf("/data/same.iso")))
        server.enqueue(imageListResponse(listOf("/data/same.iso")))
        val catalogs = runBlocking { client.api.listImages() to client.api.listImages() }
        val firstHandle = catalogs.first.images.single()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.mountImage(catalogs.second, firstHandle) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.deleteImage(
                    catalogs.first,
                    NanoKvmImage("/data/same.iso"),
                )
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `image list rejects traversal out-of-root extension duplicates and excess entries`() {
        val invalidLists = listOf(
            listOf("/data/../secret.iso"),
            listOf("/dataevil/disk.iso"),
            listOf("/data/readme.txt"),
            listOf("/data/folder\\disk.iso"),
            listOf("/data/${"x".repeat(MAX_IMAGE_PATH_UTF8_BYTES)}.iso"),
            listOf("/data/a.iso", "/data/a.iso"),
            List(MAX_IMAGE_COUNT + 1) { "/data/$it.iso" },
        )

        invalidLists.forEach { files ->
            server.enqueue(imageListResponse(files))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { client.api.listImages() }
            }
        }
        assertEquals(invalidLists.size, server.requestCount)
    }

    @Test
    fun `empty mounted path is physical media and invalid CD-ROM integer is rejected`() = runBlocking {
        server.enqueue(envelope("""{"file":""}"""))
        server.enqueue(envelope("""{"cdrom":2}"""))

        assertNull(client.api.mountedImage())
        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.cdRomState() }
        }
        Unit
    }

    @Test
    fun `HID mode preserves bounded unknown values for forward compatibility`() = runBlocking {
        server.enqueue(envelope("""{"mode":"normal"}"""))
        server.enqueue(envelope("""{"mode":"future-mode"}"""))
        server.enqueue(envelope("""{"mode":"${"x".repeat(MAX_HID_MODE_UTF8_BYTES + 1)}"}"""))

        assertEquals(NanoKvmHidMode.Normal, client.api.hidMode())
        assertEquals(NanoKvmHidMode.Other("future-mode"), client.api.hidMode())
        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.hidMode() }
        }
        Unit
    }

    @Test
    fun `known HID mode change is one exact request and unknown modes remain read only`() = runBlocking {
        server.enqueue(successWithoutData())

        client.api.setHidMode(NanoKvmHidMode.HidOnly)

        assertEquals(
            mapOf("mode" to "hid-only"),
            flatJson(takeAuthenticated("POST", "/api/hid/mode")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.setHidMode(NanoKvmHidMode.Other("future-mode")) }
        }
        assertEquals(1, server.requestCount)
        Unit
    }

    @Test
    fun `virtual-device read and one-shot toggle use exact stable routes`() = runBlocking {
        server.enqueue(envelope("""{"network":true,"media":false,"disk":false}"""))
        server.enqueue(envelope("""{"on":true}"""))

        assertEquals(
            NanoKvmVirtualDevices(network = true, media = false, disk = false),
            client.api.virtualDevices(),
        )
        assertEquals(
            NanoKvmVirtualDeviceToggleResult(NanoKvmVirtualDevice.DISK, enabled = true),
            client.api.toggleVirtualDevice(NanoKvmVirtualDevice.DISK),
        )
        takeAuthenticated("GET", "/api/vm/device/virtual")
        assertEquals(
            mapOf("device" to "disk"),
            flatJson(takeAuthenticated("POST", "/api/vm/device/virtual")),
        )
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `transfer enabled start and status omit unstable checksum and cancellation fields`() = runBlocking {
        val source = NanoKvmRemoteImageUrl.parse("https://images.example.test/os-1.2.iso?mirror=eu")
        server.enqueue(envelope("""{"enabled":true}"""))
        server.enqueue(
            envelope(
                """{"status":"in_progress","file":"$source","percentage":"42.5%"}""",
            ),
        )
        server.enqueue(envelope("""{"status":"queued-by-future-server"}"""))

        assertTrue(client.api.isImageTransferEnabled())
        val started = client.api.startImageTransfer(source)
        val future = client.api.imageTransferStatus()

        assertEquals(NanoKvmImageTransferState.InProgress, started.state)
        assertEquals(42.5, requireNotNull(started.percentage), 0.0)
        assertEquals(
            NanoKvmImageTransferState.Other("queued-by-future-server"),
            future.state,
        )
        takeAuthenticated("GET", "/api/download/image/enabled")
        val startJson = flatJson(takeAuthenticated("POST", "/api/download/image"))
        assertEquals(mapOf("file" to source.value), startJson)
        takeAuthenticated("GET", "/api/download/image/status")
        Unit
    }

    @Test
    fun `remote transfer URL accepts only safe HTTP image basenames`() {
        assertEquals(
            "http://example.test/images/disk.img",
            NanoKvmRemoteImageUrl.parse("http://example.test/images/disk.img").value,
        )
        listOf(
            "ftp://example.test/disk.iso",
            "https://user:secret@example.test/disk.iso",
            "https://example.test/readme.txt",
            "https://example.test/a..b.iso",
            "https://example.test/my%20disk.iso",
            "https://example.test/disk.iso#fragment",
            "https:///disk.iso",
            " https://example.test/disk.iso",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                NanoKvmRemoteImageUrl.parse(value)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `oversized transfer response fields are rejected`() {
        val invalidResponses = listOf(
            """{"status":"${"x".repeat(MAX_TRANSFER_STATUS_UTF8_BYTES + 1)}"}""",
            """{"status":"idle","file":"${"x".repeat(MAX_TRANSFER_FILE_UTF8_BYTES + 1)}"}""",
            """{"status":"idle","percentage":"${"1".repeat(MAX_TRANSFER_PERCENTAGE_UTF8_BYTES + 1)}"}""",
        )
        invalidResponses.forEach { response ->
            server.enqueue(envelope(response))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { client.api.imageTransferStatus() }
            }
        }
    }

    @Test
    fun `Wake-on-LAN methods canonicalize history and use DELETE with JSON body`() = runBlocking {
        val mac = NanoKvmMacAddress.parse("aa-bb-cc-dd-ee-ff")
        repeat(1) { server.enqueue(successWithoutData()) }
        server.enqueue(
            envelope(
                """{"macs":["aabb.ccdd.eeff   Lab   host","00:11:22:33:44:55"]}""",
            ),
        )
        repeat(2) { server.enqueue(successWithoutData()) }

        client.api.sendWakeOnLan(mac)
        val history = client.api.wakeOnLanHistory(NanoKvmApplicationVersion(2, 4, 3))
        client.api.renameWakeOnLanEntry(mac, "  Rack\tserver  ")
        client.api.deleteWakeOnLanEntry(mac)

        assertEquals("AA:BB:CC:DD:EE:FF", mac.value)
        assertEquals(
            listOf(
                NanoKvmWakeOnLanEntry(mac, "Lab host"),
                NanoKvmWakeOnLanEntry(NanoKvmMacAddress.parse("001122334455"), null),
            ),
            history,
        )
        assertEquals(
            mapOf("mac" to mac.value),
            flatJson(takeAuthenticated("POST", "/api/network/wol")),
        )
        takeAuthenticated("GET", "/api/network/wol/mac")
        assertEquals(
            mapOf("mac" to mac.value, "name" to "Rack server"),
            flatJson(takeAuthenticated("POST", "/api/network/wol/mac/name")),
        )
        assertEquals(
            mapOf("mac" to mac.value),
            flatJson(takeAuthenticated("DELETE", "/api/network/wol/mac")),
        )
    }

    @Test
    fun `legacy first-use WOL error is empty only with version context through 2_4_1`() {
        repeat(3) { server.enqueue(apiError(-2, "open file error")) }
        server.enqueue(apiError(-2, "different error"))

        assertTrue(
            runBlocking {
                client.api.wakeOnLanHistory(NanoKvmApplicationVersion(2, 4, 1)).isEmpty()
            },
        )
        assertThrows(ApiResponseException::class.java) {
            runBlocking { client.api.wakeOnLanHistory() }
        }
        assertThrows(ApiResponseException::class.java) {
            runBlocking {
                client.api.wakeOnLanHistory(NanoKvmApplicationVersion(2, 4, 2))
            }
        }
        assertThrows(ApiResponseException::class.java) {
            runBlocking {
                client.api.wakeOnLanHistory(NanoKvmApplicationVersion(2, 4, 1))
            }
        }
    }

    @Test
    fun `WOL validation rejects invalid MAC name malformed history and duplicate entries`() {
        listOf("", "AA:BB:CC:DD:EE", "GG:BB:CC:DD:EE:FF", "AA BB CC DD EE FF").forEach {
            assertThrows(IllegalArgumentException::class.java) { NanoKvmMacAddress.parse(it) }
        }
        val mac = NanoKvmMacAddress.parse("AA:BB:CC:DD:EE:FF")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.renameWakeOnLanEntry(mac, " ") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.renameWakeOnLanEntry(mac, "x".repeat(MAX_WOL_NAME_UTF8_BYTES + 1))
            }
        }
        server.enqueue(envelope("""{"macs":["not-a-mac workstation"]}"""))
        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.wakeOnLanHistory() }
        }
        server.enqueue(
            envelope(
                """{"macs":${Json.encodeToString(List(MAX_WOL_HISTORY_ENTRIES + 1) { "x" })}}""",
            ),
        )
        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.wakeOnLanHistory() }
        }
        server.enqueue(
            envelope(
                """{"macs":["AA:BB:CC:DD:EE:FF A","aa-bb-cc-dd-ee-ff B"]}""",
            ),
        )
        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.wakeOnLanHistory() }
        }
    }

    @Test
    fun `all client construction paths disable automatic connection retries`() {
        assertFalse(client.transport.retryOnConnectionFailure)

        val supplied = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
        val using = NanoKvmClient.using(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            httpClient = supplied,
        )
        try {
            assertFalse(using.transport.retryOnConnectionFailure)
        } finally {
            using.close()
            supplied.dispatcher.executorService.shutdown()
            supplied.connectionPool.evictAll()
        }
    }

    private fun takeAuthenticated(method: String, path: String): RecordedRequest {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals("nano-kvm-token=phase-three-token", request.getHeader("Cookie"))
        return request
    }

    private fun flatJson(request: RecordedRequest): Map<String, Any> =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject.mapValues { (_, value) ->
            if (value.jsonPrimitive.isString) value.jsonPrimitive.content else {
                value.jsonPrimitive.booleanOrNull ?: value.jsonPrimitive.content
            }
        }

    private fun envelope(data: String): MockResponse = jsonResponse(
        """{"code":0,"msg":"success","data":$data}""",
    )

    private fun imageListResponse(files: List<String>): MockResponse =
        envelope("""{"files":${Json.encodeToString(files)}}""")

    private fun successWithoutData(): MockResponse = jsonResponse(
        """{"code":0,"msg":"success","data":null}""",
    )

    private fun apiError(code: Int, message: String): MockResponse = jsonResponse(
        """{"code":$code,"msg":"$message","data":null}""",
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}

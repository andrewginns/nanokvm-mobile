package org.nanokvm.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NanoKvmAutostartApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("autostart-token"),
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `list and bounded content read use exact latest handles and clearable buffers`() =
        runBlocking {
            val scriptText = "#!/bin/sh\nprintf 'ready \u2713\\n'\n"
            server.enqueue(envelope("""{"files":["01-ready.sh","worker.py"]}"""))
            server.enqueue(stringEnvelope(scriptText))

            val catalog = client.api.autostartScripts()
            val script = requireNotNull(catalog.find("01-ready.sh"))
            val content = client.api.autostartContent(catalog, script)

            assertEquals(listOf("01-ready.sh", "worker.py"), catalog.scripts.map { it.name })
            assertEquals("sh", script.extension)
            assertEquals(scriptText.encodeToByteArray().size, content.byteCount)
            assertArrayEquals(scriptText.encodeToByteArray(), content.copyBytes())
            assertFalse(content.toString().contains(scriptText))
            content.close()
            assertThrows(IllegalStateException::class.java) { content.copyBytes() }

            takeAuthenticated("GET", "/api/vm/autostart")
            takeAuthenticated("GET", "/api/vm/autostart/01-ready.sh")
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `nil autostart slice decodes as an empty catalog`() = runBlocking {
        server.enqueue(envelope("""{"files":null}"""))

        assertTrue(client.api.autostartScripts().scripts.isEmpty())

        takeAuthenticated("GET", "/api/vm/autostart")
        Unit
    }

    @Test
    fun `create owns and clears content then sends one exact escaped JSON request`() = runBlocking {
        server.enqueue(envelope("""{"files":[]}"""))
        server.enqueue(stringEnvelope("01-init.sh"))
        val catalog = client.api.autostartScripts()
        val source = "#!/bin/sh\nprintf \"caf\u00e9\\\\ready\"\n".encodeToByteArray()
        val expected = source.copyOf()
        val content = NanoKvmAutostartWriteContent.takeOwnership(source)

        assertTrue(source.all { it == 0.toByte() })
        val receipt = client.api.createAutostartScript(catalog, "01-init.sh", content)

        assertEquals(
            NanoKvmAutostartWriteReceipt(
                fileName = "01-init.sh",
                byteCount = expected.size,
                kind = NanoKvmAutostartWriteKind.CREATE,
            ),
            receipt,
        )
        takeAuthenticated("GET", "/api/vm/autostart")
        assertJsonBody(
            takeAuthenticated("POST", "/api/vm/autostart/01-init.sh"),
            """{"content":${Json.encodeToString(expected.decodeToString())}}""",
        )
        assertTrue(content.toString().contains("consumed=true"))
        assertFalse(content.toString().contains("caf\u00e9"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `update and delete require fresh exact snapshots and use upstream routes`() = runBlocking {
        server.enqueue(envelope("""{"files":["boot.py"]}"""))
        server.enqueue(stringEnvelope("boot.py"))
        val updateCatalog = client.api.autostartScripts()
        val updateScript = updateCatalog.scripts.single()

        val receipt = client.api.updateAutostartScript(
            updateCatalog,
            updateScript,
            NanoKvmAutostartWriteContent.takeOwnership("print('new')\n".encodeToByteArray()),
        )

        assertEquals(NanoKvmAutostartWriteKind.UPDATE, receipt.kind)
        takeAuthenticated("GET", "/api/vm/autostart")
        assertJsonBody(
            takeAuthenticated("POST", "/api/vm/autostart/boot.py"),
            """{"content":"print('new')\n"}""",
        )

        server.enqueue(envelope("""{"files":["boot.py"]}"""))
        server.enqueue(successWithoutData())
        val deleteCatalog = client.api.autostartScripts()
        val deleteScript = deleteCatalog.scripts.single()
        client.api.deleteAutostartScript(deleteCatalog, deleteScript)

        takeAuthenticated("GET", "/api/vm/autostart")
        val delete = takeAuthenticated("DELETE", "/api/vm/autostart/boot.py")
        assertEquals(0L, delete.bodySize)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.deleteAutostartScript(deleteCatalog, deleteScript) }
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `new list immediately invalidates older and counterfeit handles`() = runBlocking {
        server.enqueue(envelope("""{"files":["old.sh"]}"""))
        server.enqueue(envelope("""{"files":["new.sh"]}"""))
        val oldCatalog = client.api.autostartScripts()
        val oldScript = oldCatalog.scripts.single()
        val latestCatalog = client.api.autostartScripts()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.autostartContent(oldCatalog, oldScript) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.autostartContent(
                    latestCatalog,
                    NanoKvmAutostartScript("new.sh"),
                )
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `create refuses overwrite and consumes supplied root-equivalent content locally`() =
        runBlocking {
            server.enqueue(envelope("""{"files":["exists.sh"]}"""))
            val catalog = client.api.autostartScripts()
            val content = NanoKvmAutostartWriteContent.takeOwnership(
                "#!/bin/sh\nexit 0\n".encodeToByteArray(),
            )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.api.createAutostartScript(catalog, "exists.sh", content) }
            }

            assertTrue(content.toString().contains("consumed=true"))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `unsafe basenames and content bytes are rejected before transport and source is cleared`() {
        listOf(
            "",
            "../boot.sh",
            "sub/boot.sh",
            "boot.txt",
            "boot script.sh",
            "boot..sh",
            ".hidden.sh",
            "x".repeat(252) + ".sh",
        ).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                validateAutostartBasename(name)
            }
        }
        assertEquals("UPPER.PY", validateAutostartBasename("UPPER.PY"))

        val invalidContents = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            byteArrayOf(0x7f),
            byteArrayOf(0xc0.toByte(), 0x80.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            ByteArray(MAX_AUTOSTART_CONTENT_BYTES + 1) { 'x'.code.toByte() },
        )
        invalidContents.forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                NanoKvmAutostartWriteContent.takeOwnership(source)
            }
            assertTrue(source.all { it == 0.toByte() })
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `invalid server lists and content become redacted invalid-response failures`() {
        val invalidLists = listOf(
            """{"files":["../escape.sh"]}""",
            """{"files":["same.sh","same.sh"]}""",
            """{"files":["notes.txt"]}""",
        )
        invalidLists.forEach { data ->
            server.enqueue(envelope(data))
            val error = assertThrows(NanoKvmAutostartOperationException::class.java) {
                runBlocking { client.api.autostartScripts() }
            }
            assertEquals(NanoKvmAutostartOperation.LIST, error.operation)
            assertEquals(NanoKvmAutostartFailure.InvalidResponse, error.failure)
            assertNull(error.cause)
        }

        server.enqueue(envelope("""{"files":["secret.sh"]}"""))
        server.enqueue(stringEnvelope("secret\u0000payload"))
        val catalog = runBlocking { client.api.autostartScripts() }
        val error = assertThrows(NanoKvmAutostartOperationException::class.java) {
            runBlocking { client.api.autostartContent(catalog, catalog.scripts.single()) }
        }
        assertEquals(NanoKvmAutostartFailure.InvalidResponse, error.failure)
        assertFalse(error.toString().contains("secret\u0000payload"))
        assertNull(error.cause)
    }

    @Test
    fun `server and HTTP diagnostics never retain root-equivalent material`() {
        val secret = "ROOT_SCRIPT_SECRET_9471"
        server.enqueue(jsonResponse("""{"code":-2,"msg":"$secret","data":null}"""))
        val apiError = assertThrows(NanoKvmAutostartOperationException::class.java) {
            runBlocking { client.api.autostartScripts() }
        }
        assertEquals(NanoKvmAutostartFailure.Api(-2), apiError.failure)
        assertFalse(apiError.toString().contains(secret))
        assertNull(apiError.cause)

        server.enqueue(MockResponse().setResponseCode(500).setBody(secret))
        val httpError = assertThrows(NanoKvmAutostartOperationException::class.java) {
            runBlocking { client.api.autostartScripts() }
        }
        assertEquals(NanoKvmAutostartFailure.Http(500), httpError.failure)
        assertFalse(httpError.toString().contains(secret))
        assertNull(httpError.cause)
    }

    @Test
    fun `ambiguous create loss consumes snapshot and is never replayed`() = runBlocking {
        server.enqueue(envelope("""{"files":[]}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val catalog = client.api.autostartScripts()

        val error = assertThrows(NanoKvmAutostartOperationException::class.java) {
            runBlocking {
                client.api.createAutostartScript(
                    catalog,
                    "once.sh",
                    NanoKvmAutostartWriteContent.takeOwnership("exit 0\n".encodeToByteArray()),
                )
            }
        }
        assertEquals(NanoKvmAutostartFailure.Transport, error.failure)

        Thread.sleep(150)
        assertEquals(2, server.requestCount)
        assertFalse(client.transport.retryOnConnectionFailure)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.createAutostartScript(
                    catalog,
                    "once.sh",
                    NanoKvmAutostartWriteContent.takeOwnership("exit 0\n".encodeToByteArray()),
                )
            }
        }
        assertEquals(2, server.requestCount)
    }

    private fun takeAuthenticated(method: String, path: String): RecordedRequest {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals("nano-kvm-token=autostart-token", request.getHeader("Cookie"))
        return request
    }

    private fun assertJsonBody(request: RecordedRequest, expected: String) {
        val actualJson = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val expectedJson: JsonObject = Json.parseToJsonElement(expected).jsonObject
        assertEquals(expectedJson, actualJson)
    }

    private fun stringEnvelope(value: String): MockResponse = envelope(Json.encodeToString(value))

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

package org.nanokvm.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NanoKvmOperatorScriptApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("operator-token"),
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `script list run delete and upload match the 2_4_3 wire contract`() = runBlocking {
        server.enqueue(envelope("""{"files":["health.sh","TASK.PY"]}"""))
        server.enqueue(envelope("""{"log":"healthy\n"}"""))
        server.enqueue(envelope("""{"log":""}"""))
        server.enqueue(successWithoutData())
        server.enqueue(envelope("""{"file":"repair.sh"}"""))

        val catalog = client.api.listScripts()
        assertEquals(listOf("health.sh", "TASK.PY"), catalog.scripts.map { it.name })
        assertEquals("py", catalog.scripts.last().extension)

        val foreground = client.api.runScript(
            catalog,
            requireNotNull(catalog.find("health.sh")),
            NanoKvmScriptRunMode.FOREGROUND,
        )
        val background = client.api.runScript(
            catalog,
            requireNotNull(catalog.find("TASK.PY")),
            NanoKvmScriptRunMode.BACKGROUND,
        )
        client.api.deleteScript(catalog, requireNotNull(catalog.find("health.sh")))
        val source = "#!/bin/sh\nprintf 'fixed\\n'\n".encodeToByteArray()
        val receipt = client.api.uploadScript("repair.sh", source)

        assertEquals(NanoKvmScriptRunMode.FOREGROUND, foreground.mode)
        assertEquals("healthy\n", foreground.output)
        assertEquals(NanoKvmScriptRunMode.BACKGROUND, background.mode)
        assertEquals("", background.output)
        assertEquals(NanoKvmScriptUploadReceipt("repair.sh", source.size), receipt)

        takeAuthenticated("GET", "/api/vm/script")
        assertEquals(
            mapOf("name" to "health.sh", "type" to "foreground"),
            jsonObject(takeAuthenticated("POST", "/api/vm/script/run")),
        )
        assertEquals(
            mapOf("name" to "TASK.PY", "type" to "background"),
            jsonObject(takeAuthenticated("POST", "/api/vm/script/run")),
        )
        assertEquals(
            mapOf("name" to "health.sh"),
            jsonObject(takeAuthenticated("DELETE", "/api/vm/script")),
        )
        val upload = takeAuthenticated("POST", "/api/vm/script/upload")
        assertTrue(upload.getHeader("Content-Type")?.startsWith("multipart/form-data; boundary=") == true)
        val multipart = upload.body.readUtf8()
        assertTrue(multipart.contains("name=\"file\"; filename=\"repair.sh\""))
        assertTrue(multipart.contains("Content-Type: application/octet-stream"))
        assertTrue(multipart.contains("#!/bin/sh\nprintf 'fixed\\n'\n"))
        Unit
    }

    @Test
    fun `nil script slice decodes as an empty catalog`() = runBlocking {
        server.enqueue(envelope("""{"files":null}"""))

        assertTrue(client.api.listScripts().scripts.isEmpty())

        takeAuthenticated("GET", "/api/vm/script")
        Unit
    }

    @Test
    fun `script list rejects traversal injection unsupported extensions duplicates and bounds`() {
        val invalidLists = listOf(
            listOf("../evil.sh"),
            listOf("folder/evil.sh"),
            listOf("folder\\evil.py"),
            listOf("x;reboot.sh"),
            listOf("a..b.sh"),
            listOf(".hidden.sh"),
            listOf("notes.txt"),
            listOf("x".repeat(MAX_SCRIPT_BASENAME_UTF8_BYTES) + ".sh"),
            listOf("same.sh", "same.sh"),
            List(MAX_SCRIPT_COUNT + 1) { "script$it.sh" },
        )

        invalidLists.forEach { files ->
            server.enqueue(envelope("""{"files":${Json.encodeToString(files)}}"""))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { client.api.listScripts() }
            }
        }
        assertEquals(invalidLists.size, server.requestCount)
    }

    @Test
    fun `run and delete require exact handles from this APIs latest successful list`() {
        repeat(3) {
            server.enqueue(envelope("""{"files":["same.sh"]}"""))
        }
        val first = runBlocking { client.api.listScripts() }
        val second = runBlocking { client.api.listScripts() }
        val foreignClient = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("foreign-token"),
        )
        val foreign = try {
            runBlocking { foreignClient.api.listScripts() }
        } finally {
            foreignClient.close()
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.runScript(
                    first,
                    first.scripts.single(),
                    NanoKvmScriptRunMode.FOREGROUND,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.deleteScript(second, first.scripts.single())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.deleteScript(second, NanoKvmScript("same.sh"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.deleteScript(foreign, foreign.scripts.single())
            }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `upload and delete invalidate script catalogs and upload receipt is not a handle`() {
        server.enqueue(envelope("""{"files":["old.sh"]}"""))
        server.enqueue(envelope("""{"file":"new.sh"}"""))
        val catalog = runBlocking { client.api.listScripts() }
        runBlocking { client.api.uploadScript("new.sh", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.runScript(
                    catalog,
                    catalog.scripts.single(),
                    NanoKvmScriptRunMode.FOREGROUND,
                )
            }
        }

        server.enqueue(envelope("""{"files":["old.sh"]}"""))
        server.enqueue(successWithoutData())
        val refreshed = runBlocking { client.api.listScripts() }
        runBlocking { client.api.deleteScript(refreshed, refreshed.scripts.single()) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.deleteScript(refreshed, refreshed.scripts.single())
            }
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `upload validates basename and byte cap before network IO`() {
        listOf("", "../x.sh", "x;touch.sh", "folder/x.py", "safe.txt", "a..b.py").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.api.uploadScript(it, byteArrayOf(1)) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.uploadScript("empty.sh", byteArrayOf()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.uploadScript("large.py", ByteArray(MAX_SCRIPT_UPLOAD_BYTES + 1))
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `malicious upload response and oversized foreground output are rejected`() {
        server.enqueue(envelope("""{"file":"../different.sh"}"""))
        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { client.api.uploadScript("safe.sh", byteArrayOf(1)) }
        }

        server.enqueue(envelope("""{"files":["safe.sh"]}"""))
        val catalog = runBlocking { client.api.listScripts() }
        server.enqueue(envelope("""{"log":"${"x".repeat(MAX_SCRIPT_OUTPUT_UTF8_BYTES + 1)}"}"""))
        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking {
                client.api.runScript(
                    catalog,
                    catalog.scripts.single(),
                    NanoKvmScriptRunMode.FOREGROUND,
                )
            }
        }
    }

    @Test
    fun `script API errors are bounded structured and forward compatible`() {
        server.enqueue(envelope("""{"files":["fail.sh"]}"""))
        val catalog = runBlocking { client.api.listScripts() }
        server.enqueue(apiError(-2, "x".repeat(MAX_SCRIPT_ERROR_MESSAGE_UTF8_BYTES + 100)))
        val known = assertThrows(NanoKvmScriptOperationException::class.java) {
            runBlocking {
                client.api.runScript(
                    catalog,
                    catalog.scripts.single(),
                    NanoKvmScriptRunMode.FOREGROUND,
                )
            }
        }
        assertEquals(NanoKvmScriptOperation.RUN, known.operation)
        assertTrue(known.failure is NanoKvmScriptFailure.OperationFailed)
        assertTrue(known.failure.serverMessage.utf8Size() <= MAX_SCRIPT_ERROR_MESSAGE_UTF8_BYTES)

        server.enqueue(apiError(73, "future failure"))
        val future = assertThrows(NanoKvmScriptOperationException::class.java) {
            runBlocking {
                client.api.runScript(
                    catalog,
                    catalog.scripts.single(),
                    NanoKvmScriptRunMode.BACKGROUND,
                )
            }
        }
        assertEquals(
            NanoKvmScriptFailure.Other(73, "NanoKVM rejected the script operation."),
            future.failure,
        )
    }

    @Test
    fun `authentication expiry clears latest script handles`() {
        server.enqueue(envelope("""{"files":["safe.sh"]}"""))
        val catalog = runBlocking { client.api.listScripts() }
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(AuthenticationExpiredException::class.java) {
            runBlocking { client.api.vmInfo() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.api.deleteScript(catalog, catalog.scripts.single())
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `script mutations inherit transport no-replay policy`() {
        assertFalse(client.transport.retryOnConnectionFailure)
    }

    @Test
    fun `ambiguous connection loss does not replay run delete or upload`() {
        server.enqueue(envelope("""{"files":["once.sh"]}"""))
        val catalog = runBlocking { client.api.listScripts() }

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(Exception::class.java) {
            runBlocking {
                client.api.runScript(
                    catalog,
                    catalog.scripts.single(),
                    NanoKvmScriptRunMode.FOREGROUND,
                )
            }
        }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(Exception::class.java) {
            runBlocking { client.api.deleteScript(catalog, catalog.scripts.single()) }
        }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(Exception::class.java) {
            runBlocking { client.api.uploadScript("once.py", byteArrayOf(1, 2, 3)) }
        }

        Thread.sleep(200)
        assertEquals(4, server.requestCount)
    }

    private fun takeAuthenticated(method: String, path: String): RecordedRequest {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals("nano-kvm-token=operator-token", request.getHeader("Cookie"))
        return request
    }

    private fun jsonObject(request: RecordedRequest): Map<String, String> =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.content
        }

    private fun envelope(data: String): MockResponse = jsonResponse(
        """{"code":0,"msg":"success","data":$data}""",
    )

    private fun successWithoutData(): MockResponse = jsonResponse(
        """{"code":0,"msg":"success","data":null}""",
    )

    private fun apiError(code: Int, message: String): MockResponse = jsonResponse(
        """{"code":$code,"msg":${Json.encodeToString(message)},"data":null}""",
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}

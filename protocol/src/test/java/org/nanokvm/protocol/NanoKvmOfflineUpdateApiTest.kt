package org.nanokvm.protocol

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NanoKvmOfflineUpdateApiTest {
    private lateinit var server: MockWebServer
    private lateinit var tokenStore: InMemorySessionTokenStore
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = InMemorySessionTokenStore("offline-update-token")
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
    fun `offline archive streams as one exact authenticated multipart request`() = runBlocking {
        server.enqueue(successWithoutData())
        val content = "bounded-archive-content".encodeToByteArray()
        val opens = AtomicInteger()
        val progress = mutableListOf<NanoKvmOfflineUpdateProgress>()
        val packageFile = packageFile("nanokvm_2.4.3.tar.gz", content, opens)

        val receipt = client.api.startOfflineUpdate(packageFile, progress::add)

        assertEquals(
            NanoKvmOfflineUpdateReceipt("nanokvm_2.4.3.tar.gz", content.size.toLong()),
            receipt,
        )
        assertEquals(1, opens.get())
        assertEquals(
            listOf(
                NanoKvmOfflineUpdateProgress(0, content.size.toLong()),
                NanoKvmOfflineUpdateProgress(content.size.toLong(), content.size.toLong()),
            ),
            progress,
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/application/update/offline", request.path)
        assertEquals("nano-kvm-token=offline-update-token", request.getHeader("Cookie"))
        assertNull(request.getHeader("Transfer-Encoding"))

        val contentType = requireNotNull(request.getHeader("Content-Type"))
        assertTrue(contentType.startsWith("multipart/form-data; boundary="))
        val boundary = contentType.substringAfter("boundary=")
        val bodyBytes = request.body.readByteArray()
        assertEquals(bodyBytes.size.toLong(), request.getHeader("Content-Length")?.toLong())
        val body = bodyBytes.toString(Charsets.ISO_8859_1)
        assertTrue(body.startsWith("--$boundary\r\n"))
        assertTrue(body.endsWith("\r\n--$boundary--\r\n"))
        assertTrue(
            body.contains(
                "Content-Disposition: form-data; name=\"file\"; " +
                    "filename=\"nanokvm_2.4.3.tar.gz\"",
            ),
        )
        assertTrue(body.contains("Content-Type: application/gzip"))
        assertTrue(body.contains("\r\n\r\nbounded-archive-content\r\n--$boundary--\r\n"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `authentication expiry clears session and never reuses the consumed package`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val packageFile = packageFile("nanokvm_2.4.4.tar.gz", byteArrayOf(1, 2, 3))

        assertThrows(AuthenticationExpiredException::class.java) {
            runBlocking { client.api.startOfflineUpdate(packageFile) }
        }

        assertNull(tokenStore.read())
        assertThrows(IllegalStateException::class.java) { packageFile.consume() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancellation cancels the call and package remains consumed`() {
        server.enqueue(successWithoutData().setHeadersDelay(5, TimeUnit.SECONDS))
        val packageFile = packageFile(
            "nanokvm_2.4.5.tar.gz",
            ByteArray(64 * 1024) { (it and 0xff).toByte() },
        )

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(100) { client.api.startOfflineUpdate(packageFile) }
            }
        }

        assertThrows(IllegalStateException::class.java) { packageFile.consume() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancellation closes an active provider stream`() = runBlocking {
        // A normal response keeps MockWebServer reading the body; the body itself blocks until
        // cancellation closes its provider stream.
        server.enqueue(successWithoutData())
        val source = CloseAwareBlockingInputStream()
        val packageFile = NanoKvmOfflineUpdatePackage.create(
            "nanokvm_2.4.10.tar.gz",
            1,
            NanoKvmOfflineUpdateStream { source },
        )
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            client.api.startOfflineUpdate(packageFile)
        }

        assertTrue(source.readEntered.await(2, TimeUnit.SECONDS))
        update.cancel()
        update.join()

        assertTrue(update.isCancelled)
        assertTrue(source.closed.await(2, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) { packageFile.consume() }
        Unit
    }

    @Test
    fun `ambiguous disconnect is redacted and never replayed`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val opens = AtomicInteger()
        val packageFile = packageFile(
            "nanokvm_2.4.6.tar.gz",
            "one-shot".encodeToByteArray(),
            opens,
        )

        val error = assertThrows(NanoKvmOfflineUpdateException::class.java) {
            runBlocking { client.api.startOfflineUpdate(packageFile) }
        }

        assertEquals(NanoKvmOfflineUpdateFailure.TransportOutcomeUnknown, error.failure)
        assertEquals(1, opens.get())
        Thread.sleep(100)
        assertEquals(1, server.requestCount)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { client.api.startOfflineUpdate(packageFile) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `redirect response is surfaced without replaying archive body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .addHeader("Location", server.url("/api/application/update/offline")),
        )
        val opens = AtomicInteger()
        val packageFile = packageFile("nanokvm_2.4.7.tar.gz", byteArrayOf(7), opens)

        val error = assertThrows(NanoKvmOfflineUpdateException::class.java) {
            runBlocking { client.api.startOfflineUpdate(packageFile) }
        }

        assertEquals(
            NanoKvmOfflineUpdateFailure.HttpRejected(307, outcomeUnknown = false),
            error.failure,
        )
        assertEquals(1, opens.get())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `filename and content length bounds reject before opening or dispatch`() {
        val opens = AtomicInteger()
        val stream = NanoKvmOfflineUpdateStream {
            opens.incrementAndGet()
            ByteArrayInputStream(byteArrayOf(1))
        }
        val invalidNames = listOf(
            "nanokvm_2.4.tar.gz",
            "nanokvm_2.4.3.zip",
            "NanoKVM_2.4.3.tar.gz",
            "../nanokvm_2.4.3.tar.gz",
            "nanokvm_1234567890.4.3.tar.gz",
        )

        invalidNames.forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                NanoKvmOfflineUpdatePackage.create(name, 1, stream)
            }
        }
        listOf(0L, -1L, NanoKvmOfflineUpdatePackage.MAX_CONTENT_LENGTH_BYTES + 1).forEach { length ->
            assertThrows(IllegalArgumentException::class.java) {
                NanoKvmOfflineUpdatePackage.create("nanokvm_2.4.3.tar.gz", length, stream)
            }
        }

        assertEquals(0, opens.get())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `declared length mismatch fails locally without retaining source detail`() {
        val short = NanoKvmOfflineUpdatePackage.create(
            "nanokvm_2.4.8.tar.gz",
            4,
            NanoKvmOfflineUpdateStream { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val long = NanoKvmOfflineUpdatePackage.create(
            "nanokvm_2.4.9.tar.gz",
            2,
            NanoKvmOfflineUpdateStream { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        repeat(2) { server.enqueue(successWithoutData()) }

        listOf(short, long).forEach { packageFile ->
            val error = assertThrows(NanoKvmOfflineUpdateException::class.java) {
                runBlocking { client.api.startOfflineUpdate(packageFile) }
            }
            assertEquals(NanoKvmOfflineUpdateFailure.LocalSourceUnavailable, error.failure)
            assertFalse(error.toString().contains("ByteArrayInputStream"))
        }
    }

    @Test
    fun `server errors are bounded classifications without appliance text`() {
        val sensitiveMessage = "update failed: /tmp/private/package/location"
        server.enqueue(apiError(-1, sensitiveMessage))

        val error = assertThrows(NanoKvmOfflineUpdateException::class.java) {
            runBlocking {
                client.api.startOfflineUpdate(
                    packageFile("nanokvm_2.5.0.tar.gz", byteArrayOf(1)),
                )
            }
        }

        assertEquals(
            NanoKvmOfflineUpdateFailure.ApiRejected(-1),
            error.failure,
        )
        assertTrue(error.failure.outcomeUnknown)
        assertFalse(error.toString().contains(sensitiveMessage))
        assertFalse(error.toString().contains("/tmp/"))
    }

    @Test
    fun `invalid success and server HTTP failure preserve outcome uncertainty without bodies`() {
        server.enqueue(jsonResponse("not-json"))
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("internal path /data/private and deployment detail"),
        )

        val invalid = assertThrows(NanoKvmOfflineUpdateException::class.java) {
            runBlocking {
                client.api.startOfflineUpdate(
                    packageFile("nanokvm_2.5.1.tar.gz", byteArrayOf(1)),
                )
            }
        }
        val unavailable = assertThrows(NanoKvmOfflineUpdateException::class.java) {
            runBlocking {
                client.api.startOfflineUpdate(
                    packageFile("nanokvm_2.5.2.tar.gz", byteArrayOf(2)),
                )
            }
        }

        assertEquals(
            NanoKvmOfflineUpdateFailure.InvalidResponseOutcomeUnknown,
            invalid.failure,
        )
        assertEquals(
            NanoKvmOfflineUpdateFailure.HttpRejected(503, outcomeUnknown = true),
            unavailable.failure,
        )
        assertFalse(unavailable.toString().contains("/data/private"))
        assertFalse(unavailable.toString().contains("deployment detail"))
    }

    @Test
    fun `offline update capability has exact 2_3_1 version floor`() = runBlocking {
        enqueueProbe("2.3.0")
        val before = client.api.probeCapabilities().capabilities
        enqueueProbe("2.3.1")
        val introduced = client.api.probeCapabilities().capabilities

        val unsupported = before[NanoKvmCapability.OFFLINE_UPDATE]
        assertTrue(unsupported is NanoKvmCapabilitySupport.Unsupported)
        assertEquals(
            NanoKvmCapabilityEvidence.VersionFloor(
                actual = NanoKvmApplicationVersion(2, 3, 0),
                minimum = NanoKvmApplicationVersion(2, 3, 1),
            ),
            (unsupported as NanoKvmCapabilitySupport.Unsupported).evidence,
        )
        assertEquals(
            NanoKvmCapabilitySupport.Supported(
                NanoKvmCapabilityEvidence.VersionFloor(
                    actual = NanoKvmApplicationVersion(2, 3, 1),
                    minimum = NanoKvmApplicationVersion(2, 3, 1),
                ),
            ),
            introduced[NanoKvmCapability.OFFLINE_UPDATE],
        )
    }

    private fun packageFile(
        name: String,
        content: ByteArray,
        opens: AtomicInteger = AtomicInteger(),
    ): NanoKvmOfflineUpdatePackage = NanoKvmOfflineUpdatePackage.create(
        fileName = name,
        contentLength = content.size.toLong(),
        stream = NanoKvmOfflineUpdateStream {
            opens.incrementAndGet()
            ByteArrayInputStream(content)
        },
    )

    private fun enqueueProbe(version: String) {
        server.enqueue(envelope("""{"application":"$version"}"""))
        server.enqueue(envelope("""{"version":"NanoKVM Cube"}"""))
        server.enqueue(envelope("""{"pwr":false,"hdd":false}"""))
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

    private class CloseAwareBlockingInputStream : InputStream() {
        val readEntered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val isClosed = AtomicBoolean(false)

        override fun read(): Int {
            readEntered.countDown()
            while (!isClosed.get()) {
                Thread.sleep(5)
            }
            throw IOException("provider detail that must not escape")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()

        override fun close() {
            isClosed.set(true)
            closed.countDown()
        }
    }
}

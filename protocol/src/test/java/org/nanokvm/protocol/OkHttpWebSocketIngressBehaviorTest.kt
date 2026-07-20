package org.nanokvm.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterizes the exact OkHttp version selected by the verified dependency graph.
 *
 * This deliberately observes OkHttp's private reader buffer. It is an audit tripwire rather than
 * production coupling: a dependency update that changes the implementation must make this test
 * fail so inbound allocation behavior is measured again before the residual risk is reclassified.
 */
class OkHttpWebSocketIngressBehaviorTest {
    @Test
    fun `slow fragmented message accumulates completely before listener can reject it`() {
        RawWebSocketServer().use { server ->
            val opened = CountDownLatch(1)
            val delivered = CountDownLatch(1)
            val received = AtomicReference<ByteString?>()
            val failure = AtomicReference<Throwable?>()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            val socket = client.newWebSocket(
                Request.Builder().url(server.url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        received.set(bytes)
                        delivered.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        failure.set(t)
                        delivered.countDown()
                        response?.close()
                    }
                },
            )

            try {
                assertTrue(server.handshakeComplete.await(2, TimeUnit.SECONDS))
                assertTrue(opened.await(2, TimeUnit.SECONDS))

                val first = ByteArray(48 * 1_024) { 0x11 }
                server.sendFragment(opcode = BINARY_OPCODE, final = false, payload = first)
                assertEquals(first.size.toLong(), awaitBufferedBytes(socket, first.size.toLong()))
                assertFalse(
                    "A non-final fragment reached the application listener",
                    delivered.await(100, TimeUnit.MILLISECONDS),
                )

                val second = ByteArray(48 * 1_024) { 0x22 }
                server.sendFragment(opcode = CONTINUATION_OPCODE, final = false, payload = second)
                val cumulative = first.size.toLong() + second.size
                assertEquals(cumulative, awaitBufferedBytes(socket, cumulative))
                assertFalse(
                    "A fragmented message was delivered before FIN",
                    delivered.await(100, TimeUnit.MILLISECONDS),
                )

                val final = ByteArray(17) { 0x33 }
                server.sendFragment(opcode = CONTINUATION_OPCODE, final = true, payload = final)
                assertTrue(
                    "The complete fragmented message was not delivered",
                    delivered.await(2, TimeUnit.SECONDS),
                )
                assertNull(failure.get())
                assertEquals(first.size + second.size + final.size, received.get()?.size)
            } finally {
                socket.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `unsolicited compression negotiation fails the handshake`() {
        RawWebSocketServer(responseExtension = "permessage-deflate").use { server ->
            val client = NanoKvmClient.create(
                endpoint = NanoKvmEndpoint.parse(server.origin),
                tokenStore = InMemorySessionTokenStore("fixture-token"),
            )
            val input = client.newInputSocket(heartbeatIntervalMillis = 10_000)
            try {
                assertTrue(input.connect())
                assertTrue(server.handshakeComplete.await(2, TimeUnit.SECONDS))
                assertTrue(
                    "The compression offer reached the peer",
                    !requireNotNull(server.requestHeaders.get()).contains(
                        "Sec-WebSocket-Extensions:",
                        ignoreCase = true,
                    ),
                )
                val failed = awaitInputState(input) { it is InputConnectionState.Failed }
                    as? InputConnectionState.Failed
                assertTrue("Unsolicited compression did not fail the handshake", failed != null)
                assertTrue(failed?.cause is ProtocolException)
            } finally {
                input.close()
                client.close()
            }
        }
    }

    @Test
    fun `compressed frame is rejected from its header before declared payload allocation`() {
        RawWebSocketServer().use { server ->
            val client = NanoKvmClient.create(
                endpoint = NanoKvmEndpoint.parse(server.origin),
                tokenStore = InMemorySessionTokenStore("fixture-token"),
            )
            val input = client.newInputSocket(heartbeatIntervalMillis = 10_000)
            try {
                assertTrue(input.connect())
                assertTrue(server.handshakeComplete.await(2, TimeUnit.SECONDS))
                val connected = awaitInputState(input) { it is InputConnectionState.Connected }
                assertTrue(connected is InputConnectionState.Connected)

                // Send only RSV1 plus a declared 8 MiB length. With compression disabled, OkHttp
                // rejects the flag at the frame header rather than waiting for or inflating a body.
                server.sendFrameHeader(
                    opcode = BINARY_OPCODE,
                    final = true,
                    compressed = true,
                    payloadLength = 8L * 1_024 * 1_024,
                )
                val failed = awaitInputState(input) { it is InputConnectionState.Failed }
                    as? InputConnectionState.Failed
                assertTrue("Compressed frame header did not fail the socket", failed != null)
                assertTrue(failed?.cause is ProtocolException)
            } finally {
                input.close()
                client.close()
            }
        }
    }

    private fun awaitInputState(
        input: NanoKvmInputSocket,
        predicate: (InputConnectionState) -> Boolean,
    ): InputConnectionState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var state = input.state.value
        while (!predicate(state) && System.nanoTime() < deadline) {
            Thread.yield()
            state = input.state.value
        }
        return state
    }

    private fun awaitBufferedBytes(webSocket: WebSocket, expected: Long): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var observed = -1L
        while (System.nanoTime() < deadline) {
            observed = readerMessageBuffer(webSocket)?.size ?: -1L
            if (observed >= expected) return observed
            Thread.yield()
        }
        return observed
    }

    private fun readerMessageBuffer(webSocket: WebSocket): Buffer? {
        val readerField = webSocket.javaClass.getDeclaredField("reader").apply { isAccessible = true }
        val reader = readerField.get(webSocket) ?: return null
        val bufferField = reader.javaClass.getDeclaredField("messageFrameBuffer").apply {
            isAccessible = true
        }
        return bufferField.get(reader) as Buffer
    }

    private companion object {
        const val CONTINUATION_OPCODE = 0x0
        const val BINARY_OPCODE = 0x2
    }
}

/** Minimal uncompressed RFC 6455 peer which exposes fragment boundaries to the test. */
private class RawWebSocketServer(
    private val responseExtension: String? = null,
) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "raw-websocket-ingress-fixture").apply { isDaemon = true }
    }
    private val accepted = AtomicReference<Socket?>()
    private val output = AtomicReference<BufferedOutputStream?>()
    val requestHeaders = AtomicReference<String?>()
    val handshakeComplete = CountDownLatch(1)
    val origin: String = "http://${server.inetAddress.hostAddress}:${server.localPort}"
    val url: String = "$origin/hostile"

    init {
        executor.execute {
            val socket = server.accept().apply { soTimeout = 3_000 }
            accepted.set(socket)
            val input = BufferedInputStream(socket.getInputStream())
            val headers = readHttpHeaders(input)
            requestHeaders.set(headers)
            val key = headers.lineSequence()
                .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                .substringAfter(':')
                .trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(
                    (key + WEBSOCKET_GUID).toByteArray(StandardCharsets.ISO_8859_1),
                ),
            )
            val sink = BufferedOutputStream(socket.getOutputStream())
            val extensionHeader = responseExtension?.let {
                "Sec-WebSocket-Extensions: $it\r\n"
            }.orEmpty()
            sink.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n" +
                    extensionHeader +
                    "\r\n")
                    .toByteArray(StandardCharsets.ISO_8859_1),
            )
            sink.flush()
            output.set(sink)
            handshakeComplete.countDown()
        }
    }

    @Synchronized
    fun sendFragment(opcode: Int, final: Boolean, payload: ByteArray) {
        check(handshakeComplete.await(2, TimeUnit.SECONDS)) { "WebSocket handshake did not complete" }
        val sink = checkNotNull(output.get())
        sink.write((if (final) 0x80 else 0) or opcode)
        writePayloadLength(sink, payload.size.toLong())
        sink.write(payload)
        sink.flush()
    }

    @Synchronized
    fun sendFrameHeader(
        opcode: Int,
        final: Boolean,
        compressed: Boolean,
        payloadLength: Long,
    ) {
        check(handshakeComplete.await(2, TimeUnit.SECONDS)) { "WebSocket handshake did not complete" }
        val sink = checkNotNull(output.get())
        sink.write((if (final) 0x80 else 0) or (if (compressed) 0x40 else 0) or opcode)
        writePayloadLength(sink, payloadLength)
        sink.flush()
    }

    override fun close() {
        accepted.getAndSet(null)?.close()
        server.close()
        executor.shutdownNow()
    }

    private fun readHttpHeaders(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val terminal = byteArrayOf(
            '\r'.code.toByte(),
            '\n'.code.toByte(),
            '\r'.code.toByte(),
            '\n'.code.toByte(),
        )
        while (matched < terminal.size && bytes.size < MAX_HANDSHAKE_BYTES) {
            val value = input.read()
            check(value >= 0) { "Peer closed during WebSocket handshake" }
            val byte = value.toByte()
            bytes += byte
            matched = when {
                byte == terminal[matched] -> matched + 1
                byte == terminal[0] -> 1
                else -> 0
            }
        }
        check(matched == terminal.size) { "WebSocket handshake headers were too large" }
        return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private fun writePayloadLength(sink: BufferedOutputStream, length: Long) {
        require(length >= 0)
        when {
            length <= 125 -> sink.write(length.toInt())
            length <= 0xffff -> {
                sink.write(126)
                sink.write((length ushr 8).toInt())
                sink.write(length.toInt())
            }
            else -> {
                sink.write(127)
                for (shift in 56 downTo 0 step 8) sink.write((length ushr shift).toInt())
            }
        }
    }

    private companion object {
        const val MAX_HANDSHAKE_BYTES = 16 * 1_024
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}

package org.nanokvm.protocol

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
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
import org.junit.runner.RunWith

/**
 * Device-side availability characterization for OkHttp's unavoidable first WebSocket allocation.
 *
 * Memory values are emitted as evidence rather than asserted as a universal budget: PSS depends on
 * the device/runtime and an emulator cannot establish a production threshold. Fragment delivery,
 * full pre-callback accumulation, and callback size are deterministic assertions.
 */
@RunWith(AndroidJUnit4::class)
class WebSocketIngressMemoryInstrumentedTest {
    @Test
    fun slowEightMiBMessageRecordsHeapAndPssBeforeListenerDelivery() {
        RawIngressPeer().use { peer ->
            val opened = CountDownLatch(1)
            val delivered = CountDownLatch(1)
            val receivedSize = AtomicReference<Int?>()
            val failure = AtomicReference<Throwable?>()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            val socket = client.newWebSocket(
                Request.Builder().url(peer.url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        receivedSize.set(bytes.size)
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
                assertTrue(peer.handshakeComplete.await(3, TimeUnit.SECONDS))
                assertTrue(opened.await(3, TimeUnit.SECONDS))
                forceCollection()
                val samples = mutableListOf(memorySample(0))
                val fragment = ByteArray(FRAGMENT_BYTES) { 0x5a }

                repeat(FRAGMENT_COUNT) { index ->
                    peer.sendFragment(
                        opcode = if (index == 0) BINARY_OPCODE else CONTINUATION_OPCODE,
                        final = false,
                        payload = fragment,
                    )
                    val accumulated = (index + 1L) * fragment.size
                    assertEquals(accumulated, awaitBufferedBytes(socket, accumulated))
                    assertFalse(
                        "OkHttp delivered a fragmented message before FIN.",
                        delivered.await(FRAGMENT_DELAY_MS, TimeUnit.MILLISECONDS),
                    )
                    if ((index + 1) % SAMPLE_EVERY_FRAGMENTS == 0) {
                        samples += memorySample(accumulated)
                    }
                }

                peer.sendFragment(CONTINUATION_OPCODE, final = true, payload = byteArrayOf())
                assertTrue("Complete fragmented input was not delivered.", delivered.await(5, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertEquals(TOTAL_MESSAGE_BYTES, receivedSize.get())
                samples += memorySample(TOTAL_MESSAGE_BYTES.toLong())
                emitEvidence(samples)
            } finally {
                socket.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun awaitBufferedBytes(webSocket: WebSocket, expected: Long): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
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

    private fun memorySample(accumulatedBytes: Long): IngressMemorySample {
        val runtime = Runtime.getRuntime()
        return IngressMemorySample(
            accumulatedBytes = accumulatedBytes,
            javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            pssKilobytes = Debug.getPss(),
        )
    }

    private fun forceCollection() {
        Runtime.getRuntime().gc()
        Thread.sleep(100)
    }

    private fun emitEvidence(samples: List<IngressMemorySample>) {
        val evidence = samples.joinToString(separator = ";") { sample ->
            "input=${sample.accumulatedBytes},heap=${sample.javaHeapBytes},pssKb=${sample.pssKilobytes}"
        }
        Log.i(EVIDENCE_TAG, evidence)
        println("$EVIDENCE_TAG $evidence")
    }

    private data class IngressMemorySample(
        val accumulatedBytes: Long,
        val javaHeapBytes: Long,
        val pssKilobytes: Long,
    )

    private companion object {
        const val CONTINUATION_OPCODE = 0x0
        const val BINARY_OPCODE = 0x2
        const val FRAGMENT_BYTES = 128 * 1_024
        const val FRAGMENT_COUNT = 64
        const val SAMPLE_EVERY_FRAGMENTS = 16
        const val TOTAL_MESSAGE_BYTES = FRAGMENT_BYTES * FRAGMENT_COUNT
        const val FRAGMENT_DELAY_MS = 20L
        const val EVIDENCE_TAG = "NanoKvmIngressMemory"
    }
}

/** Minimal uncompressed RFC 6455 loopback peer with caller-controlled fragment boundaries. */
private class RawIngressPeer : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "android-raw-websocket-ingress").apply { isDaemon = true }
    }
    private val accepted = AtomicReference<Socket?>()
    private val output = AtomicReference<BufferedOutputStream?>()
    val handshakeComplete = CountDownLatch(1)
    val url: String = "http://127.0.0.1:${server.localPort}/hostile"

    init {
        executor.execute {
            val socket = server.accept().apply { soTimeout = 5_000 }
            accepted.set(socket)
            val input = BufferedInputStream(socket.getInputStream())
            val headers = readHttpHeaders(input)
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
            sink.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n")
                    .toByteArray(StandardCharsets.ISO_8859_1),
            )
            sink.flush()
            output.set(sink)
            handshakeComplete.countDown()
        }
    }

    @Synchronized
    fun sendFragment(opcode: Int, final: Boolean, payload: ByteArray) {
        check(handshakeComplete.await(3, TimeUnit.SECONDS)) { "WebSocket handshake did not complete" }
        val sink = checkNotNull(output.get())
        sink.write((if (final) 0x80 else 0) or opcode)
        when {
            payload.size <= 125 -> sink.write(payload.size)
            payload.size <= 0xffff -> {
                sink.write(126)
                sink.write(payload.size ushr 8)
                sink.write(payload.size)
            }
            else -> {
                sink.write(127)
                repeat(4) { sink.write(0) }
                sink.write(payload.size ushr 24)
                sink.write(payload.size ushr 16)
                sink.write(payload.size ushr 8)
                sink.write(payload.size)
            }
        }
        sink.write(payload)
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
            matched = if (byte == terminal[matched]) {
                matched + 1
            } else if (byte == terminal[0]) {
                1
            } else {
                0
            }
        }
        check(matched == terminal.size) { "WebSocket handshake headers were too large" }
        return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private companion object {
        const val MAX_HANDSHAKE_BYTES = 16 * 1_024
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}

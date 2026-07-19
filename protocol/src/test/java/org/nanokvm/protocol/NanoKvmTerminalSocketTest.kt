package org.nanokvm.protocol

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class NanoKvmTerminalSocketTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient
    private val sockets = mutableListOf<NanoKvmTerminalSocket>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("terminal-token"),
        )
    }

    @After
    fun tearDown() {
        sockets.forEach(NanoKvmTerminalSocket::close)
        client.close()
        server.shutdown()
    }

    @Test
    fun `terminal handshake input resize serial command and binary output match 2_4_3`() {
        val peer = RecordingTerminalPeer(expectedMessages = 3)
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        val terminal = newTerminal()

        assertTrue(terminal.connect())
        assertTrue(peer.opened.await(2, TimeUnit.SECONDS))
        awaitState(terminal) { it is NanoKvmTerminalConnectionState.Connected }

        val handshake = server.takeRequest(2, TimeUnit.SECONDS)
        requireNotNull(handshake)
        assertEquals("/api/vm/terminal", handshake.path)
        assertEquals("nano-kvm-token=terminal-token", handshake.getHeader("Cookie"))
        assertEquals(
            "ws://example.test/api/vm/terminal",
            NanoKvmEndpoint.parse("http://example.test").webSocketUrl("/api/vm/terminal"),
        )
        assertEquals(
            "wss://example.test/api/vm/terminal",
            NanoKvmEndpoint.parse("https://example.test").webSocketUrl("/api/vm/terminal"),
        )

        assertTrue(terminal.sendInput("printf ok\r"))
        assertTrue(terminal.resize(NanoKvmTerminalSize(rows = 42, columns = 120)))
        val serial = NanoKvmSerialConfiguration(
            port = NanoKvmSerialPort.TTY_S2,
            baud = NanoKvmSerialBaud.B921600,
            parity = NanoKvmSerialParity.ODD,
            flowControl = NanoKvmSerialFlowControl.HARDWARE,
            dataBits = NanoKvmSerialDataBits.SEVEN,
            stopBits = NanoKvmSerialStopBits.TWO,
        )
        assertTrue(terminal.startSerial(serial))
        assertTrue(peer.messages.await(2, TimeUnit.SECONDS))

        assertEquals(
            listOf(
                "printf ok\r",
                "picocom /dev/ttyS2 --baud 921600 --parity odd --flow hard " +
                    "--databits 7 --stopbits 2\r",
            ),
            peer.textFrames.toList(),
        )
        val resizeFrame = peer.binaryFrames.single().utf8()
        assertEquals("""{"rows":42,"cols":120}""", resizeFrame)
        val resize = Json.parseToJsonElement(resizeFrame).jsonObject
        assertEquals(42, resize.getValue("rows").jsonPrimitive.int)
        assertEquals(120, resize.getValue("cols").jsonPrimitive.int)

        runBlocking {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) {
                    terminal.events.filterIsInstance<NanoKvmTerminalEvent.Output>().first()
                }
            }
            val source = byteArrayOf(0x1b, '['.code.toByte(), '2'.code.toByte(), 'J'.code.toByte())
            assertTrue(requireNotNull(peer.webSocket.get()).send(source.toByteString()))
            val event = pending.await()
            assertArrayEquals(source, event.copyBytes())
            val callerCopy = event.copyBytes()
            callerCopy[0] = 0
            assertEquals(0x1b, event.copyBytes()[0].toInt())
        }

        terminal.disconnect()
        assertTrue(peer.closing.await(2, TimeUnit.SECONDS))
        assertEquals(1000, peer.closeCode.get())
        awaitState(terminal) { it is NanoKvmTerminalConnectionState.Disconnected }
    }

    @Test
    fun `serial defaults and exit sequence are sent once before delayed close`() = runBlocking {
        val peer = RecordingTerminalPeer(expectedMessages = 2)
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        val terminal = newTerminal()
        assertTrue(terminal.connect())
        assertTrue(peer.opened.await(2, TimeUnit.SECONDS))
        awaitState(terminal) { it is NanoKvmTerminalConnectionState.Connected }

        assertTrue(terminal.startSerial())
        assertTrue(terminal.exitSerialAndDisconnect())
        assertFalse(terminal.exitSerialAndDisconnect())
        assertTrue(peer.messages.await(2, TimeUnit.SECONDS))
        assertTrue(peer.closing.await(2, TimeUnit.SECONDS))

        assertEquals(
            listOf(
                "picocom /dev/ttyS1 --baud 115200 --parity none --flow none " +
                    "--databits 8 --stopbits 1\r",
                "\u0001\u0018",
            ),
            peer.textFrames.toList(),
        )
        assertEquals(2, peer.textFrames.size)
        val delayMillis = TimeUnit.NANOSECONDS.toMillis(
            peer.closingAtNanos.get() - peer.exitAtNanos.get(),
        )
        assertTrue("Close was not delayed after picocom exit: ${delayMillis}ms", delayMillis >= 70)
        Unit
    }

    @Test
    fun `serial port and terminal size validation reject injection and out of range values`() {
        assertEquals("/dev/ttyS1", NanoKvmSerialPort.TTY_S1.value)
        assertEquals("/dev/ttyUSB_0-aux.1", NanoKvmSerialPort.parse("/dev/ttyUSB_0-aux.1").value)
        listOf(
            "ttyS1",
            "/dev/ttyS1 ",
            "/dev/ttyS1;reboot",
            "/dev/tty S1",
            "/dev/../etc/passwd",
            "/dev/..",
            "/dev/a/b",
            "/dev/${"x".repeat(MAX_SERIAL_PORT_UTF8_BYTES)}",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                NanoKvmSerialPort.parse(value)
            }
        }
        listOf(
            { NanoKvmTerminalSize(0, 80) },
            { NanoKvmTerminalSize(24, 0) },
            { NanoKvmTerminalSize(UShort.MAX_VALUE.toInt() + 1, 80) },
            { NanoKvmTerminalSize(24, UShort.MAX_VALUE.toInt() + 1) },
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
        assertEquals(
            listOf(
                50, 75, 110, 134, 150, 200, 300, 600, 1200, 1800, 2400, 4800, 9600,
                19200, 38400, 57600, 115200, 230400, 460800, 500000, 576000, 921600,
                1000000, 1152000, 1500000, 2000000, 2500000, 3000000, 3500000,
                4000000,
            ),
            NanoKvmSerialBaud.entries.map { it.wireValue },
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `text and oversized server frames close with protocol-specific codes`() {
        val textPeer = RecordingTerminalPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(textPeer))
        val textTerminal = newTerminal()
        assertTrue(textTerminal.connect())
        assertTrue(textPeer.opened.await(2, TimeUnit.SECONDS))
        awaitState(textTerminal) { it is NanoKvmTerminalConnectionState.Connected }
        runBlocking {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) {
                    textTerminal.events
                        .filterIsInstance<NanoKvmTerminalEvent.ProtocolViolation>()
                        .first()
                }
            }
            assertTrue(requireNotNull(textPeer.webSocket.get()).send("not binary PTY output"))
            assertEquals("terminal server sent a text frame", pending.await().reason)
        }
        assertTrue(textPeer.closing.await(2, TimeUnit.SECONDS))
        assertEquals(1003, textPeer.closeCode.get())
        awaitState(textTerminal) { it is NanoKvmTerminalConnectionState.Disconnected }

        val largePeer = RecordingTerminalPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(largePeer))
        val largeTerminal = newTerminal()
        assertTrue(largeTerminal.connect())
        assertTrue(largePeer.opened.await(2, TimeUnit.SECONDS))
        awaitState(largeTerminal) { it is NanoKvmTerminalConnectionState.Connected }
        runBlocking {
            val accepted = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) {
                    largeTerminal.events.filterIsInstance<NanoKvmTerminalEvent.Output>().first()
                }
            }
            val boundary = ByteArray(MAX_TERMINAL_SERVER_CHUNK_BYTES) { 7 }
            assertTrue(requireNotNull(largePeer.webSocket.get()).send(boundary.toByteString()))
            assertEquals(MAX_TERMINAL_SERVER_CHUNK_BYTES, accepted.await().size)

            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) {
                    largeTerminal.events
                        .filterIsInstance<NanoKvmTerminalEvent.ProtocolViolation>()
                        .first()
                }
            }
            val oversized = ByteArray(MAX_TERMINAL_SERVER_CHUNK_BYTES + 1)
            assertTrue(requireNotNull(largePeer.webSocket.get()).send(oversized.toByteString()))
            assertEquals("terminal output chunk too large", pending.await().reason)
        }
        assertTrue(largePeer.closing.await(2, TimeUnit.SECONDS))
        assertEquals(1009, largePeer.closeCode.get())
    }

    @Test
    fun `input is never queued replayed or automatically reconnected`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val terminal = newTerminal()
        assertFalse(terminal.sendInput("must not queue"))
        assertFalse(terminal.resize(NanoKvmTerminalSize(24, 80)))
        assertTrue(terminal.connect())
        awaitState(terminal) { it is NanoKvmTerminalConnectionState.Failed }
        val failed = terminal.state.value as NanoKvmTerminalConnectionState.Failed
        assertEquals(1L, failed.generation)
        assertEquals(503, failed.httpStatus)
        Thread.sleep(200)
        assertEquals(1, server.requestCount)
        assertFalse(terminal.sendInput("must not replay"))

        val peer = RecordingTerminalPeer(expectedMessages = 1)
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        assertTrue(terminal.connect())
        assertTrue(peer.opened.await(2, TimeUnit.SECONDS))
        awaitState(terminal) { it is NanoKvmTerminalConnectionState.Connected }
        val connected = terminal.state.value as NanoKvmTerminalConnectionState.Connected
        assertNotEquals(failed.generation, connected.generation)
        Thread.sleep(100)
        assertTrue(peer.textFrames.isEmpty())
        assertTrue(peer.binaryFrames.isEmpty())
        assertTrue(terminal.sendInput("only explicit input"))
        assertTrue(peer.messages.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("only explicit input"), peer.textFrames.toList())
        assertEquals(0, client.transport.pingIntervalMillis)
    }

    @Test
    fun `client input cap and disposed lifecycle fail locally`() {
        val terminal = newTerminal()
        assertTrue("é".repeat(MAX_TERMINAL_CLIENT_TEXT_UTF8_BYTES / 2).hasBoundedUtf8Length(
            MAX_TERMINAL_CLIENT_TEXT_UTF8_BYTES,
        ))
        assertFalse("é".repeat(MAX_TERMINAL_CLIENT_TEXT_UTF8_BYTES / 2 + 1).hasBoundedUtf8Length(
            MAX_TERMINAL_CLIENT_TEXT_UTF8_BYTES,
        ))
        assertThrows(IllegalArgumentException::class.java) {
            terminal.sendInput("x".repeat(MAX_TERMINAL_CLIENT_TEXT_UTF8_BYTES + 1))
        }
        terminal.close()
        assertTrue(terminal.state.value is NanoKvmTerminalConnectionState.Disconnected)
        assertThrows(IllegalStateException::class.java) { terminal.connect() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `terminal refuses to open without an authenticated cookie`() {
        client.forgetSession()
        val terminal = newTerminal()
        assertThrows(AuthenticationExpiredException::class.java) { terminal.connect() }
        assertEquals(0, server.requestCount)
        assertTrue(terminal.state.value is NanoKvmTerminalConnectionState.Disconnected)
    }

    @Test
    fun `terminal handshake 401 expires the local session without reconnect`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val terminal = newTerminal()
        assertTrue(terminal.connect())
        awaitState(terminal) { it is NanoKvmTerminalConnectionState.Failed }
        val failed = terminal.state.value as NanoKvmTerminalConnectionState.Failed
        assertEquals(401, failed.httpStatus)
        assertTrue(failed.cause is AuthenticationExpiredException)
        assertNull(client.tokenStore.read())
        assertThrows(AuthenticationExpiredException::class.java) { terminal.connect() }
        Thread.sleep(100)
        assertEquals(1, server.requestCount)
    }

    private fun newTerminal(): NanoKvmTerminalSocket = client.newTerminalSocket().also(sockets::add)

    private fun awaitState(
        terminal: NanoKvmTerminalSocket,
        predicate: (NanoKvmTerminalConnectionState) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate(terminal.state.value) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("Terminal state did not converge: ${terminal.state.value}", predicate(terminal.state.value))
    }

    private class RecordingTerminalPeer(
        expectedMessages: Int = 0,
    ) : WebSocketListener() {
        val webSocket = AtomicReference<WebSocket?>()
        val opened = CountDownLatch(1)
        val messages = CountDownLatch(expectedMessages)
        val closing = CountDownLatch(1)
        val textFrames = ConcurrentLinkedQueue<String>()
        val binaryFrames = ConcurrentLinkedQueue<ByteString>()
        val closeCode = AtomicInteger(-1)
        val exitAtNanos = AtomicLong(0)
        val closingAtNanos = AtomicLong(0)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            this.webSocket.set(webSocket)
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            textFrames += text
            if (text == "\u0001\u0018") exitAtNanos.set(System.nanoTime())
            messages.countDown()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            binaryFrames += bytes
            messages.countDown()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode.set(code)
            closingAtNanos.set(System.nanoTime())
            closing.countDown()
            webSocket.close(code, reason)
        }
    }
}

package org.nanokvm.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidTextEditTest {
    @Test
    fun `replacement sends all deletes before corrected text and releases every key`() {
        InputFixture().use { fixture ->
            assertEquals(HidTextEditResult.SUBMITTED, fixture.input.sendTextEdit(3, "the "))
            val expectedKeys = listOf(
                HidUsage.BACKSPACE, HidUsage.BACKSPACE, HidUsage.BACKSPACE,
                HidUsage.T, HidUsage.H, HidUsage.E, HidUsage.SPACE,
            )
            assertEquals(expectedKeys.size * 2, fixture.socket.frames.size)
            expectedKeys.forEachIndexed { index, key ->
                assertArrayEquals(
                    HidKeyboardReport.create(keys = listOf(key)).toWireFrame(),
                    fixture.socket.frames[index * 2],
                )
                assertArrayEquals(HidKeyboardReport.released().toWireFrame(), fixture.socket.frames[index * 2 + 1])
            }
        }
    }

    @Test
    fun `unsupported corrected text rejects every delete and insertion report`() {
        InputFixture().use { fixture ->
            for (text in listOf("don’t ", "café", "a😃b")) {
                assertEquals(HidTextEditResult.UNSUPPORTED_TEXT, fixture.input.sendTextEdit(4, text))
            }
            assertTrue(fixture.socket.frames.isEmpty())
            val committed = fixture.input.sendCommittedText("a😃b")
            assertEquals(0, committed.sentKeystrokes)
            assertEquals(listOf(UnsupportedCodePoint(1, 0x1F603)), committed.unsupported)
            assertTrue(fixture.socket.frames.isEmpty())
        }
    }

    @Test
    fun `unbounded ranges and intentional modifiers cannot turn correction into shortcuts`() {
        InputFixture().use { fixture ->
            assertEquals(HidTextEditResult.INVALID_RANGE, fixture.input.sendTextEdit(-1, "x"))
            assertEquals(HidTextEditResult.INVALID_RANGE, fixture.input.sendTextEdit(257, "x"))
            assertEquals(HidTextEditResult.INVALID_RANGE, fixture.input.sendTextEdit(1, "x".repeat(4_097)))
            for (modifier in HidModifier.entries) {
                assertEquals(
                    HidTextEditResult.MODIFIER_CONFLICT,
                    fixture.input.sendTextEdit(1, "word", heldModifiers = setOf(modifier)),
                )
            }
            assertTrue(fixture.socket.frames.isEmpty())
        }
    }

    @Test
    fun `embedded controls cannot delete beyond declared range or move host focus`() {
        InputFixture().use { fixture ->
            for (text in listOf("x\by", "x\ty", "x\u0000y", "x\u007fy")) {
                assertEquals(HidTextEditResult.UNSUPPORTED_TEXT, fixture.input.sendTextEdit(2, text))
            }
            assertTrue(fixture.socket.frames.isEmpty())
        }
    }

    @Test
    fun `failed delete release stops before insertion and attempts only a safety release`() {
        InputFixture().use { fixture ->
            fixture.socket.rejectAttempt = 2
            assertEquals(HidTextEditResult.UNKNOWN, fixture.input.sendTextEdit(1, "replacement"))
            assertEquals(3, fixture.socket.frames.size)
            assertArrayEquals(
                HidKeyboardReport.create(keys = listOf(HidUsage.BACKSPACE)).toWireFrame(),
                fixture.socket.frames[0],
            )
            assertArrayEquals(HidKeyboardReport.released().toWireFrame(), fixture.socket.frames[1])
            assertArrayEquals(HidKeyboardReport.released().toWireFrame(), fixture.socket.frames[2])
        }
    }

    @Test
    fun `context invalidation before dispatch is stale and during dispatch stops after a key pair`() {
        InputFixture().use { fixture ->
            assertEquals(HidTextEditResult.STALE, fixture.input.sendTextEdit(1, "x", isCurrent = { false }))
            assertTrue(fixture.socket.frames.isEmpty())
            var valid = true
            fixture.socket.afterSend = { if (fixture.socket.frames.size == 2) valid = false }
            assertEquals(HidTextEditResult.UNKNOWN, fixture.input.sendTextEdit(1, "x", isCurrent = { valid }))
            assertEquals(2, fixture.socket.frames.size)
            assertArrayEquals(HidKeyboardReport.released().toWireFrame(), fixture.socket.frames.last())
        }
    }

    @Test
    fun `UK replacement and embedded line break retain selected physical mapping`() {
        InputFixture().use { fixture ->
            assertEquals(
                HidTextEditResult.SUBMITTED,
                fixture.input.sendTextEdit(
                    0, "@\n", layout = KeyboardLayout.UK,
                    lineBreakMode = CommittedTextLineBreakMode.SHIFT_ENTER,
                ),
            )
            assertArrayEquals(
                HidKeyboardReport.create(setOf(HidModifier.LEFT_SHIFT), listOf(HidUsage.APOSTROPHE)).toWireFrame(),
                fixture.socket.frames[0],
            )
            assertArrayEquals(
                HidKeyboardReport.create(setOf(HidModifier.LEFT_SHIFT), listOf(HidUsage.ENTER)).toWireFrame(),
                fixture.socket.frames[2],
            )
        }
    }

    private class InputFixture : AutoCloseable {
        private val client = OkHttpClient()
        val input = NanoKvmInputSocket(
            NanoKvmEndpoint.parse("https://nanokvm.invalid"), client, InMemorySessionTokenStore(), 10_000,
        )
        val socket = RecordingSocket()

        init {
            NanoKvmInputSocket::class.java.getDeclaredField("socket").apply {
                isAccessible = true
                set(input, socket)
            }
            @Suppress("UNCHECKED_CAST")
            val state = NanoKvmInputSocket::class.java.getDeclaredField("mutableState").let {
                it.isAccessible = true
                it.get(input) as MutableStateFlow<InputConnectionState>
            }
            state.value = InputConnectionState.Connected
        }

        override fun close() {
            input.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private class RecordingSocket : WebSocket {
        val frames = mutableListOf<ByteArray>()
        var rejectAttempt: Int? = null
        var afterSend: () -> Unit = {}
        override fun request(): Request = Request.Builder().url("https://nanokvm.invalid").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = error("Expected binary HID reports")
        override fun send(bytes: ByteString): Boolean {
            frames += bytes.toByteArray()
            afterSend()
            return frames.size != rejectAttempt
        }
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }
}

package org.nanokvm.mobile.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.HidKeyboardReport
import org.nanokvm.protocol.HidModifier
import org.nanokvm.protocol.HidUsage
import org.nanokvm.protocol.InputConnectionState
import org.nanokvm.protocol.NanoKvmClient
import org.nanokvm.protocol.NanoKvmEndpoint
import org.nanokvm.protocol.NanoKvmInputSocket

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTextEditBackendTest {
    @Test
    fun `text edit is queued before following Enter and replacement is contiguous`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val context = RemoteTextEditContext()
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(2, "he ", KeyboardLayout.Us, context)
            }
            fixture.backend.tapKey(RemoteKey.Enter, context)
            advanceUntilIdle()
            assertEquals(RemoteTextEditResult.Submitted, edit.await())
            val expected = listOf(
                HidUsage.BACKSPACE, HidUsage.BACKSPACE, HidUsage.H, HidUsage.E, HidUsage.SPACE, HidUsage.ENTER,
            )
            assertEquals(expected.size * 2, fixture.socket.frames.size)
            expected.forEachIndexed { index, key ->
                assertArrayEquals(
                    HidKeyboardReport.create(keys = listOf(key)).toWireFrame(),
                    fixture.socket.frames[index * 2],
                )
                assertArrayEquals(HidKeyboardReport.released().toWireFrame(), fixture.socket.frames[index * 2 + 1])
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `unsupported replacement invalidates dependent queued edits before any deletion`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val context = RemoteTextEditContext()
            val unsupported = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(4, "don’t", KeyboardLayout.Us, context)
            }
            val dependent = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(5, "don't ", KeyboardLayout.Us, context)
            }
            fixture.backend.tapKey(RemoteKey.Enter, context)
            advanceUntilIdle()
            assertEquals(
                RemoteTextEditResult.Rejected(RemoteTextEditRejection.UnsupportedText), unsupported.await(),
            )
            assertEquals(RemoteTextEditResult.Stale, dependent.await())
            assertFalse(context.isValid)
            assertTrue(fixture.socket.frames.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `invalidated context and replaced command epoch cannot dispatch queued edits`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val context = RemoteTextEditContext()
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(1, "x", KeyboardLayout.Us, context)
            }
            context.invalidate()
            advanceUntilIdle()
            assertEquals(RemoteTextEditResult.Stale, edit.await())

            val freshContext = RemoteTextEditContext()
            val oldEpoch = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(1, "x", KeyboardLayout.Us, freshContext)
            }
            fixture.backend.setField("commandAcceptanceEpoch", 1L)
            advanceUntilIdle()
            assertEquals(RemoteTextEditResult.Stale, oldEpoch.await())
            assertFalse(freshContext.isValid)
            assertTrue(fixture.socket.frames.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `partial send reports unknown and never replays or starts its dependent correction`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.socket.rejectAttempt = 2
            val context = RemoteTextEditContext()
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(1, "word", KeyboardLayout.Us, context)
            }
            val dependent = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(4, "words", KeyboardLayout.Us, context)
            }
            advanceUntilIdle()
            assertEquals(RemoteTextEditResult.Unknown, edit.await())
            assertEquals(RemoteTextEditResult.Stale, dependent.await())
            assertFalse(context.isValid)
            assertEquals(3, fixture.socket.frames.size)
            assertArrayEquals(
                HidKeyboardReport.create(keys = listOf(HidUsage.BACKSPACE)).toWireFrame(), fixture.socket.frames[0],
            )
            assertArrayEquals(HidKeyboardReport.released().toWireFrame(), fixture.socket.frames[2])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `intentional Ctrl remains held and cannot modify a text replacement`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.backend.key(RemoteKey.Control, true)
            val context = RemoteTextEditContext()
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(1, "word", KeyboardLayout.Us, context)
            }
            advanceUntilIdle()
            assertEquals(
                RemoteTextEditResult.Rejected(RemoteTextEditRejection.ModifierConflict), edit.await(),
            )
            assertEquals(1, fixture.socket.frames.size)
            assertArrayEquals(
                HidKeyboardReport.create(setOf(HidModifier.LEFT_CONTROL)).toWireFrame(), fixture.socket.frames[0],
            )
            assertFalse(context.isValid)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `canceling caller invalidates queued edit synchronously`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val context = RemoteTextEditContext()
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(1, "x", KeyboardLayout.Us, context)
            }
            edit.cancel()
            assertFalse(context.isValid)
            advanceUntilIdle()
            assertTrue(fixture.socket.frames.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `closed command acceptance returns stale without leaving caller suspended`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.backend.setField("acceptingCommands", false)
            val context = RemoteTextEditContext()
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(1, "x", KeyboardLayout.Us, context)
            }
            assertTrue("No queue slot should mean an immediate result", edit.isCompleted)
            assertEquals(RemoteTextEditResult.Stale, edit.await())
            assertFalse(context.isValid)
            assertTrue(fixture.socket.frames.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `soft key always releases after its down even when context invalidates during send`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val context = RemoteTextEditContext()
            fixture.socket.afterSend = { context.invalidate() }
            fixture.backend.tapKey(RemoteKey.Enter, context)
            advanceUntilIdle()
            assertEquals(2, fixture.socket.frames.size)
            assertArrayEquals(
                HidKeyboardReport.create(keys = listOf(HidUsage.ENTER)).toWireFrame(), fixture.socket.frames[0],
            )
            assertArrayEquals(HidKeyboardReport.released().toWireFrame(), fixture.socket.frames[1])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `physical Enter down cannot overtake rejected composition but up still releases`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val context = RemoteTextEditContext()
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.applyTextEdit(2, "café", KeyboardLayout.Us, context)
            }
            fixture.backend.key(RemoteKey.Enter, true, context)
            fixture.backend.key(RemoteKey.Enter, false, context)
            advanceUntilIdle()
            assertEquals(
                RemoteTextEditResult.Rejected(RemoteTextEditRejection.UnsupportedText), edit.await(),
            )
            assertEquals(1, fixture.socket.frames.size)
            assertArrayEquals(HidKeyboardReport.released().toWireFrame(), fixture.socket.frames.single())
        } finally {
            fixture.close()
        }
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        private val client = NanoKvmClient.create(NanoKvmEndpoint.parse("https://nanokvm.invalid"))
        private val input = client.newInputSocket()
        val socket = RecordingSocket()
        val backend = NanoKvmConsoleBackend(dispatcher, ReconnectPolicy(listOf(0L), jitterFraction = 0.0))

        init {
            input.setField("socket", socket)
            @Suppress("UNCHECKED_CAST")
            val inputState = input.field("mutableState") as MutableStateFlow<InputConnectionState>
            inputState.value = InputConnectionState.Connected
            backend.setField("input", input)
            backend.setField("acceptingCommands", true)
            @Suppress("UNCHECKED_CAST")
            val session = backend.field("mutableSession") as MutableStateFlow<BackendSession>
            session.value = BackendSession(connection = ConnectionState.Connected)
        }

        suspend fun close() {
            backend.closeAndAwait()
            input.close()
            client.close()
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

private fun Any.setField(name: String, value: Any?) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private fun Any.field(name: String): Any? =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

package org.nanokvm.protocol

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InputMessageBoundsTest {
    @Test
    fun `binary input-server message at boundary is copied into event`() {
        val payload = ByteArray(MAX_INPUT_SERVER_MESSAGE_BYTES) { (it and 0xff).toByte() }

        val event = boundedInputBinaryEvent(payload.toByteString())

        assertNotNull(event)
        event as InputServerEvent.Binary
        assertArrayEquals(payload, event.value)
    }

    @Test
    fun `oversized binary input-server message is rejected before array copy`() {
        val payload = ByteArray(MAX_INPUT_SERVER_MESSAGE_BYTES + 1).toByteString()

        assertNull(boundedInputBinaryEvent(payload))
    }

    @Test
    fun `text input-server limit is measured in UTF-8 bytes`() {
        val boundary = "€".repeat(MAX_INPUT_SERVER_MESSAGE_BYTES / 3) + "a"
        assertEquals(MAX_INPUT_SERVER_MESSAGE_BYTES, boundary.encodeToByteArray().size)

        val event = boundedInputTextEvent(boundary)
        assertNotNull(event)
        assertEquals(boundary, (event as InputServerEvent.Text).value)
        assertNull(boundedInputTextEvent(boundary + "a"))
    }
}

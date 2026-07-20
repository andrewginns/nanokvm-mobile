package org.nanokvm.protocol

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMessageBoundsTest {
    @Test
    fun `binary input-server message at boundary is accepted without a second payload copy`() {
        val payload = ByteArray(MAX_INPUT_SERVER_MESSAGE_BYTES) { (it and 0xff).toByte() }

        assertTrue(isInputBinaryMessageWithinLimit(payload.toByteString()))
    }

    @Test
    fun `oversized binary input-server message is rejected before array copy`() {
        val payload = ByteArray(MAX_INPUT_SERVER_MESSAGE_BYTES + 1).toByteString()

        assertFalse(isInputBinaryMessageWithinLimit(payload))
    }

    @Test
    fun `text input-server limit is measured in UTF-8 bytes`() {
        val boundary = "€".repeat(MAX_INPUT_SERVER_MESSAGE_BYTES / 3) + "a"
        assertEquals(MAX_INPUT_SERVER_MESSAGE_BYTES, boundary.encodeToByteArray().size)

        assertTrue(isInputTextMessageWithinLimit(boundary))
        assertFalse(isInputTextMessageWithinLimit(boundary + "a"))
    }
}

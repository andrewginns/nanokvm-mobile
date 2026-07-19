package org.nanokvm.video

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class H264FrameParserTest {
    @Test
    fun parsesKeyFlagLittleEndianTimestampAndAccessUnit() {
        val payload = byteArrayOf(
            1,
            0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
            0x00, 0x00, 0x00, 0x01, 0x65,
        )

        val frame = NanoKvmH264FrameParser.parse(payload)

        assertTrue(frame.isKeyFrame)
        assertEquals(0x0102030405060708L, frame.timestampUs)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65), frame.data)
    }

    @Test
    fun zeroFlagIsDeltaFrame() {
        val payload = ByteArray(10).apply { this[9] = 0x41 }
        assertFalse(NanoKvmH264FrameParser.parse(payload).isKeyFrame)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyAccessUnit() {
        NanoKvmH264FrameParser.parse(ByteArray(9))
    }

    @Test
    fun `WebSocket message at configured access-unit boundary is accepted`() {
        val maxAccessUnitBytes = 4
        val payload = ByteArray(NanoKvmH264FrameParser.HEADER_SIZE + maxAccessUnitBytes).apply {
            this[NanoKvmH264FrameParser.HEADER_SIZE] = 0x65
        }

        val frame = NanoKvmH264FrameParser.parse(payload.toByteString(), maxAccessUnitBytes)

        assertEquals(maxAccessUnitBytes, frame.data.size)
    }

    @Test
    fun `oversized WebSocket message is rejected before array conversion`() {
        val maxAccessUnitBytes = 4
        val payload = ByteArray(NanoKvmH264FrameParser.HEADER_SIZE + maxAccessUnitBytes + 1)

        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmH264FrameParser.parse(payload.toByteString(), maxAccessUnitBytes)
        }
    }

    @Test
    fun `truncated WebSocket envelope is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmH264FrameParser.parse(
                ByteArray(NanoKvmH264FrameParser.HEADER_SIZE).toByteString(),
                maxAccessUnitBytes = 4,
            )
        }
    }

    @Test
    fun `default H264 access-unit limit is four MiB`() {
        assertEquals(4 * 1024 * 1024, NanoKvmH264FrameParser.DEFAULT_MAX_ACCESS_UNIT_BYTES)
        assertEquals(
            NanoKvmH264FrameParser.DEFAULT_MAX_ACCESS_UNIT_BYTES,
            H264DecoderConfig().maxInputSizeBytes,
        )
    }
}

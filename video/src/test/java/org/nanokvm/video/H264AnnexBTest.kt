package org.nanokvm.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AnnexBTest {
    @Test
    fun extractsAndNormalizesThreeAndFourByteParameterSetStartCodes() {
        val accessUnit = byteArrayOf(
            0, 0, 1, 0x67, 0x42, 0x00, 0x1f,
            0, 0, 0, 1, 0x68, 0x01, 0x02,
            0, 0, 1, 0x65, 0x55,
        )

        val result = checkNotNull(H264AnnexB.codecSpecificData(accessUnit))

        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f), result.sps)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68, 0x01, 0x02), result.pps)
    }

    @Test
    fun rejectsAccessUnitWithoutBothParameterSets() {
        val spsAndIdr = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x65, 1)

        assertNull(H264AnnexB.codecSpecificData(spsAndIdr))
    }

    @Test
    fun bootstrapKeepsKeyOutsideLiveQueueAndEmitsConfigBeforeFrame() {
        val bootstrap = H264DecoderBootstrap()
        val delta = H264AccessUnit(false, 1, byteArrayOf(0, 0, 1, 0x41, 1))
        val key = H264AccessUnit(
            true,
            2,
            byteArrayOf(
                0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f,
                0, 0, 0, 1, 0x68, 1,
                0, 0, 0, 1, 0x65, 2,
            ),
        )

        assertFalse(checkNotNull(bootstrap.offer(delta)).accepted)
        assertTrue(checkNotNull(bootstrap.offer(key)).accepted)
        val config = checkNotNull(bootstrap.poll())
        val frame = checkNotNull(bootstrap.poll())

        assertEquals(H264DecoderInput.CODEC_CONFIG, config.flags)
        assertEquals(H264DecoderInput.KEY_FRAME, frame.flags)
        assertEquals(key.timestampUs, frame.timestampUs)
        assertArrayEquals(key.data, frame.data)
        assertNull(bootstrap.poll())
        assertNull(bootstrap.offer(delta))
    }
}

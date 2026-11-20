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
    fun queuesDeltasAfterStagingBootstrapAndDeliversTheirReferencesFirst() {
        val inputs = H264DecoderInputQueue(capacity = 3)
        val key = bootstrapKey()
        val followingDelta = delta(3)

        assertFalse(inputs.offer(delta(1)).accepted)
        assertTrue(inputs.offer(key).accepted)
        assertTrue(inputs.offer(followingDelta).accepted)

        val config = checkNotNull(inputs.poll())
        val frame = checkNotNull(inputs.poll())
        assertEquals(H264DecoderInput.CODEC_CONFIG, config.flags)
        assertEquals(H264DecoderInput.KEY_FRAME, frame.flags)
        assertEquals(key.timestampUs, frame.timestampUs)
        assertArrayEquals(key.data, frame.data)
        assertEquals(followingDelta.timestampUs, inputs.poll()?.timestampUs)
        assertNull(inputs.poll())
    }

    @Test
    fun incompleteBootstrapKeyDoesNotPreventRecoveryAtNextKey() {
        val inputs = H264DecoderInputQueue(capacity = 3)
        val keyWithoutParameterSets = H264AccessUnit(true, 1, byteArrayOf(0, 0, 1, 0x65, 1))

        assertEquals(H264FrameDropReason.MALFORMED_TRANSPORT_FRAME, inputs.offer(keyWithoutParameterSets).dropReason)
        assertFalse(inputs.offer(delta(2)).accepted)
        assertNull(inputs.poll())
        assertTrue(inputs.offer(bootstrapKey()).accepted)
        assertEquals(H264DecoderInput.CODEC_CONFIG, inputs.poll()?.flags)
        assertEquals(H264DecoderInput.KEY_FRAME, inputs.poll()?.flags)
    }

    @Test
    fun drainingStagedKeyDoesNotReopenQueueAfterDroppingDependentFrames() {
        val inputs = H264DecoderInputQueue(capacity = 1)
        inputs.offer(bootstrapKey())
        assertTrue(inputs.offer(delta(3)).accepted)
        assertEquals(H264FrameDropReason.STALE_BACKLOG, inputs.offer(delta(4)).dropReason)
        assertEquals(H264DecoderInput.CODEC_CONFIG, inputs.poll()?.flags)
        assertEquals(H264DecoderInput.KEY_FRAME, inputs.poll()?.flags)
        assertEquals(H264FrameDropReason.AWAITING_KEY_FRAME, inputs.offer(delta(5)).dropReason)
        assertNull(inputs.poll())

        assertTrue(inputs.offer(H264AccessUnit(true, 6, byteArrayOf(0, 0, 1, 0x65, 1))).accepted)
        assertEquals(6L, inputs.poll()?.timestampUs)
        assertTrue(inputs.offer(delta(7)).accepted)
        assertEquals(7L, inputs.poll()?.timestampUs)
    }

    @Test
    fun malformedPacketDiscardsDependentFramesButKeepsBootstrap() {
        val inputs = H264DecoderInputQueue(capacity = 3)
        inputs.offer(bootstrapKey())
        inputs.offer(delta(3))
        assertEquals(1, inputs.discardLiveFrames())
        assertFalse(inputs.offer(delta(5)).accepted)
        assertEquals(H264DecoderInput.CODEC_CONFIG, inputs.poll()?.flags)
        assertEquals(H264DecoderInput.KEY_FRAME, inputs.poll()?.flags)
        assertNull(inputs.poll())
    }

    private fun delta(timestamp: Long) = H264AccessUnit(false, timestamp, byteArrayOf(0, 0, 1, 0x41, 1))

    private fun bootstrapKey() = H264AccessUnit(
        true,
        2,
        byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f,
            0, 0, 0, 1, 0x68, 1,
            0, 0, 0, 1, 0x65, 2,
        ),
    )
}

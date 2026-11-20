package org.nanokvm.video

/** AVC parameter sets in the start-code-prefixed form required by Android MediaCodec. */
data class H264CodecSpecificData(
    val sps: ByteArray,
    val pps: ByteArray,
) {
    fun concatenated(): ByteArray = sps + pps
}

/** Minimal Annex-B parser for extracting the SPS/PPS carried by a NanoKVM key access unit. */
object H264AnnexB {
    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun codecSpecificData(accessUnit: ByteArray): H264CodecSpecificData? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var current = findStartCode(accessUnit, 0) ?: return null

        while (true) {
            val nalStart = current.index + current.length
            val next = findStartCode(accessUnit, nalStart)
            val nalEnd = next?.index ?: accessUnit.size
            if (nalStart < nalEnd) {
                val nalType = accessUnit[nalStart].toInt() and NAL_TYPE_MASK
                if (nalType == NAL_TYPE_SPS && sps == null) {
                    sps = normalizedNal(accessUnit, nalStart, nalEnd)
                } else if (nalType == NAL_TYPE_PPS && pps == null) {
                    pps = normalizedNal(accessUnit, nalStart, nalEnd)
                }
                if (sps != null && pps != null) return H264CodecSpecificData(sps, pps)
            }
            current = next ?: return null
        }
    }

    private fun normalizedNal(bytes: ByteArray, start: Int, end: Int): ByteArray =
        startCode + bytes.copyOfRange(start, end)

    private fun findStartCode(bytes: ByteArray, fromIndex: Int): StartCode? {
        var index = fromIndex.coerceAtLeast(0)
        while (index + 2 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) return StartCode(index, 3)
                if (index + 3 < bytes.size &&
                    bytes[index + 2] == 0.toByte() &&
                    bytes[index + 3] == 1.toByte()
                ) {
                    return StartCode(index, 4)
                }
            }
            index++
        }
        return null
    }

    private data class StartCode(val index: Int, val length: Int)

    private const val NAL_TYPE_MASK = 0x1f
    private const val NAL_TYPE_SPS = 7
    private const val NAL_TYPE_PPS = 8
}

/**
 * Preserves NanoKVM's first SPS/PPS/IDR access unit outside the bounded low-latency queue.
 * MediaCodec receives its codec-config input before that keyframe, even if startup takes longer
 * than the three-frame live queue can tolerate. Both lanes share a lock so a dependent frame
 * cannot be read before its bootstrap keyframe or reopen the queue after a dropped reference.
 */
internal class H264DecoderInputQueue(capacity: Int) {
    private val live = H264FrameQueue(capacity)
    private var stage = Stage.WAITING_FOR_KEY
    private var codecSpecificData: H264CodecSpecificData? = null
    private var keyFrame: H264AccessUnit? = null

    @Synchronized
    fun offer(frame: H264AccessUnit): H264QueueOfferResult {
        if (stage != Stage.WAITING_FOR_KEY) return live.offer(frame)
        if (!frame.isKeyFrame) {
            return H264QueueOfferResult(
                accepted = false,
                droppedFrames = 1,
                dropReason = H264FrameDropReason.AWAITING_KEY_FRAME,
            )
        }

        codecSpecificData = H264AnnexB.codecSpecificData(frame.data)
            ?: return H264QueueOfferResult(
                accepted = false,
                droppedFrames = 1,
                dropReason = H264FrameDropReason.MALFORMED_TRANSPORT_FRAME,
            )
        keyFrame = frame
        stage = Stage.CODEC_CONFIG_PENDING
        live.onBootstrapKeyFrameStaged()
        return H264QueueOfferResult(accepted = true)
    }

    @Synchronized
    fun poll(): H264DecoderInput? = when (stage) {
        Stage.WAITING_FOR_KEY -> null
        Stage.CODEC_CONFIG_PENDING -> {
            stage = Stage.KEY_FRAME_PENDING
            H264DecoderInput(
                data = checkNotNull(codecSpecificData).concatenated(),
                timestampUs = checkNotNull(keyFrame).timestampUs,
                flags = H264DecoderInput.CODEC_CONFIG,
            )
        }
        Stage.KEY_FRAME_PENDING -> {
            val frame = checkNotNull(keyFrame)
            stage = Stage.COMPLETE
            codecSpecificData = null
            keyFrame = null
            H264DecoderInput(
                data = frame.data,
                timestampUs = frame.timestampUs,
                flags = H264DecoderInput.KEY_FRAME,
            )
        }
        Stage.COMPLETE -> live.poll()?.let { frame ->
            H264DecoderInput(
                data = frame.data,
                timestampUs = frame.timestampUs,
                flags = if (frame.isKeyFrame) H264DecoderInput.KEY_FRAME else H264DecoderInput.NONE,
            )
        }
    }

    @Synchronized
    fun discardLiveFrames(): Int = live.clearAndAwaitKeyFrame()

    fun snapshot(): H264QueueSnapshot = live.snapshot()

    @Synchronized
    fun clear() {
        stage = Stage.WAITING_FOR_KEY
        codecSpecificData = null
        keyFrame = null
        live.clearAndAwaitKeyFrame()
    }

    private enum class Stage {
        WAITING_FOR_KEY,
        CODEC_CONFIG_PENDING,
        KEY_FRAME_PENDING,
        COMPLETE,
    }
}

internal data class H264DecoderInput(
    val data: ByteArray,
    val timestampUs: Long,
    val flags: Int,
) {
    companion object {
        const val NONE = 0
        const val KEY_FRAME = 1
        const val CODEC_CONFIG = 2
    }
}

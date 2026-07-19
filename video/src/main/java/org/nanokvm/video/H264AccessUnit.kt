package org.nanokvm.video

import okio.ByteString

/** A single H.264 access unit as emitted by NanoKVM's direct stream. */
data class H264AccessUnit(
    val isKeyFrame: Boolean,
    val timestampUs: Long,
    val data: ByteArray,
)

/** Parses NanoKVM's 9-byte direct-stream envelope without depending on Android APIs. */
object NanoKvmH264FrameParser {
    const val HEADER_SIZE = 9
    const val DEFAULT_MAX_ACCESS_UNIT_BYTES = 4 * 1024 * 1024

    /** Validates the WebSocket message size before making its only [ByteArray] copy. */
    fun parse(
        payload: ByteString,
        maxAccessUnitBytes: Int = DEFAULT_MAX_ACCESS_UNIT_BYTES,
    ): H264AccessUnit {
        require(maxAccessUnitBytes > 0) { "Maximum H.264 access-unit size must be positive" }
        require(payload.size.toLong() <= maxAccessUnitBytes.toLong() + HEADER_SIZE) {
            "NanoKVM H.264 access unit exceeds the $maxAccessUnitBytes-byte limit"
        }
        require(payload.size > HEADER_SIZE) {
            "NanoKVM H.264 frame must contain a 9-byte header and a non-empty access unit"
        }

        var timestampUs = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            timestampUs = timestampUs or
                ((payload[index + 1].toLong() and 0xffL) shl (index * Byte.SIZE_BITS))
        }

        return H264AccessUnit(
            isKeyFrame = payload[0].toInt() != 0,
            timestampUs = timestampUs,
            data = payload.substring(HEADER_SIZE).toByteArray(),
        )
    }

    /**
     * Wire format: key-frame flag, little-endian uint64 timestamp in microseconds, H.264 AU.
     *
     * [IllegalArgumentException] is thrown for a truncated envelope or empty access unit.
     */
    fun parse(payload: ByteArray): H264AccessUnit {
        require(payload.size > HEADER_SIZE) {
            "NanoKVM H.264 frame must contain a 9-byte header and a non-empty access unit"
        }

        var timestampUs = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            timestampUs = timestampUs or
                ((payload[index + 1].toLong() and 0xffL) shl (index * Byte.SIZE_BITS))
        }

        return H264AccessUnit(
            isKeyFrame = payload[0].toInt() != 0,
            timestampUs = timestampUs,
            data = payload.copyOfRange(HEADER_SIZE, payload.size),
        )
    }
}

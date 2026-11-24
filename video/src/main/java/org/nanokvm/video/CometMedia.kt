package org.nanokvm.video

import okio.ByteString

object CometMedia {
    const val START_H264 = """{"event_type":"start","event":{"type":"video","format":"h264"}}"""

    /** Pong (255) and unknown future types are not decoder input. */
    fun frame(bytes: ByteString, timestampUs: Long): H264AccessUnit? {
        if (bytes.size == 0 || bytes[0].toInt() != 1) return null
        require(bytes.size in 3..(2 + NanoKvmH264FrameParser.DEFAULT_MAX_ACCESS_UNIT_BYTES))
        return H264AccessUnit(bytes[1].toInt() != 0, timestampUs, bytes.substring(2).toByteArray())
    }
}

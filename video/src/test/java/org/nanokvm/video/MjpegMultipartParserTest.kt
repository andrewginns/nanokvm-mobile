package org.nanokvm.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class MjpegMultipartParserTest {
    @Test
    fun parsesMultipleContentLengthParts() {
        val first = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
        val second = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 2, 3, 0xff.toByte(), 0xd9.toByte())
        val stream = multipart("frame", first, second)
        val frames = mutableListOf<ByteArray>()

        MjpegMultipartParser("frame").read(ByteArrayInputStream(stream)) {
            frames += it
            true
        }

        assertEquals(2, frames.size)
        assertArrayEquals(first, frames[0])
        assertArrayEquals(second, frames[1])
    }

    @Test
    fun extractsQuotedBoundaryCaseInsensitively() {
        assertEquals(
            "nano-boundary",
            MjpegMultipartParser.boundaryFromContentType(
                "multipart/x-mixed-replace; charset=binary; BOUNDARY=\"nano-boundary\"",
            ),
        )
    }

    @Test(expected = EOFException::class)
    fun rejectsTruncatedJpegPayload() {
        val bytes = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 20\r\n\r\nshort"
            .toByteArray(Charsets.US_ASCII)
        MjpegMultipartParser("frame").read(ByteArrayInputStream(bytes)) { true }
    }

    @Test
    fun callbackCanStopWithoutReadingFollowingFrame() {
        val stream = multipart("frame", byteArrayOf(1), byteArrayOf(2))
        var count = 0
        MjpegMultipartParser("frame").read(ByteArrayInputStream(stream)) {
            count++
            false
        }
        assertEquals(1, count)
    }

    private fun multipart(boundary: String, vararg frames: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        frames.forEachIndexed { index, frame ->
            output.write("--$boundary\r\n".toByteArray())
            output.write("Content-Type: image/jpeg\r\n".toByteArray())
            val lengthName = if (index == 0) "Content-Length" else "content-length"
            output.write("$lengthName: ${frame.size}\r\n\r\n".toByteArray())
            output.write(frame)
            output.write("\r\n".toByteArray())
        }
        output.write("--$boundary--\r\n".toByteArray())
        return output.toByteArray()
    }
}

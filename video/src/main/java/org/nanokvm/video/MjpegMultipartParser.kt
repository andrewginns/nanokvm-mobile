package org.nanokvm.video

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** Content-Length based multipart MJPEG parser, independent of Android and OkHttp. */
class MjpegMultipartParser(
    boundary: String,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val maxHeaderLineBytes: Int = DEFAULT_MAX_HEADER_LINE_BYTES,
) {
    private val boundary = normalizeBoundary(boundary)
    private val delimiter = "--${this.boundary}"
    private val closingDelimiter = "$delimiter--"

    init {
        require(this.boundary.isNotEmpty()) { "MJPEG boundary must not be empty" }
        require(maxFrameBytes > 0) { "Maximum frame size must be positive" }
        require(maxHeaderLineBytes >= 128) { "Maximum header line size is too small" }
    }

    /**
     * Reads frames until EOF, a closing delimiter, or [onFrame] returns false.
     * A part without a valid Content-Length is rejected to keep allocations bounded.
     */
    @Throws(IOException::class)
    fun read(input: InputStream, onFrame: (ByteArray) -> Boolean) {
        while (true) {
            val marker = findNextDelimiter(input) ?: return
            if (marker == closingDelimiter) return

            val headers = readHeaders(input)
            val lengthText = headers["content-length"]
                ?: throw IOException("MJPEG part is missing Content-Length")
            val length = lengthText.toIntOrNull()
                ?: throw IOException("Invalid MJPEG Content-Length: $lengthText")
            if (length !in 1..maxFrameBytes) {
                throw IOException("MJPEG frame size $length is outside 1..$maxFrameBytes")
            }

            val jpeg = ByteArray(length)
            readExactly(input, jpeg)
            if (!onFrame(jpeg)) return
        }
    }

    private fun findNextDelimiter(input: InputStream): String? {
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line == delimiter || line == closingDelimiter) return line
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val line = readAsciiLine(input) ?: throw EOFException("EOF in MJPEG headers")
            if (line.isEmpty()) return result
            if (++count > MAX_HEADER_COUNT) throw IOException("Too many MJPEG part headers")
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("Malformed MJPEG part header: $line")
            val name = line.substring(0, colon).trim().lowercase(Locale.US)
            result[name] = line.substring(colon + 1).trim()
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ByteArray(maxHeaderLineBytes)
        var size = 0
        while (true) {
            val value = input.read()
            if (value == -1) {
                if (size == 0) return null
                return bytes.decodeToString(0, size).removeSuffix("\r")
            }
            if (value == '\n'.code) {
                return bytes.decodeToString(0, size).removeSuffix("\r")
            }
            if (size == bytes.size) throw IOException("MJPEG header line is too long")
            bytes[size++] = value.toByte()
        }
    }

    private fun readExactly(input: InputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val read = input.read(destination, offset, destination.size - offset)
            if (read < 0) throw EOFException("EOF in MJPEG JPEG payload")
            if (read == 0) continue
            offset += read
        }
    }

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_COUNT = 64

        fun boundaryFromContentType(contentType: String?): String? {
            if (contentType == null) return null
            return contentType.split(';')
                .asSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.removeSurrounding("\"")
                ?.let(::normalizeBoundary)
                ?.takeIf(String::isNotEmpty)
        }

        private fun normalizeBoundary(value: String): String =
            value.trim().removeSurrounding("\"").removePrefix("--")
    }
}

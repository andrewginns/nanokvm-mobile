package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Utf8TextBoundsTest {
    @Test
    fun `generic scanner counts one two three and four byte scalars at exact boundaries`() {
        val value = "A\u00A3\u20AC\uD83D\uDE03"

        assertEquals(10, value.utf8SizeAtMost(10))
        assertNull(value.utf8SizeAtMost(9))
        assertEquals(0, "".utf8SizeAtMost(0))
    }

    @Test
    fun `generic scanner matches encoder replacement width for unpaired surrogates`() {
        val values = listOf("\uD800", "\uDC00", "A\uD800B")

        values.forEach { value ->
            val encodedSize = value.encodeToByteArray().size
            assertEquals(encodedSize, value.utf8SizeAtMost(encodedSize))
            assertNull(value.utf8SizeAtMost(encodedSize - 1))
        }
    }

    @Test
    fun `safe scalar scanner permits script whitespace and rejects invalid controls`() {
        val valid = "#!/bin/sh\necho\t'\uD83D\uDE03'\r\n"

        assertEquals(valid.encodeToByteArray().size, valid.safeScalarTextUtf8SizeAtMost(64))
        assertNull("bad\u0000text".safeScalarTextUtf8SizeAtMost(64))
        assertNull("bad\u007ftext".safeScalarTextUtf8SizeAtMost(64))
        assertNull("\uD800".safeScalarTextUtf8SizeAtMost(64))
        assertNull("\uDC00".safeScalarTextUtf8SizeAtMost(64))
    }

    @Test
    fun `large input stops at the configured bound`() {
        val value = "x".repeat(512 * 1_024 + 1)

        assertNull(value.utf8SizeAtMost(512 * 1_024))
        assertNull(value.safeScalarTextUtf8SizeAtMost(512 * 1_024))
    }
}

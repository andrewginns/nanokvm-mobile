package org.nanokvm.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorSurfaceLifecycleTest {
    @Test
    fun `operator approval requires exact destination generation`() {
        val binding = binding(3L)
        val approved = ApprovedOperatorDestination(
            profileId = "office",
            authority = "192.0.2.4",
            sessionGeneration = 3L,
        )

        assertTrue(approved.matches(binding))
        assertFalse(approved.copy(profileId = "other").matches(binding))
        assertFalse(approved.copy(authority = "192.0.2.5").matches(binding))
        assertFalse(approved.copy(sessionGeneration = 4L).matches(binding))
        assertFalse(approved.toString().contains("192.0.2.4"))
    }

    @Test
    fun `operator loads only while visible foreground and exactly installed`() {
        val current = binding(8L)

        assertTrue(operatorSurfaceCanLoad(true, true, current, current))
        assertFalse(operatorSurfaceCanLoad(false, true, current, current))
        assertFalse(operatorSurfaceCanLoad(true, false, current, current))
        assertFalse(operatorSurfaceCanLoad(true, true, current, binding(9L)))
        assertFalse(operatorSurfaceCanLoad(true, true, null, current))
    }

    @Test
    fun `ephemeral output diagnostics redact content`() {
        val output = OperatorEphemeralOutput(OperatorOutputKind.Script, "secret output")

        assertFalse(output.toString().contains("secret output"))
        assertEquals("secret output", output.copyText())
    }

    @Test
    fun `incremental output buffer retains only complete newest utf8 scalars`() {
        val buffer = BoundedOperatorOutputBuffer(maximumUtf8Bytes = 4)

        buffer.append("ab")
        buffer.append("é€")

        assertEquals("€", buffer.snapshot())
        assertEquals(3, buffer.retainedUtf8Bytes)

        buffer.clear()
        buffer.append("a\uD83D")
        buffer.append("\uDE00")

        assertEquals("\uD83D\uDE00", buffer.snapshot())
        assertEquals(4, buffer.retainedUtf8Bytes)

        val tooSmallForSupplementaryScalar = BoundedOperatorOutputBuffer(maximumUtf8Bytes = 3)
        tooSmallForSupplementaryScalar.append("\uD83D\uDE00")
        assertEquals("", tooSmallForSupplementaryScalar.snapshot())
    }

    @Test(timeout = 5_000L)
    fun `many tiny appends keep incremental storage chunk bounded`() {
        val maximumBytes = 4 * 1024
        val buffer = BoundedOperatorOutputBuffer(maximumUtf8Bytes = maximumBytes)
        buffer.append("x".repeat(maximumBytes))

        repeat(100_000) { buffer.append("x") }

        assertEquals(maximumBytes, buffer.retainedUtf8Bytes)
        assertTrue(buffer.retainedChunkCount <= 2)
        assertEquals("x".repeat(maximumBytes), buffer.snapshot())
    }

    @Test
    fun `owned upload buffer clears and diagnostics redact name and content`() {
        val request = OperatorScriptUploadRequest(
            fileName = "private.sh",
            content = "secret".encodeToByteArray(),
        )

        request.clear()

        assertTrue(request.content.all { it == 0.toByte() })
        assertFalse(request.toString().contains("private.sh"))
        assertFalse(request.toString().contains("secret"))
    }

    private fun binding(generation: Long) = NanoKvmSessionBinding(
        profileId = "office",
        authority = "192.0.2.4",
        sessionGeneration = generation,
    )
}

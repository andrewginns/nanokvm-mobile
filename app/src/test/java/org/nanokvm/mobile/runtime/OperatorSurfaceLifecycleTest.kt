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
    fun `ephemeral output is utf8 bounded and diagnostics redact content`() {
        val retained = appendBoundedOperatorOutput("ab", "é€", maximumUtf8Bytes = 4)
        val output = OperatorEphemeralOutput(OperatorOutputKind.Script, "secret output")

        assertEquals("€", retained)
        assertFalse(output.toString().contains("secret output"))
        assertEquals("secret output", output.copyText())
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

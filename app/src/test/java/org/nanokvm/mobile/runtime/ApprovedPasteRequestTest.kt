package org.nanokvm.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedPasteRequestTest {
    @Test
    fun `approval matches only its exact profile authority and session generation`() {
        val request = ApprovedPasteRequest(
            profileId = "office",
            authority = "192.0.2.250",
            sessionGeneration = 7,
            content = "clipboard secret",
            keyboardLayout = KeyboardLayout.Uk,
        )

        assertTrue(request.matchesDestination("office", "192.0.2.250", 7))
        assertFalse(request.matchesDestination("lab", "192.0.2.250", 7))
        assertFalse(request.matchesDestination("office", "192.0.2.251", 7))
        assertFalse(request.matchesDestination("office", "192.0.2.250", 8))
        assertFalse(request.toString().contains("clipboard secret"))
    }
}

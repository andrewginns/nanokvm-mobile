package org.nanokvm.mobile.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.BuildConfig

class PlayDistributionPolicyTest {
    @Test
    fun `Play build disables PicoClaw in the production backend`() = runBlocking {
        assertFalse(BuildConfig.DEBUG)
        assertFalse(BuildConfig.PICOCLAW_ENABLED)
        assertTrue(BuildConfig.PUBLIC_DISTRIBUTION_BUILD)

        val backend = NanoKvmConsoleBackend()
        try {
            assertNull(backend.features.picoClaw)
        } finally {
            backend.closeAndAwait()
        }
    }
}

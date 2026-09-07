package org.nanokvm.mobile

import android.content.ClipData
import android.content.Intent
import android.text.SpannableStringBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nanokvm.mobile.ui.AppNotice
import org.nanokvm.mobile.ui.AppUiState
import org.nanokvm.mobile.ui.AppViewModel
import org.nanokvm.mobile.ui.ShareNotice
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MainActivityShareIntentTest {
    @Test
    fun acceptedSharedTextIsNotRetainedByActivityIntent() {
        assertShareIntentDiscarded(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, SECRET),
        )
    }

    @Test
    fun spannedExtraTextIsNormalizedAndScrubbedWithoutClipData() {
        val sharedIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, SpannableStringBuilder("$SECRET\r\nnext\rline"))
            .putExtra("android.content.extra.IS_SENSITIVE", true)

        assertShareIntentDiscarded(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
            newIntent = sharedIntent,
        ) { state ->
            val payload = requireNotNull(state.pendingSharedPaste)
            assertEquals("$SECRET\nnext\nline", payload.text)
            assertTrue(payload.isSensitive)
        }

        assertNull(sharedIntent.action)
        assertNull(sharedIntent.type)
        assertNull(sharedIntent.clipData)
        assertTrue(sharedIntent.extras?.isEmpty != false)
    }

    @Test
    fun rejectedSharedPayloadIsNotRetainedByActivityIntent() {
        assertShareIntentDiscarded(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/html")
                .apply {
                    clipData = ClipData.newPlainText("shared", SECRET)
                    putExtra(Intent.EXTRA_TEXT, SECRET)
                },
        )
    }

    @Test
    fun multiChunkSharedTextIsAcceptedAndScrubbedFromTheActivityIntent() {
        assertShareIntentDiscarded(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "x".repeat(1_025)),
        ) { state ->
            assertEquals(1_025, state.pendingSharedPaste?.utf8ByteCount)
            assertEquals(2, state.pendingSharedPaste?.chunkCount)
        }
    }

    @Test
    fun sharedTextOverRetainedLimitIsRejectedAndScrubbedFromTheActivityIntent() {
        assertShareIntentDiscarded(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "x".repeat(65_537)),
        ) { state -> assertTooLargeNotice(state) }
    }

    @Test
    fun sharedClipDataOverRetainedLimitKeepsItsSpecificReasonAndIsScrubbed() {
        assertShareIntentDiscarded(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .apply {
                    clipData = ClipData.newPlainText("oversized", "x".repeat(65_537))
                },
        ) { state -> assertTooLargeNotice(state) }
    }

    private fun assertShareIntentDiscarded(
        launchIntent: Intent,
        newIntent: Intent? = null,
        assertState: (AppUiState) -> Unit = {},
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var activity: MainActivity? = null
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .startActivity(launchIntent)
            val launched = instrumentation.waitForMonitorWithTimeout(monitor, ACTIVITY_TIMEOUT_MILLIS)
                as? MainActivity
                ?: throw AssertionError("MainActivity was not launched")
            activity = launched
            instrumentation.waitForIdleSync()

            if (newIntent != null) {
                instrumentation.runOnMainSync {
                    // Starting an Activity can migrate EXTRA_TEXT into ClipData. Deliver directly
                    // to exercise the EXTRA_TEXT fallback without that framework conversion.
                    assertNull(newIntent.clipData)
                    instrumentation.callActivityOnNewIntent(launched, newIntent)
                }
                instrumentation.waitForIdleSync()
            }

            val retainedReference = AtomicReference<Intent>()
            val stateReference = AtomicReference<AppUiState>()
            instrumentation.runOnMainSync {
                retainedReference.set(Intent(launched.intent))
                val field = MainActivity::class.java.getDeclaredField("appViewModel")
                    .apply { isAccessible = true }
                val viewModel = requireNotNull(field.get(launched) as? AppViewModel)
                stateReference.set(viewModel.state.value)
            }
            val retained = retainedReference.get()
            assertFalse(retained.action == Intent.ACTION_SEND)
            assertNull(retained.type)
            assertNull(retained.clipData)
            assertNull(retained.extras)
            assertFalse(retained.toUri(Intent.URI_INTENT_SCHEME).contains(SECRET))
            assertState(requireNotNull(stateReference.get()))
        } finally {
            instrumentation.removeMonitor(monitor)
            activity?.let { launched ->
                instrumentation.runOnMainSync { launched.finishAndRemoveTask() }
                instrumentation.waitForIdleSync()
            }
        }
    }

    private fun assertTooLargeNotice(state: AppUiState) {
        assertNull(state.pendingSharedPaste)
        assertTrue(
            state.pendingAppNotices.any {
                it.content == AppNotice.Share(ShareNotice.TooLarge)
            },
        )
    }

    private companion object {
        const val ACTIVITY_TIMEOUT_MILLIS = 10_000L
        const val SECRET = "shared-text-must-not-survive"
    }
}

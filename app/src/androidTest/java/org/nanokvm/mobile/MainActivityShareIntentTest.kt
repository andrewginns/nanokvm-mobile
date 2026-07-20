package org.nanokvm.mobile

import android.content.ClipData
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
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
    fun oversizedSharedTextIsRejectedAndScrubbedFromTheActivityIntent() {
        assertShareIntentDiscarded(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "x".repeat(1_025)),
        )
    }

    private fun assertShareIntentDiscarded(launchIntent: Intent) {
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

            val retainedReference = AtomicReference<Intent>()
            instrumentation.runOnMainSync {
                retainedReference.set(Intent(launched.intent))
            }
            val retained = retainedReference.get()
            assertFalse(retained.action == Intent.ACTION_SEND)
            assertNull(retained.type)
            assertNull(retained.clipData)
            assertNull(retained.extras)
            assertFalse(retained.toUri(Intent.URI_INTENT_SCHEME).contains(SECRET))
        } finally {
            instrumentation.removeMonitor(monitor)
            activity?.let { launched ->
                instrumentation.runOnMainSync { launched.finishAndRemoveTask() }
                instrumentation.waitForIdleSync()
            }
        }
    }

    private companion object {
        const val ACTIVITY_TIMEOUT_MILLIS = 10_000L
        const val SECRET = "shared-text-must-not-survive"
    }
}

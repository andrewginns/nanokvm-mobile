package org.nanokvm.mobile.macrobenchmark

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The system picker owns the foreground during rotation; no appliance or upload is involved. */
@RunWith(AndroidJUnit4::class)
class OfflinePickerRegressionTest {
    @Test
    fun cancelThenRotateAndReturnToReviewThroughSystemPicker() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val fileName = "nanokvm_9.9.9.tar.gz"
        val uri = checkNotNull(resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/gzip")
            },
        ))
        val device = UiDevice.getInstance(instrumentation)
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(byteArrayOf(31, -117, 8)) }
            device.setOrientationNatural()
            device.executeShellCommand("am start -W -n $CONSOLE_FIXTURE_PACKAGE/$CONSOLE_FIXTURE_ACTIVITY")
            checkNotNull(device.wait(Until.findObject(By.desc("Open console controls")), TIMEOUT)).click()
            device.tapText("More actions", scroll = true)
            device.tapText("Administration")
            device.tapText("Install offline update package", scroll = true)
            device.tapText("Choose package")
            check(device.wait(Until.hasObject(By.desc("Show roots")), TIMEOUT))
            device.pressBack()
            check(device.wait(Until.gone(By.desc("Show roots")), TIMEOUT))
            device.tapText("Choose package")

            checkNotNull(device.wait(Until.findObject(By.desc("Show roots")), TIMEOUT)).click()
            device.tapText("Downloads")
            check(device.wait(Until.hasObject(By.text(fileName)), TIMEOUT))
            device.findObject(By.desc("List view"))?.click()
            device.setOrientationLeft()
            device.waitForIdle(1_500)
            device.tapText(fileName)
            check(device.wait(Until.hasObject(By.text("Install once")), TIMEOUT)) {
                val hierarchy = ByteArrayOutputStream().use { output ->
                    device.dumpWindowHierarchy(output)
                    output.toString(Charsets.UTF_8.name())
                }
                val labels = Regex("(?:text|content-desc)=\"([^\"]+)\"")
                    .findAll(hierarchy).map { it.groupValues[1] }.distinct().toList()
                "Expected the review screen after choosing the package. Visible labels: $labels"
            }
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
            resolver.delete(uri, null, null)
            device.executeShellCommand("am force-stop $CONSOLE_FIXTURE_PACKAGE")
        }
    }

    private fun UiDevice.tapText(text: String, scroll: Boolean = false) {
        val selector = By.text(text)
        if (scroll) {
            repeat(6) {
                findObject(selector)?.let {
                    it.click()
                    return
                }
                val container = checkNotNull(wait(
                    Until.findObject(By.clazz(ScrollView::class.java).scrollable(true)),
                    TIMEOUT,
                ))
                val bounds = container.visibleBounds
                swipe(
                    bounds.centerX(),
                    bounds.bottom - bounds.height() / 7,
                    bounds.centerX(),
                    bounds.top + bounds.height() / 8,
                    64,
                )
                waitForIdle(1_000)
            }
        }
        repeat(3) {
            val target = checkNotNull(wait(Until.findObject(selector), TIMEOUT)) {
                "Missing UI text: $text"
            }
            try {
                target.click()
                return
            } catch (_: StaleObjectException) {
                // DocumentsUI may replace a node while its navigation drawer opens.
                waitForIdle(1_000)
            }
        }
        error("Unstable UI text: $text")
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}

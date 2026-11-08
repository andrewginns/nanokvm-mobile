package org.nanokvm.mobile.macrobenchmark

import android.content.pm.ApplicationInfo
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.waitForStableInActiveWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises system capture of the non-debug app, including windows recreated during rotation. */
@RunWith(AndroidJUnit4::class)
class ScreenCaptureInstrumentedTest {
    private lateinit var device: UiDevice

    @Before
    fun resetTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        val target = instrumentation.context.packageManager.getApplicationInfo(TARGET_PACKAGE, 0)
        assertEquals("This regression requires a non-debug target.", 0, target.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertTrue(device.executeShellCommand("pm clear $TARGET_PACKAGE").contains("Success"))
        device.setOrientationNatural()
        launchTarget()
    }

    @After
    fun stopTarget() {
        device.setOrientationNatural()
        device.unfreezeRotation()
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
    }

    @Test
    fun foregroundScreenshotsContainAppContentAcrossNavigationResumeAndRotation() {
        assertTextIsCaptured("NanoKVM Mobile")
        device.findObject(By.desc("Add a NanoKVM profile")).click()
        assertTextIsCaptured("Add NanoKVM")

        device.pressHome()
        launchTarget()
        assertTextIsCaptured("Add NanoKVM")

        device.setOrientationLeft()
        assertTextIsCaptured("Add NanoKVM")
        device.setOrientationNatural()
        assertTextIsCaptured("Add NanoKVM")
    }

    private fun launchTarget() {
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/org.nanokvm.mobile.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), UI_TIMEOUT_MS))
    }

    private fun assertTextIsCaptured(text: String) {
        assertTrue("App text `$text` did not appear.", device.wait(Until.hasObject(By.text(text)), UI_TIMEOUT_MS))
        assertTrue("App window did not stabilize for `$text`.", !device.waitForStableInActiveWindow(
            stableTimeoutMs = UI_TIMEOUT_MS,
            requireStableScreenshot = false,
        ).isTimeout)
        val bounds = device.findObject(By.text(text)).visibleBounds
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "Android did not return a system screenshot."
        }
        try {
            assertTrue("App text `$text` has no capturable bounds.", !bounds.isEmpty)
            assertTrue("App text `$text` is outside the screenshot.",
                bounds.left >= 0 && bounds.top >= 0 &&
                    bounds.right <= screenshot.width && bounds.bottom <= screenshot.height)
            val pixels = IntArray(bounds.width() * bounds.height())
            screenshot.getPixels(pixels, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height())
            // Secure windows produce a blank app region. Sample only the app's text bounds so
            // visible system bars or a keyboard cannot make a blocked capture pass.
            val darkest = pixels.minOf { Color.red(it) + Color.green(it) + Color.blue(it) }
            val lightest = pixels.maxOf { Color.red(it) + Color.green(it) + Color.blue(it) }
            assertTrue("System capture blanked app text `$text`.", lightest - darkest > 96)
        } finally {
            screenshot.recycle()
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "org.nanokvm.mobile"
        const val UI_TIMEOUT_MS = 8_000L
    }
}

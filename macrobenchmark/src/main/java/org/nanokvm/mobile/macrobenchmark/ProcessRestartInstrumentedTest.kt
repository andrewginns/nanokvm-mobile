package org.nanokvm.mobile.macrobenchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises Android's real saved-instance-state path across process death.
 *
 * This deliberately uses `am kill` after backgrounding the target. Unlike force-stop, that preserves
 * the task and saved state Android would retain when reclaiming a background process.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRestartInstrumentedTest {
    private lateinit var device: UiDevice

    @Before
    fun resetTarget() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertShellSucceeded("pm clear $TARGET_PACKAGE", "Success")
        launchTarget()
    }

    @After
    fun stopTarget() {
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
    }

    @Test
    fun nonSecretProfileDraftSurvivesBackgroundProcessDeath() {
        assertTrue(
            "Profile catalog did not expose the add-profile action.",
            device.wait(Until.hasObject(By.desc(ADD_PROFILE_DESCRIPTION)), UI_TIMEOUT_MS),
        )
        device.findObject(By.desc(ADD_PROFILE_DESCRIPTION)).click()

        assertTrue(
            "Profile editor did not open.",
            device.wait(Until.hasObject(By.text(ADD_PROFILE_TITLE)), UI_TIMEOUT_MS),
        )
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        assertTrue("Expected the visible profile name and host fields.", fields.size >= 2)
        fields[0].text = DRAFT_NAME
        fields[1].text = DRAFT_HOST

        device.pressHome()
        device.waitForIdle()
        val originalPid = processId()
        assertTrue("Target process was not running before the restart exercise.", originalPid.isNotBlank())

        device.executeShellCommand("am kill $TARGET_PACKAGE")
        assertTrue(
            "Android did not terminate the background target process.",
            device.wait(Until.gone(By.pkg(TARGET_PACKAGE)), UI_TIMEOUT_MS),
        )
        assertEquals("", processId())

        launchTarget()
        val restartedPid = processId()
        assertTrue("Target process did not restart.", restartedPid.isNotBlank())
        assertNotEquals("Expected a fresh process after Android reclaimed the app.", originalPid, restartedPid)
        assertTrue(
            "Profile editor destination was not restored.",
            device.wait(Until.hasObject(By.text(ADD_PROFILE_TITLE)), UI_TIMEOUT_MS),
        )

        val restoredText = device.findObjects(By.clazz("android.widget.EditText")).map { it.text.orEmpty() }
        assertTrue("Draft name was not restored after process death.", DRAFT_NAME in restoredText)
        assertTrue("Draft host was not restored after process death.", DRAFT_HOST in restoredText)
        assertTrue(
            "A password-like field must never be restored into the editor hierarchy.",
            device.findObjects(By.clazz("android.widget.EditText")).none {
                it.contentDescription?.contains("password", ignoreCase = true) == true
            },
        )
    }

    private fun launchTarget() {
        device.executeShellCommand("am start -W -n $TARGET_PACKAGE/.MainActivity")
        assertTrue(
            "NanoKVM Mobile did not reach an interactive window.",
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), UI_TIMEOUT_MS),
        )
    }

    private fun processId(): String =
        device.executeShellCommand("pidof $TARGET_PACKAGE").trim()

    private fun assertShellSucceeded(command: String, expected: String) {
        val output = device.executeShellCommand(command).trim()
        assertTrue("`$command` returned `$output`.", output.contains(expected))
    }

    private companion object {
        const val TARGET_PACKAGE = "org.nanokvm.mobile"
        const val ADD_PROFILE_DESCRIPTION = "Add a NanoKVM profile"
        const val ADD_PROFILE_TITLE = "Add NanoKVM"
        const val DRAFT_NAME = "Restart-safe desk KVM"
        const val DRAFT_HOST = "restart-safe.example.test"
        const val UI_TIMEOUT_MS = 8_000L
    }
}

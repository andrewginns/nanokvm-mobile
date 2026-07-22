package org.nanokvm.mobile.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateStartupProfile() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        maxIterations = MAX_PROFILE_ITERATIONS,
        stableIterations = REQUIRED_STABLE_ITERATIONS,
        includeInStartupProfile = true,
        strictStability = true,
        profileBlock = {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.desc(ADD_PROFILE_DESCRIPTION)), UI_TIMEOUT_MS)) {
                "Profile catalog did not become ready after startup."
            }
        },
    )

    @Test
    fun generateProfileCatalogNavigation() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        maxIterations = MAX_PROFILE_ITERATIONS,
        stableIterations = REQUIRED_STABLE_ITERATIONS,
        includeInStartupProfile = false,
        strictStability = true,
        profileBlock = {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.desc(ADD_PROFILE_DESCRIPTION)), UI_TIMEOUT_MS)) {
                "Profile catalog did not expose the add-profile action."
            }
            device.findObject(By.desc(ADD_PROFILE_DESCRIPTION)).click()
            check(device.wait(Until.gone(By.desc(ADD_PROFILE_DESCRIPTION)), UI_TIMEOUT_MS)) {
                "Profile editor did not replace the profile catalog."
            }
            check(device.wait(Until.hasObject(By.text(PROFILE_EDITOR_TITLE)), UI_TIMEOUT_MS)) {
                "Profile editor did not become ready."
            }
            device.pressBack()
            check(device.wait(Until.hasObject(By.desc(ADD_PROFILE_DESCRIPTION)), UI_TIMEOUT_MS)) {
                "Profile catalog did not return after leaving the profile editor."
            }
        },
    )

    private companion object {
        const val TARGET_PACKAGE = "org.nanokvm.mobile"
        const val ADD_PROFILE_DESCRIPTION = "Add a NanoKVM profile"
        const val PROFILE_EDITOR_TITLE = "Add NanoKVM"
        const val MAX_PROFILE_ITERATIONS = 15
        const val REQUIRED_STABLE_ITERATIONS = 3
        const val UI_TIMEOUT_MS = 5_000L
    }
}

package org.nanokvm.mobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class MjpegFrameDetectionPreferenceControlInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun preferenceIsOffByDefaultAndExposesOneToggleAction() {
        var enabled by mutableStateOf(false)
        var changes = 0
        composeRule.setContent {
            NanoKvmTheme {
                MjpegFrameDetectionPreferenceControl(
                    enabled = enabled,
                    onEnabledChange = {
                        changes++
                        enabled = it
                    },
                )
            }
        }

        composeRule.onNodeWithTag(MJPEG_FRAME_DETECTION_TOGGLE_TAG)
            .assertIsOff()
            .performClick()
            .assertIsOn()

        composeRule.runOnIdle {
            assertEquals(true, enabled)
            assertEquals(1, changes)
        }
    }
}

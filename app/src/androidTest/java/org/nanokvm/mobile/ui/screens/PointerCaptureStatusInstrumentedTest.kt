package org.nanokvm.mobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.ui.input.PointerCaptureState
import org.nanokvm.mobile.ui.input.PointerCaptureUnavailableReason
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class PointerCaptureStatusInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun activeAndUnavailableReleasePathsRemainVisibleAtLargeTextOnNarrowScreens() {
        val state = mutableStateOf<PointerCaptureState>(PointerCaptureState.Active)
        var retryCalls = 0
        var releaseCalls = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NanoKvmTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 479.dp)) {
                        PointerCaptureStatus(
                            state = state.value,
                            onRetry = { retryCalls++ },
                            onRelease = { releaseCalls++ },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("pointer-capture-release-action")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("pointer-capture-retry-action").assertDoesNotExist()

        composeRule.runOnIdle {
            state.value = PointerCaptureState.Unavailable(
                PointerCaptureUnavailableReason.RequestRejected,
            )
        }
        composeRule.onNodeWithTag("pointer-capture-retry-action")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("pointer-capture-release-action")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, retryCalls)
            assertEquals(2, releaseCalls)
        }
    }
}

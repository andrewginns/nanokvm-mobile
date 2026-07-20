package org.nanokvm.mobile

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.ui.components.PoliteStatus
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class PoliteStatusInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun asynchronousStatusUsesPoliteLiveRegionAndUpdatesMergedText() {
        val message = mutableStateOf("Applying action")
        composeRule.setContent {
            NanoKvmTheme {
                PoliteStatus(Modifier.testTag("polite-status")) {
                    Text(message.value)
                }
            }
        }

        composeRule.onNodeWithTag("polite-status")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .assertIsDisplayed()

        composeRule.runOnIdle { message.value = "Action could not be applied" }

        composeRule.onNodeWithText("Action could not be applied").assertIsDisplayed()
    }
}

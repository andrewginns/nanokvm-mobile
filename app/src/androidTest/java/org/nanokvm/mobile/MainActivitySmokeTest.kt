package org.nanokvm.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun profileListAndEditorAreReachable() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithText("Connections").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.profiles_add_content_description)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.profile_editor_add_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Host or IP address").assertIsDisplayed()
    }

    @Test
    fun profileDraftAndDestinationSurviveRealActivityRecreation() {
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.profiles_add_content_description)).performClick()
        composeRule.onAllNodes(hasSetTextAction())[0]
            .performTextReplacement("Draft across recreation")

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.profile_editor_add_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Draft across recreation").assertIsDisplayed()
    }
}

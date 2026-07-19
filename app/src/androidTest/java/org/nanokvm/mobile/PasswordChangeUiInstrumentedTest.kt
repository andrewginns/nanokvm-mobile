package org.nanokvm.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.ui.screens.PasswordChangeDialog
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class PasswordChangeUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun exactConfirmationProducesOneOwnedMutableSubmission() {
        var submittedUsername: String? = null
        var submittedPassword: CharArray? = null
        var saveReplacement = true

        composeRule.setContent {
            NanoKvmTheme {
                PasswordChangeDialog(
                    destinationLabel = "Lab NanoKVM",
                    currentUsername = "admin",
                    protectedCredentialAvailable = false,
                    onDismiss = {},
                    onSubmit = { username, password, save ->
                        submittedUsername = username
                        submittedPassword = password
                        saveReplacement = save
                    },
                )
            }
        }

        composeRule.onNodeWithTag("password-change-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("password-change-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("password-change-password").performTextInput("replacement-123")
        composeRule.onNodeWithTag("password-change-confirmation")
            .performTextInput("replacement-123")
        composeRule.onNodeWithTag("password-change-confirm").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("admin", submittedUsername)
            assertArrayEquals("replacement-123".toCharArray(), submittedPassword)
            assertFalse(saveReplacement)
            submittedPassword?.fill('\u0000')
        }
    }

    @Test
    fun mismatchedConfirmationCannotDispatch() {
        var submissions = 0
        composeRule.setContent {
            NanoKvmTheme {
                PasswordChangeDialog(
                    destinationLabel = "Lab NanoKVM",
                    currentUsername = "admin",
                    protectedCredentialAvailable = true,
                    onDismiss = {},
                    onSubmit = { _, password, _ ->
                        submissions++
                        password.fill('\u0000')
                    },
                )
            }
        }

        composeRule.onNodeWithTag("password-change-save").assertIsOff()
        composeRule.onNodeWithTag("password-change-password").performTextInput("first-value")
        composeRule.onNodeWithTag("password-change-confirmation").performTextInput("other-value")
        composeRule.onNodeWithTag("password-change-confirm").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, submissions) }
    }
}

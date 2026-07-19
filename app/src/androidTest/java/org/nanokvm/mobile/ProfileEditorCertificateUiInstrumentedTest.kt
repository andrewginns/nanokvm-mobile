package org.nanokvm.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.ui.screens.ProfileEditorScreen
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class ProfileEditorCertificateUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun forgettingCertificateDoesNotSubmitInvalidOrUnrelatedDraftFields() {
        val original = HostProfile(
            id = "pinned-profile",
            name = "Rack console",
            host = "nanokvm.example.internal",
            trustedCertificateSha256 = "AA:BB:CC:DD",
        )
        val savedProfiles = mutableListOf<HostProfile>()
        val forgottenProfileIds = mutableListOf<String>()

        composeRule.setContent {
            NanoKvmTheme {
                ProfileEditorScreen(
                    initial = original,
                    isNew = false,
                    hasSavedPassword = false,
                    onSave = { savedProfiles += it },
                    onDelete = null,
                    onRemoveSavedPassword = {},
                    onForgetCertificate = { forgottenProfileIds += it },
                    onCancel = {},
                )
            }
        }

        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("")
        composeRule.onAllNodes(hasSetTextAction())[1]
            .performTextReplacement("unrelated-unsaved-host.example")
        // API 37 opens the IME for text replacement and keeps the host field focused. Let the
        // editor finish resizing before scrolling its lazy content to the independent action.
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile-editor-list")
            .performScrollToNode(hasText("Forget certificate"))
        composeRule.onNodeWithText("Forget certificate").performClick()

        assertTrue(savedProfiles.isEmpty())
        assertEquals(listOf(original.id), forgottenProfileIds)
    }
}

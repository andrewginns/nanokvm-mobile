package org.nanokvm.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.data.ThemeMode
import org.nanokvm.mobile.ui.ProfileStorageIssue
import org.nanokvm.mobile.ui.screens.ProfilesScreen
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class CredentialUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val profile = HostProfile(
        id = "credential-ui-profile",
        name = "Credential test KVM",
        host = "192.0.2.25",
    )

    @Test
    fun secureSavingDefaultsOffAndOrdinaryConnectUsesSessionOnlyPassword() {
        val submissions = mutableListOf<RecordedSubmission>()
        render(passwordEntryProfile = profile, submissions = submissions)

        composeRule.onNode(savePasswordToggle(ToggleableState.Off)).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput(ORDINARY_PASSWORD)
        composeRule.onAllNodesWithText("Connect")[1].performClick()

        assertEquals(1, submissions.size)
        assertEquals(ORDINARY_PASSWORD, submissions.single().password)
        assertEquals(false, submissions.single().savePassword)
    }

    @Test
    fun checkingSecureSaveSubmitsAnOwnedMutableBufferForAuthentication() {
        val submissions = mutableListOf<RecordedSubmission>()
        render(passwordEntryProfile = profile, submissions = submissions)

        composeRule.onNodeWithText("Save with biometrics or device screen lock").performClick()
        composeRule.onNode(savePasswordToggle(ToggleableState.On)).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput(STAGED_PASSWORD)
        composeRule.onNodeWithText("Save & connect").performClick()

        assertEquals(1, submissions.size)
        assertEquals(STAGED_PASSWORD, submissions.single().password)
        assertEquals(true, submissions.single().savePassword)
        assertTrue(submissions.single().sourceBufferWasCleared)
    }

    @Test
    fun savedProfileRequestsTypedUnlockWithoutOpeningPasswordEntry() {
        val prepared = mutableListOf<String>()
        render(savedPassword = true, preparedProfileIds = prepared)

        composeRule.onNodeWithText("Unlock").assertIsDisplayed().performClick()

        assertEquals(listOf(profile.id), prepared)
        composeRule.onNodeWithText("Sign in to ${profile.name}").assertDoesNotExist()
    }

    @Test
    fun removingSavedPasswordRequiresAnExplicitAction() {
        val removedProfileIds = mutableListOf<String>()
        render(
            savedPassword = true,
            passwordEntryProfile = profile,
            removedProfileIds = removedProfileIds,
        )

        composeRule.onNodeWithText("Remove saved password").assertIsDisplayed()
        assertTrue(removedProfileIds.isEmpty())

        composeRule.onNodeWithText("Remove saved password").performClick()

        assertEquals(listOf(profile.id), removedProfileIds)
    }

    @Test
    fun appearanceDialogChangesThemeAndDynamicColourPreference() {
        val themeChanges = mutableListOf<ThemeMode>()
        val dynamicColorChanges = mutableListOf<Boolean>()
        render(
            themeChanges = themeChanges,
            dynamicColorChanges = dynamicColorChanges,
        )

        composeRule.onNodeWithContentDescription("Appearance").performClick()
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.onNodeWithText("Use device colours").performClick()

        assertEquals(listOf(ThemeMode.DARK), themeChanges)
        assertEquals(listOf(false), dynamicColorChanges)
    }

    @Test
    fun corruptStorageCanBeKeptWithoutResetAndRecoveryRemainsReachable() {
        var resetRequests = 0
        render(
            profileStorageIssue = ProfileStorageIssue.Corrupted,
            onResetProfileStorage = { resetRequests += 1 },
        )

        composeRule.onNodeWithText(
            "Resetting permanently removes every saved connection, certificate decision and protected password.",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Keep data for now").performClick()

        assertEquals(0, resetRequests)
        composeRule.onNodeWithText("Saved connections remain locked").assertIsDisplayed()
        composeRule.onNodeWithText("Review recovery options").performClick()
        composeRule.onNodeWithText("Delete and reset all").performClick()

        assertEquals(1, resetRequests)
    }

    private fun render(
        savedPassword: Boolean = false,
        passwordEntryProfile: HostProfile? = null,
        submissions: MutableList<RecordedSubmission> = mutableListOf(),
        preparedProfileIds: MutableList<String> = mutableListOf(),
        removedProfileIds: MutableList<String> = mutableListOf(),
        themeChanges: MutableList<ThemeMode> = mutableListOf(),
        dynamicColorChanges: MutableList<Boolean> = mutableListOf(),
        profileStorageIssue: ProfileStorageIssue? = null,
        onResetProfileStorage: () -> Unit = {},
    ) {
        composeRule.setContent {
            NanoKvmTheme {
                ProfilesScreen(
                    profiles = listOf(profile),
                    profileCatalogResolved = true,
                    savedPasswordProfileIds = if (savedPassword) setOf(profile.id) else emptySet(),
                    profileStorageIssue = profileStorageIssue,
                    profileStorageBusy = false,
                    passwordEntryProfile = passwordEntryProfile,
                    canSavePassword = true,
                    themeMode = ThemeMode.SYSTEM,
                    useDynamicColor = true,
                    dynamicColorAvailable = true,
                    onAdd = {},
                    onEdit = {},
                    onPrepareConnection = { preparedProfileIds += it.id },
                    onSubmitPassword = { _, password, savePassword ->
                        val text = password.concatToString()
                        password.fill('\u0000')
                        submissions += RecordedSubmission(
                            password = text,
                            savePassword = savePassword,
                            sourceBufferWasCleared = password.all { it == '\u0000' },
                        )
                    },
                    onDismissPassword = {},
                    onRemoveSavedCredential = { removedProfileIds += it },
                    onResetProfileStorage = onResetProfileStorage,
                    onRetryProfileStorage = {},
                    onThemeModeChange = { themeChanges += it },
                    onUseDynamicColorChange = { dynamicColorChanges += it },
                )
            }
        }
    }

    private fun savePasswordToggle(state: ToggleableState): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    private data class RecordedSubmission(
        val password: String,
        val savePassword: Boolean,
        val sourceBufferWasCleared: Boolean,
    )

    private companion object {
        const val ORDINARY_PASSWORD = "ordinary-dummy-password"
        const val STAGED_PASSWORD = "staged-dummy-password"
    }
}

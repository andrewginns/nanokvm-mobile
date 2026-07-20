package org.nanokvm.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.runtime.AdministrationUiState
import org.nanokvm.mobile.runtime.AdministrationUpdateUiState
import org.nanokvm.mobile.runtime.ApprovedAdministrationDestination
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdatePhase
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateReview
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateUiState
import org.nanokvm.mobile.runtime.NanoKvmSessionBinding
import org.nanokvm.mobile.ui.screens.AdministrationDialog
import org.nanokvm.mobile.ui.screens.OfflineUpdateDialog
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class OfflineUpdateUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun administrationSoftwareSectionOpensOfflineUpdateSurface() {
        var opens = 0
        composeRule.setContent {
            NanoKvmTheme {
                AdministrationDialog(
                    destinationLabel = "Lab NanoKVM",
                    destination = ApprovedAdministrationDestination(
                        profileId = "offline-profile",
                        authority = "192.0.2.44",
                        sessionGeneration = 7L,
                    ),
                    state = AdministrationUiState(
                        available = true,
                        updates = AdministrationUpdateUiState(
                            currentVersion = "2.4.3",
                            latestVersion = null,
                            previewUpdatesEnabled = false,
                        ),
                    ),
                    controls = OfflineUpdateNoOpControls,
                    onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                    offlineUpdateAvailable = true,
                    onOfflineUpdate = { opens++ },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("administration-offline-update")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun reviewShowsExactDestinationAndOneShotWarningsBeforeConfirmingSameApproval() {
        val review = NanoKvmOfflineUpdateReview(
            destinationAuthority = "192.0.2.44",
            installedVersion = "2.4.3",
            packageVersion = "2.5.0",
            packageSizeBytes = 2L * 1024L * 1024L,
            binding = NanoKvmSessionBinding(
                profileId = "offline-profile",
                authority = "192.0.2.44",
                sessionGeneration = 7L,
            ),
        )
        var confirmed: NanoKvmOfflineUpdateReview? = null
        composeRule.setContent {
            NanoKvmTheme {
                OfflineUpdateDialog(
                    state = NanoKvmOfflineUpdateUiState(
                        phase = NanoKvmOfflineUpdatePhase.REVIEW_REQUIRED,
                        review = review,
                        totalBytes = review.packageSizeBytes,
                    ),
                    onChoosePackage = {},
                    onConfirm = { confirmed = it },
                    onCancelUpload = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("offline-update-review").assertIsDisplayed()
        composeRule.onNodeWithText("Destination: 192.0.2.44").assertIsDisplayed()
        composeRule.onNodeWithText("Currently reported version: 2.4.3").assertIsDisplayed()
        composeRule.onNodeWithText("Required filename: nanokvm_2.5.0.tar.gz").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Installing interrupts remote control and restarts NanoKVM services.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "This is one high-risk, one-shot request to the exact destination above. " +
                "It is never retried automatically.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("offline-update-confirm").performClick()

        composeRule.runOnIdle { assertSame(review, confirmed) }
    }
}

private object OfflineUpdateNoOpControls : NoOpAdministrationControls()

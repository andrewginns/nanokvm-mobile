package org.nanokvm.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.runtime.ConnectionFailure
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class ConnectionRecoveryInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun issueRecoveryAndBackRemainReachableAtTwoHundredPercentTextInCompactHeight() {
        var editCalls = 0
        var backCalls = 0
        val longHost = "very-long-nanokvm-host." + "subdomain.".repeat(18) + "example.invalid"
        val profile = HostProfile.Default.copy(
            name = "Remote laboratory NanoKVM target with an intentionally long operator label",
            host = longHost,
        )
        val issue = AppScreen.ConnectionIssue(
            profile = profile,
            failure = ConnectionFailure.CertificateHostnameMismatch(longHost),
            primaryRecovery = ConnectionIssueRecovery.EditConnection,
        )

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NanoKvmTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 479.dp)) {
                        ConnectionIssueScreen(
                            issue = issue,
                            onRetry = {},
                            onUseAnotherPassword = {},
                            onEditConnection = { editCalls++ },
                            onBack = { backCalls++ },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("connection-issue-primary-action")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("connection-issue-back-action")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, editCalls)
            assertEquals(1, backCalls)
        }
    }

    @Test
    fun connectingCancelRemainsReachableAtTwoHundredPercentTextInCompactHeight() {
        var cancelCalls = 0
        val profile = HostProfile.Default.copy(
            name = "Remote laboratory NanoKVM target with an intentionally long operator label",
            host = "long-authority." + "segment.".repeat(20) + "invalid",
        )

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NanoKvmTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 479.dp)) {
                        ConnectingScreen(profile = profile, onCancel = { cancelCalls++ })
                    }
                }
            }
        }

        composeRule.onNodeWithTag("connecting-cancel-action")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, cancelCalls) }
    }
}

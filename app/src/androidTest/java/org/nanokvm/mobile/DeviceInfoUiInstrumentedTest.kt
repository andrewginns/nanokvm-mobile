package org.nanokvm.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.NanoKvmDeviceStatus
import org.nanokvm.mobile.runtime.NanoKvmNetworkInterfaceStatus
import org.nanokvm.mobile.ui.screens.DeviceInfoDialog
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class DeviceInfoUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deviceIdentityAndUnknownCapabilitiesRemainReadableAndDismissible() {
        var dismissed = false
        composeRule.setContent {
            NanoKvmTheme {
                DeviceInfoDialog(
                    session = BackendSession(
                        deviceStatus = NanoKvmDeviceStatus(
                            applicationVersion = "2.4.3",
                            imageVersion = "2026.07",
                            hardwareVersion = "NanoKVM-Full",
                            deviceKey = "device-key-1234",
                            mdnsName = "nanokvm.local",
                            networkAddresses = listOf("192.0.2.250", "2001:db8::250"),
                            networkInterfaces = listOf(
                                NanoKvmNetworkInterfaceStatus(
                                    name = "eth0",
                                    address = "192.0.2.250",
                                    version = "v4",
                                    type = "wired",
                                ),
                                NanoKvmNetworkInterfaceStatus(
                                    name = "usb0",
                                    address = "2001:db8::250",
                                    version = "v6",
                                    type = "USB",
                                ),
                            ),
                        ),
                    ),
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithTag("device-info-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("2.4.3").assertIsDisplayed()
        composeRule.onNodeWithText("2026.07").assertIsDisplayed()
        composeRule.onNodeWithText("NanoKVM-Full").assertIsDisplayed()
        composeRule.onNodeWithText("device-key-1234").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("192.0.2.250")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Capability information is not available for this session.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }
}

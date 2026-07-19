package org.nanokvm.mobile

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.runtime.AdministrationAccountUiState
import org.nanokvm.mobile.runtime.AdministrationDnsMode
import org.nanokvm.mobile.runtime.AdministrationDnsUiState
import org.nanokvm.mobile.runtime.AdministrationMemoryLimitUiState
import org.nanokvm.mobile.runtime.AdministrationMouseJigglerSelection
import org.nanokvm.mobile.runtime.AdministrationMouseJigglerUiState
import org.nanokvm.mobile.runtime.AdministrationOledPreset
import org.nanokvm.mobile.runtime.AdministrationOledUiState
import org.nanokvm.mobile.runtime.AdministrationSwapPreset
import org.nanokvm.mobile.runtime.AdministrationSwapUiState
import org.nanokvm.mobile.runtime.AdministrationUiState
import org.nanokvm.mobile.runtime.AdministrationUpdateUiState
import org.nanokvm.mobile.runtime.AdministrationWifiUiState
import org.nanokvm.mobile.runtime.AdministrationTailscaleSelection
import org.nanokvm.mobile.runtime.AdministrationTailscaleUiState
import org.nanokvm.mobile.runtime.ApprovedPasteRequest
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.ConnectOutcome
import org.nanokvm.mobile.runtime.ConnectRequest
import org.nanokvm.mobile.runtime.ConnectionState
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.PowerAction
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.VideoSettings
import org.nanokvm.mobile.ui.screens.ConsoleScreen
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class AdministrationUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun administrationIsDiscoverableAndOpeningItIsReadOnly() {
        val backend = AdministrationReadOnlyBackend()
        val profile = HostProfile(
            id = "admin-ui",
            name = "Lab NanoKVM",
            host = "192.0.2.44",
        )

        composeRule.setContent {
            val session by backend.session.collectAsState()
            NanoKvmTheme {
                ConsoleScreen(
                    profile = profile,
                    session = session,
                    input = backend,
                    videoSurface = backend,
                    commands = backend,
                    onDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("administration-action")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("administration-surface").assertIsDisplayed()
        composeRule.onNodeWithText("Username: admin").assertIsDisplayed()
        composeRule.onNodeWithText("Factory password replaced").assertIsDisplayed()
        composeRule.onNodeWithText("Installed: 2.4.3").assertIsDisplayed()
        composeRule.onNodeWithTag("administration-password-change")
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag("password-change-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("password-change-save").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("administration-hdmi-toggle")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("HDMI capture: enabled").assertIsDisplayed()
        composeRule.onNodeWithText("Enabled · 75 MB").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("administration-enable-tls")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Connected to manual-network")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("administration-surface")
            .performScrollToNode(hasText("Log in"))
        composeRule.onNodeWithText("Log in")
            .performScrollTo()
            .assertExists()
        composeRule.runOnIdle {
            assertTrue(backend.administrationVisible)
            assertEquals(0, backend.mutationCalls)
        }
    }
}

private class AdministrationReadOnlyBackend : ConsoleBackend {
    private val mutableSession = MutableStateFlow(
        BackendSession(
            connection = ConnectionState.Connected,
            sessionGeneration = 4L,
            administration = AdministrationUiState(
                available = true,
                account = AdministrationAccountUiState("admin", passwordUpdated = true),
                updates = AdministrationUpdateUiState("2.4.3", "2.4.4", false),
                oled = AdministrationOledUiState(
                    exists = true,
                    sleepSeconds = 30,
                    preset = AdministrationOledPreset.Seconds30,
                ),
                sshEnabled = false,
                hostname = "nanokvm",
                mdnsEnabled = true,
                webTitle = "NanoKVM",
                webTitleIsDefault = true,
                dns = AdministrationDnsUiState(
                    mode = AdministrationDnsMode.Dhcp,
                    configuredServers = emptyList(),
                    effectiveServers = listOf("192.0.2.1"),
                    dhcpServers = listOf("192.0.2.1"),
                ),
                wifi = AdministrationWifiUiState(
                    supported = true,
                    accessPointMode = false,
                    connected = true,
                    ssid = "manual-network",
                ),
                tailscale = AdministrationTailscaleUiState(
                    selection = AdministrationTailscaleSelection.NotLoggedIn,
                ),
                hdmiEnabled = true,
                mouseJiggler = AdministrationMouseJigglerUiState(
                    AdministrationMouseJigglerSelection.Relative,
                ),
                memoryLimit = AdministrationMemoryLimitUiState(
                    enabled = true,
                    limitMegabytes = 75L,
                    writable = true,
                ),
                swap = AdministrationSwapUiState(
                    sizeMegabytes = 128L,
                    preset = AdministrationSwapPreset.Mb128,
                ),
            ),
        ),
    )
    override val session: StateFlow<BackendSession> = mutableSession
    var administrationVisible = false
    var mutationCalls = 0

    override suspend fun connect(request: ConnectRequest): ConnectOutcome = ConnectOutcome.Connected
    override suspend fun disconnect() = Unit
    override fun setForeground(isForeground: Boolean) = Unit
    override fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton>) = Unit
    override fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) = Unit
    override fun mouseButton(button: MouseButton, pressed: Boolean) = Unit
    override fun scrollWheel(steps: Int) = Unit
    override fun scrollHorizontal(steps: Int) = Unit
    override fun typeCommittedText(text: String, layout: KeyboardLayout) = Unit
    override fun key(key: RemoteKey, pressed: Boolean) = Unit
    override fun releaseAllInput() = Unit
    override fun attachVideoSurface(surface: Surface, width: Int, height: Int) = Unit
    override fun resizeVideoSurface(width: Int, height: Int) = Unit
    override fun detachVideoSurface(surface: Surface) = Unit
    override fun reconnect() = Unit
    override fun updateVideo(settings: VideoSettings) = Unit
    override fun resetHid() = Unit
    override fun power(action: PowerAction) = Unit
    override fun pasteText(request: ApprovedPasteRequest) = Unit
    override fun cancelPaste() = Unit

    override fun setAdministrationSurfaceVisible(visible: Boolean) {
        administrationVisible = visible
    }
}

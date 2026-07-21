package org.nanokvm.mobile

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.clipboard.ClipboardGateway
import org.nanokvm.mobile.clipboard.ClipboardReadResult
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
import org.nanokvm.mobile.runtime.ApprovedAdministrationDestination
import org.nanokvm.mobile.runtime.ApprovedPasteRequest
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.ApprovedCoreDestination
import org.nanokvm.mobile.runtime.ConnectOutcome
import org.nanokvm.mobile.runtime.ConnectRequest
import org.nanokvm.mobile.runtime.ConnectionFailure
import org.nanokvm.mobile.runtime.ConnectionState
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.TrustPreflightOutcome
import org.nanokvm.mobile.runtime.ConsoleFeatureBundle
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.PowerAction
import org.nanokvm.mobile.runtime.PendingAdministrationHttpsNavigationRequest
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.VideoSettings
import org.nanokvm.mobile.ui.screens.ConsoleScreen
import org.nanokvm.mobile.ui.screens.AdministrationDialog
import org.nanokvm.mobile.ui.screens.ConsoleSessionDraftOwner
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
        val sessionDraftOwner = ConsoleSessionDraftOwner()

        composeRule.setContent {
            val session by backend.session.collectAsState()
            NanoKvmTheme {
                ConsoleScreen(
                    profile = profile,
                    session = session,
                    input = backend,
                    videoSurface = backend,
                    features = backend.features,
                    onDisconnect = {},
                    sessionDraftOwner = sessionDraftOwner,
                    clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                    onSharedPasteConsumed = {},
                    onScrollSensitivityChange = {},
                    onMjpegFrameDetectionEnabledChange = {},
                    onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
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

    @Test
    fun tailscaleLoginHandoffRemainsAvailableWhenAndroidCannotOpenIt() {
        val destination = ApprovedAdministrationDestination(
            profileId = "admin-ui",
            authority = "192.0.2.44",
            sessionGeneration = 4L,
        )
        val request = PendingAdministrationHttpsNavigationRequest(
            requestId = 9L,
            profileId = destination.profileId,
            authority = destination.authority,
            sessionGeneration = destination.sessionGeneration,
            value = "https://login.tailscale.com/a/auth-token",
        )
        val uriHandler = FailingThenSuccessfulUriHandler()
        val commands = NavigationAcknowledgementCommands()

        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                NanoKvmTheme {
                    AdministrationDialog(
                        destinationLabel = "Lab NanoKVM",
                        destination = destination,
                        state = AdministrationUiState(
                            available = true,
                            tailscale = AdministrationTailscaleUiState(
                                selection = AdministrationTailscaleSelection.NotLoggedIn,
                            ),
                            pendingHttpsNavigation = request,
                        ),
                        controls = commands,
                        onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                        onOfflineUpdate = {},
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("administration-tailscale-open-login")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        // Button descendants are merged into the button's accessibility node. Assert the actual
        // user-visible retry label rather than an implementation-only child semantics node.
        composeRule.onNodeWithText("Couldn't open Tailscale login. Try again.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("administration-tailscale-open-login")
            .assertIsDisplayed()
        composeRule.runOnIdle { assertNull(commands.acknowledgedRequestId) }

        uriHandler.fail = false
        composeRule.onNodeWithTag("administration-tailscale-open-login").performClick()
        composeRule.runOnIdle {
            assertEquals(9L, commands.acknowledgedRequestId)
            assertEquals(destination, commands.acknowledgedDestination)
        }
    }

    @Test
    fun safeWifiDraftSurvivesRestorationButPasswordDoesNotEnterSavedState() {
        val restorationTester = StateRestorationTester(composeRule)
        val destination = ApprovedAdministrationDestination(
            profileId = "admin-ui",
            authority = "192.0.2.44",
            sessionGeneration = 4L,
        )
        restorationTester.setContent {
            NanoKvmTheme {
                AdministrationDialog(
                    destinationLabel = "Lab NanoKVM",
                    destination = destination,
                    state = AdministrationUiState(
                        available = true,
                        wifi = AdministrationWifiUiState(
                            supported = true,
                            accessPointMode = false,
                            connected = false,
                            ssid = null,
                        ),
                    ),
                    controls = NoOpAdministrationControls(),
                    onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                    onOfflineUpdate = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("administration-wifi-ssid")
            .performScrollTo()
            .performTextInput("safe-network")
        composeRule.onNodeWithTag("administration-wifi-password")
            .performScrollTo()
            .performTextInput("must-not-be-saved")
        composeRule.onNodeWithTag("administration-wifi-connect").assertIsEnabled()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("administration-wifi-ssid")
            .performScrollTo()
            .assertTextContains("safe-network")
        composeRule.onNodeWithTag("administration-wifi-connect").assertIsNotEnabled()
    }
}

private class FailingThenSuccessfulUriHandler : UriHandler {
    var fail = true

    override fun openUri(uri: String) {
        if (fail) error("No activity can handle this URI")
    }
}

private class NavigationAcknowledgementCommands : NoOpAdministrationControls() {
    var acknowledgedDestination: ApprovedAdministrationDestination? = null
    var acknowledgedRequestId: Long? = null

    override fun acknowledgeAdministrationNavigationOpened(
        destination: ApprovedAdministrationDestination,
        requestId: Long,
    ) {
        acknowledgedDestination = destination
        acknowledgedRequestId = requestId
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
    private val administrationControls = object : NoOpAdministrationControls() {
        override fun setAdministrationSurfaceVisible(visible: Boolean) {
            administrationVisible = visible
        }
    }
    override val features = ConsoleFeatureBundle(
        core = this,
        administration = administrationControls,
    )

    override suspend fun preflightTrust(profile: HostProfile): TrustPreflightOutcome =
        TrustPreflightOutcome.Failed(
            failure = ConnectionFailure.Unexpected,
            retryable = false,
        )

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
    override fun cancelReconnect() = Unit
    override fun updateVideo(settings: VideoSettings) = Unit
    override fun setMjpegFrameDetectionPreference(enabled: Boolean) = Unit
    override fun setMjpegFrameDetectionEnabled(enabled: Boolean) = Unit
    override fun resetHid() = Unit
    override fun power(destination: ApprovedCoreDestination, action: PowerAction) = Unit
    override fun pasteText(request: ApprovedPasteRequest) = Unit
    override fun cancelPaste() = Unit

}

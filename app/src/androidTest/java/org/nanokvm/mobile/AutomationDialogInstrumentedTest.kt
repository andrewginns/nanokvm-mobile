package org.nanokvm.mobile

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.runtime.NanoKvmAutomationGateway
import org.nanokvm.mobile.runtime.NanoKvmAutomationFeatureOwner
import org.nanokvm.mobile.runtime.NanoKvmAutomationPort
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartCatalog
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartContent
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartReceipt
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartScript
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartWrite
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartWriteKind
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortHidCatalog
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortHidKey
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortHidRunResult
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortHidShortcut
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortLeaderKey
import org.nanokvm.mobile.runtime.NanoKvmSessionBinding
import org.nanokvm.mobile.runtime.ApprovedPasteRequest
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.ConnectionState
import org.nanokvm.mobile.runtime.ConsoleCommandSink
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.PowerAction
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.VideoSettings
import org.nanokvm.mobile.runtime.VideoSurfaceSink
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.ui.screens.AutomationDialog
import org.nanokvm.mobile.ui.screens.ConsoleScreen
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class AutomationDialogInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unknownHidIsReadOnlyAndRootWriteRequiresExactConfirmation() {
        val port = AutomationDialogFakePort()
        val binding = NanoKvmSessionBinding(
            profileId = "automation-ui",
            authority = "192.0.2.77",
            sessionGeneration = 9,
        )
        val gateway = NanoKvmAutomationGateway(port, binding) { binding }

        composeRule.setContent {
            NanoKvmTheme {
                AutomationDialog(
                    destinationLabel = "Lab NanoKVM",
                    gateway = gateway,
                    onDismiss = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { port.hidListCalls > 0 }
        composeRule.onNodeWithTag("automation-surface").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Contains keys unknown to this app; visible but read-only.",
        ).assertIsDisplayed()

        composeRule.onNodeWithText("Autostart scripts").performClick()
        composeRule.onNodeWithText(
            "Autostart content is installed as executable mode 0755",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("automation-autostart-name")
            .performScrollTo()
            .performTextInput("new-safe.sh")
        composeRule.onNodeWithTag("automation-autostart-editor")
            .performScrollTo()
            .performTextInput("#!/bin/sh\nexit 0\n")
        composeRule.onNodeWithText("Review create").performScrollTo().performClick()

        composeRule.onNodeWithTag("automation-confirmation").assertIsDisplayed()
        composeRule.onNodeWithText("Create exact file new-safe.sh", substring = true)
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, port.createCalls) }

        composeRule.onNodeWithTag("automation-confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { port.createCalls == 1 }
        composeRule.runOnIdle { assertEquals(listOf("new-safe.sh"), port.autostartNames) }
    }

    @Test
    fun unavailableLeaderKeyIsNotRequestedOrReportedAsReadFailure() {
        val port = AutomationDialogFakePort()
        val binding = NanoKvmSessionBinding(
            profileId = "automation-ui",
            authority = "192.0.2.77",
            sessionGeneration = 10,
        )
        val gateway = NanoKvmAutomationGateway(port, binding) { binding }

        composeRule.setContent {
            NanoKvmTheme {
                AutomationDialog(
                    destinationLabel = "Lab NanoKVM",
                    gateway = gateway,
                    onDismiss = {},
                    leaderKeyAvailable = false,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { port.hidListCalls > 0 }
        composeRule.onNodeWithText("Not available on this NanoKVM firmware.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("State could not be read safely.").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, port.leaderReadCalls) }
    }

    @Test
    fun consoleEntryOwnsAutomationSurfaceLifecycle() {
        val profile = HostProfile(
            id = "automation-console",
            name = "Lab NanoKVM",
            host = "192.0.2.77",
        )
        val port = AutomationDialogFakePort()
        val bridge = AutomationConsoleBridge(
            binding = NanoKvmSessionBinding(profile.id, profile.authority, 12L),
            port = port,
        )

        composeRule.setContent {
            NanoKvmTheme {
                ConsoleScreen(
                    profile = profile,
                    session = BackendSession(
                        connection = ConnectionState.Connected,
                        sessionGeneration = 12L,
                    ),
                    input = bridge,
                    videoSurface = bridge,
                    commands = bridge,
                    onDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("automation-action").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { port.hidListCalls > 0 }
        composeRule.onNodeWithTag("automation-surface").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(true, bridge.surfaceVisible) }

        composeRule.onNodeWithText("Close").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("automation-surface").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000) { !bridge.surfaceVisible }
    }
}

private class AutomationConsoleBridge(
    private val binding: NanoKvmSessionBinding,
    port: NanoKvmAutomationPort,
) : ConsoleCommandSink, RemoteInputSink, VideoSurfaceSink, NanoKvmAutomationFeatureOwner {
    var surfaceVisible = false
        private set
    private val gateway = NanoKvmAutomationGateway(port, binding) {
        binding.takeIf { surfaceVisible }
    }

    override fun setAutomationSurfaceVisible(visible: Boolean) {
        surfaceVisible = visible
        if (visible) gateway.onForeground() else gateway.onBackground()
    }

    override fun currentAutomationGatewayToken(): Any? = gateway.takeIf { surfaceVisible }

    override fun reconnect() = Unit
    override fun updateVideo(settings: VideoSettings) = Unit
    override fun resetHid() = Unit
    override fun power(action: PowerAction) = Unit
    override fun pasteText(request: ApprovedPasteRequest) = Unit
    override fun cancelPaste() = Unit
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
}

private class AutomationDialogFakePort : NanoKvmAutomationPort {
    var hidListCalls = 0
    var leaderReadCalls = 0
    var createCalls = 0
    val autostartNames = mutableListOf<String>()

    override suspend fun listHidShortcuts(): NanoKvmAutomationPortHidCatalog {
        hidListCalls++
        return NanoKvmAutomationPortHidCatalog(
            shortcuts = listOf(
                NanoKvmAutomationPortHidShortcut(
                    keys = listOf(
                        NanoKvmAutomationPortHidKey(
                            code = "FutureKey",
                            label = "Future key",
                            known = false,
                        ),
                    ),
                    runnable = false,
                    opaqueToken = Any(),
                ),
            ),
            opaqueToken = Any(),
        )
    }

    override suspend fun saveHidShortcut(wireCodes: List<String>) = Unit
    override fun releaseAllInput(): Boolean = true
    override fun runHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ): NanoKvmAutomationPortHidRunResult = NanoKvmAutomationPortHidRunResult.Rejected

    override suspend fun deleteHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ) = Unit

    override suspend fun leaderKey(): NanoKvmAutomationPortLeaderKey {
        leaderReadCalls++
        return NanoKvmAutomationPortLeaderKey(
            code = "FutureLeader",
            displayLabel = "FutureLeader",
            known = false,
            enabled = true,
        )
    }

    override suspend fun setLeaderKey(wireCode: String) = Unit
    override suspend fun disableLeaderKey() = Unit

    override suspend fun listAutostartScripts(): NanoKvmAutomationPortAutostartCatalog =
        NanoKvmAutomationPortAutostartCatalog(
            scripts = autostartNames.map {
                NanoKvmAutomationPortAutostartScript(it, Any())
            },
            opaqueToken = Any(),
        )

    override suspend fun readAutostartContent(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ): NanoKvmAutomationPortAutostartContent =
        NanoKvmAutomationPortAutostartContent.takeOwnership(ByteArray(0))

    override suspend fun createAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        fileName: String,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt {
        createCalls++
        autostartNames += fileName
        val byteCount = content.byteCount
        content.close()
        return NanoKvmAutomationPortAutostartReceipt(
            displayName = fileName,
            byteCount = byteCount,
            kind = NanoKvmAutomationPortAutostartWriteKind.CREATE,
        )
    }

    override suspend fun updateAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt = throw AssertionError("Not used")

    override suspend fun deleteAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ) = Unit
}

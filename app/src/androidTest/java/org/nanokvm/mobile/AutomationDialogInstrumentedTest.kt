package org.nanokvm.mobile

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import org.nanokvm.mobile.runtime.ConsoleCoreControls
import org.nanokvm.mobile.runtime.ConsoleFeatureBundle
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.PowerAction
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.VideoSettings
import org.nanokvm.mobile.runtime.VideoSurfaceSink
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.clipboard.ClipboardGateway
import org.nanokvm.mobile.clipboard.ClipboardReadResult
import org.nanokvm.mobile.ui.screens.AutomationDialog
import org.nanokvm.mobile.ui.screens.ConsoleScreen
import org.nanokvm.mobile.ui.screens.ConsoleSessionDraftOwner
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
    fun largeAutomationCatalogsComposeBoundedRowsAndReachLastStableActions() {
        val shortcuts = List(512) { index ->
            NanoKvmAutomationPortHidShortcut(
                stableId = "instrumented-shortcut-$index",
                keys = listOf(
                    NanoKvmAutomationPortHidKey(
                        code = "KeyA",
                        label = "Shortcut $index",
                        known = true,
                    ),
                ),
                runnable = true,
            )
        }
        val autostartNames = List(512) { index -> "autostart-$index.sh" }
        val lastShortcut = shortcuts.last()
        val lastAutostartName = autostartNames.last()
        val port = AutomationDialogFakePort(
            hidShortcuts = shortcuts,
            initialAutostartNames = autostartNames,
        )
        val binding = NanoKvmSessionBinding(
            profileId = "automation-large-catalog-ui",
            authority = "192.0.2.77",
            sessionGeneration = 11,
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

        composeRule.waitUntil(timeoutMillis = 5_000) {
            port.hidListCalls > 0 && port.autostartListCalls > 0
        }
        composeRule.onNodeWithTag("automation-surface")
            .performScrollToKey("hid-shortcut:${lastShortcut.stableId}")
        composeRule.waitForIdle()

        val composedHidRows = composeRule.onAllNodesWithTag("automation-hid-shortcut")
            .fetchSemanticsNodes()
            .size
        assertTrue(composedHidRows in 1 until shortcuts.size)
        composeRule.onNodeWithTag("automation-hid-run-${lastShortcut.stableId}")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("automation-confirmation").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithTag("automation-surface").performScrollToKey("tabs")
        composeRule.onNodeWithText("Autostart scripts").performClick()
        composeRule.onNodeWithTag("automation-surface")
            .performScrollToKey("autostart-script:$lastAutostartName")
        composeRule.waitForIdle()

        val composedAutostartRows =
            composeRule.onAllNodesWithTag("automation-autostart-script")
                .fetchSemanticsNodes()
                .size
        assertTrue(composedAutostartRows in 1 until autostartNames.size)
        composeRule.onNodeWithTag("automation-autostart-delete-$lastAutostartName")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("automation-confirmation").assertIsDisplayed()
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
        val sessionDraftOwner = ConsoleSessionDraftOwner()

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
                    features = bridge.features,
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
        composeRule.onNodeWithTag("automation-action").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { port.hidListCalls > 0 }
        composeRule.onNodeWithTag("automation-surface").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(true, bridge.surfaceVisible) }

        composeRule.onNodeWithText("Close").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("automation-surface").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000) { !bridge.surfaceVisible }
    }

    @Test
    fun automationCatalogHandleRemainsUsableAcrossConsoleStateRestoration() {
        val profile = HostProfile(
            id = "automation-restoration",
            name = "Restoration NanoKVM",
            host = "192.0.2.78",
        )
        val runnableShortcut = NanoKvmAutomationPortHidShortcut(
            stableId = "restoration-shortcut",
            keys = listOf(
                NanoKvmAutomationPortHidKey(
                    code = "KeyA",
                    label = "A",
                    known = true,
                ),
            ),
            runnable = true,
        )
        val port = AutomationDialogFakePort(hidShortcuts = listOf(runnableShortcut))
        val bridge = AutomationConsoleBridge(
            binding = NanoKvmSessionBinding(profile.id, profile.authority, 13L),
            port = port,
        )
        val sessionDraftOwner = ConsoleSessionDraftOwner()
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            NanoKvmTheme {
                ConsoleScreen(
                    profile = profile,
                    session = BackendSession(
                        connection = ConnectionState.Connected,
                        sessionGeneration = 13L,
                    ),
                    input = bridge,
                    videoSurface = bridge,
                    features = bridge.features,
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
        composeRule.onNodeWithTag("automation-action").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            port.hidListCalls > 0 && port.autostartListCalls > 0
        }
        composeRule.onNodeWithTag("automation-hid-run-${runnableShortcut.stableId}")
            .assertIsDisplayed()
        val loadedHidCatalogCount = port.hidListCalls

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("automation-surface").assertIsDisplayed()
        composeRule.onNodeWithTag("automation-hid-run-${runnableShortcut.stableId}")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                "Configuration recreation must not background the live automation gateway",
                0,
                bridge.backgroundTransitions,
            )
            assertEquals(
                "The retained controller must keep its loaded catalog without a manual refresh",
                loadedHidCatalogCount,
                port.hidListCalls,
            )
        }
        composeRule.onNodeWithTag("automation-confirmation").assertIsDisplayed()
        composeRule.onNodeWithTag("automation-confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { port.hidRunCalls == 1 }
        composeRule.onNodeWithText("Action completed. State was refreshed.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, bridge.backgroundTransitions)
            assertEquals(1, port.hidRunCalls)
        }
        composeRule.onNodeWithText("Close").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { !bridge.surfaceVisible }
    }
}

private class AutomationConsoleBridge(
    private val binding: NanoKvmSessionBinding,
    port: NanoKvmAutomationPort,
) : ConsoleCoreControls, RemoteInputSink, VideoSurfaceSink, NanoKvmAutomationFeatureOwner {
    val features = ConsoleFeatureBundle(core = this, automation = this)
    var surfaceVisible = false
        private set
    var backgroundTransitions = 0
        private set
    private val gateway = NanoKvmAutomationGateway(port, binding) {
        binding.takeIf { surfaceVisible }
    }

    override fun setAutomationSurfaceVisible(visible: Boolean) {
        if (surfaceVisible && !visible) backgroundTransitions++
        surfaceVisible = visible
        if (visible) gateway.onForeground() else gateway.onBackground()
    }

    override fun currentAutomationGateway(): NanoKvmAutomationGateway? =
        gateway.takeIf { surfaceVisible }

    override fun reconnect() = Unit
    override fun updateVideo(settings: VideoSettings) = Unit
    override fun setMjpegFrameDetectionEnabled(enabled: Boolean) = Unit
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

private class AutomationDialogFakePort(
    private val hidShortcuts: List<NanoKvmAutomationPortHidShortcut> =
        defaultAutomationHidShortcuts(),
    initialAutostartNames: List<String> = emptyList(),
) : NanoKvmAutomationPort {
    var hidListCalls = 0
    var autostartListCalls = 0
    var leaderReadCalls = 0
    var createCalls = 0
    var hidRunCalls = 0
    val autostartNames = initialAutostartNames.toMutableList()

    override suspend fun listHidShortcuts(): NanoKvmAutomationPortHidCatalog {
        hidListCalls++
        return NanoKvmAutomationPortHidCatalog(
            shortcuts = hidShortcuts,
        )
    }

    override suspend fun saveHidShortcut(wireCodes: List<String>) = Unit
    override fun releaseAllInput(): Boolean = true
    override fun runHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ): NanoKvmAutomationPortHidRunResult {
        catalog.requireExactMember(shortcut)
        hidRunCalls++
        return NanoKvmAutomationPortHidRunResult.Completed(reportsSent = 1)
    }

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

    override suspend fun listAutostartScripts(): NanoKvmAutomationPortAutostartCatalog {
        autostartListCalls++
        return NanoKvmAutomationPortAutostartCatalog(
            scripts = autostartNames.map {
                NanoKvmAutomationPortAutostartScript(it)
            },
        )
    }

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

private fun defaultAutomationHidShortcuts(): List<NanoKvmAutomationPortHidShortcut> = listOf(
    NanoKvmAutomationPortHidShortcut(
        stableId = "instrumented-shortcut-a",
        keys = listOf(
            NanoKvmAutomationPortHidKey(
                code = "FutureKey",
                label = "Future key",
                known = false,
            ),
        ),
        runnable = false,
    ),
)

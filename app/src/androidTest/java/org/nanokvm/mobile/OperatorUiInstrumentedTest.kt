package org.nanokvm.mobile

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.runtime.ApprovedOperatorDestination
import org.nanokvm.mobile.runtime.ApprovedPasteRequest
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.ConnectOutcome
import org.nanokvm.mobile.runtime.ConnectRequest
import org.nanokvm.mobile.runtime.ConnectionState
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.OperatorEphemeralOutput
import org.nanokvm.mobile.runtime.OperatorScriptUiState
import org.nanokvm.mobile.runtime.OperatorUiState
import org.nanokvm.mobile.runtime.PowerAction
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.VideoSettings
import org.nanokvm.mobile.ui.screens.ConsoleScreen
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class OperatorUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun operatorToolsAreDiscoverableAndRootEntryRequiresExactReview() {
        val backend = OperatorReadOnlyBackend()
        val profile = HostProfile(
            id = "operator-ui",
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
        composeRule.onNodeWithTag("operator-tools-action")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("operator-surface").assertIsDisplayed()
        composeRule.onNodeWithText(
            "High risk: NanoKVM opens an unrestricted root shell on the appliance. Commands and scripts can damage the device, expose credentials, or interrupt KVM access. Nothing is sandboxed, replayed, or restored after backgrounding.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("health-check.sh")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("operator-open-terminal")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("Confirm unrestricted root action").assertIsDisplayed()
        composeRule.onNodeWithText("Destination: Lab NanoKVM (192.0.2.44)")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Authenticated session: 7").assertIsDisplayed()
        composeRule.onNodeWithText("Action: Open an unrestricted root PTY")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(backend.operatorVisible)
            assertEquals(0, backend.rootEntryCalls)
        }
    }
}

private class OperatorReadOnlyBackend : ConsoleBackend {
    private val mutableSession = MutableStateFlow(
        BackendSession(connection = ConnectionState.Connected, sessionGeneration = 7L),
    )
    override val session: StateFlow<BackendSession> = mutableSession
    override val operatorState: StateFlow<OperatorUiState> = MutableStateFlow(
        OperatorUiState(
            available = true,
            scriptsLoaded = true,
            scripts = listOf(OperatorScriptUiState(1L, "health-check.sh")),
        ),
    )
    override val operatorOutput: SharedFlow<OperatorEphemeralOutput> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    var operatorVisible = false
    var rootEntryCalls = 0

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

    override fun setOperatorSurfaceVisible(visible: Boolean) {
        operatorVisible = visible
    }

    override fun enterOperatorTerminal(destination: ApprovedOperatorDestination) {
        rootEntryCalls++
    }
}

package org.nanokvm.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.PixelCopy
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.data.ThemeMode
import org.nanokvm.mobile.clipboard.ClipboardGateway
import org.nanokvm.mobile.clipboard.ClipboardPayloadAnalyzer
import org.nanokvm.mobile.runtime.TrustPreflightOutcome
import org.nanokvm.mobile.clipboard.ClipboardReadResult
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.ApprovedCoreDestination
import org.nanokvm.mobile.runtime.ApprovedPasteRequest
import org.nanokvm.mobile.runtime.ConnectOutcome
import org.nanokvm.mobile.runtime.ConnectRequest
import org.nanokvm.mobile.runtime.ConnectionFailure
import org.nanokvm.mobile.runtime.ConnectionState
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.ConsoleFeatureBundle
import org.nanokvm.mobile.runtime.ConsoleMessage
import org.nanokvm.mobile.runtime.withActionFeedback
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.NanoKvmDeviceStatus
import org.nanokvm.mobile.runtime.NanoKvmNetworkInterfaceStatus
import org.nanokvm.mobile.runtime.Phase3FeatureUiState
import org.nanokvm.mobile.runtime.Phase3HidModeSelection
import org.nanokvm.mobile.runtime.Phase3HidModeUiState
import org.nanokvm.mobile.runtime.Phase3MediaImageUiState
import org.nanokvm.mobile.runtime.Phase3Notice
import org.nanokvm.mobile.runtime.Phase3VirtualMediaUiState
import org.nanokvm.mobile.runtime.Phase3WakeOnLanTargetUiState
import org.nanokvm.mobile.runtime.ApprovedPicoClawDestination
import org.nanokvm.mobile.runtime.PicoClawManualInputUiState
import org.nanokvm.mobile.runtime.PicoClawSupport
import org.nanokvm.mobile.runtime.PicoClawUiState
import org.nanokvm.mobile.runtime.PowerAction
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.VideoSettings
import org.nanokvm.mobile.runtime.VideoStreamDescriptor
import org.nanokvm.mobile.runtime.VideoTransportPreference
import org.nanokvm.mobile.ui.screens.ConsoleScreen
import org.nanokvm.mobile.ui.screens.ConsoleSessionDraftOwner
import org.nanokvm.mobile.ui.components.PointerMode
import org.nanokvm.mobile.ui.components.RemoteViewport
import org.nanokvm.mobile.ui.theme.DarkConsoleColorScheme
import org.nanokvm.mobile.ui.theme.NanoKvmTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the native console without requiring a reachable NanoKVM or stored credentials. */
class ConsoleScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val backend = RecordingConsoleBackend()
    private val sessionDraftOwner = ConsoleSessionDraftOwner()
    private val profile = HostProfile(
        id = "instrumented-console",
        name = "Lab NanoKVM",
        host = "192.0.2.250",
    )

    @After
    fun dismissSystemIme() {
        composeRule.runOnIdle {
            WindowCompat.getInsetsController(
                composeRule.activity.window,
                composeRule.activity.window.decorView,
            ).hide(WindowInsetsCompat.Type.ime())
            composeRule.activity.currentFocus?.clearFocus()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun nativeKeyboardUsesStandardImeModeAndForwardsCommittedText() {
        renderConsole()

        composeRule.onNodeWithContentDescription("Show keyboard")
            .assertIsDisplayed()
            .performClick()

        var imeEditor: View? = null
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.activity.currentFocus?.takeIf { it.onCheckIsTextEditor() }?.also {
                imeEditor = it
            } != null
        }

        composeRule.runOnIdle {
            val editor = imeEditor
            assertNotNull("The native IME sink should hold focus", editor)
            val editorInfo = EditorInfo()
            val connection = editor!!.onCreateInputConnection(editorInfo)
            assertNotNull("The native IME sink should expose an InputConnection", connection)
            assertEquals(
                InputType.TYPE_CLASS_TEXT,
                editorInfo.inputType and InputType.TYPE_MASK_CLASS,
            )
            assertEquals(
                "The native editor must not advertise a password variation, which disables voice input",
                InputType.TYPE_TEXT_VARIATION_NORMAL,
                editorInfo.inputType and InputType.TYPE_MASK_VARIATION,
            )
            assertTrue(editorInfo.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
            assertTrue(editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
            assertEquals(
                "The native editor must not force IME incognito mode, which can hide voice input",
                0,
                editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            )
            assertTrue(connection!!.commitText("hello from Android", 1))
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            backend.committedText == listOf("hello from Android" to KeyboardLayout.Us)
        }
    }

    @Test
    fun portraitShortcutsDriveImePointerModeAndDockedViewControls() {
        renderConsole()

        val previewBeforeKeyboard = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val defaultViewPanel = composeRule.onNodeWithTag("view-navigation-panel")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val quickActions = composeRule.onNodeWithTag("console-quick-actions")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("console-quick-actions-icons").assertIsDisplayed()
        composeRule.onNodeWithTag("console-quick-actions-labelled").assertDoesNotExist()
        composeRule.onNodeWithText("Keyboard").assertDoesNotExist()
        composeRule.onNodeWithText("Clipboard").assertDoesNotExist()
        composeRule.onNodeWithText("Controls").assertDoesNotExist()
        assertTrue(
            "Shortcuts must remain inside the preview without reserving a side rail",
            quickActions.left >= previewBeforeKeyboard.left - 1f &&
                quickActions.right <= previewBeforeKeyboard.right + 1f,
        )
        assertViewPanelContained(previewBeforeKeyboard, defaultViewPanel)
        assertRectsDoNotOverlap(
            defaultViewPanel,
            quickActions,
            "Shortcuts must not cover the default full-width navigation strip",
        )
        assertTrue(
            "The default view pad must overlay the bottom of the full preview",
            defaultViewPanel.top >= previewBeforeKeyboard.top - 1f &&
                defaultViewPanel.bottom <= previewBeforeKeyboard.bottom + 1f &&
                kotlin.math.abs(defaultViewPanel.bottom - previewBeforeKeyboard.bottom) <= 1f,
        )
        composeRule.onNodeWithContentDescription("Pan view left").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show view controls").assertIsDisplayed()

        // Exercise real pointer hit-testing on the relocated shortcut, not just its semantics action.
        performViewPadCustomAction("Move view pad up")
        performViewPadCustomAction("Move view pad up")
        assertViewPanelContained(
            previewBeforeKeyboard,
            composeRule.onNodeWithTag("view-navigation-panel").fetchSemanticsNode().boundsInRoot,
        )
        composeRule.onNodeWithContentDescription("Show keyboard")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performTouchInput { click(center) }
        composeRule.onNodeWithTag("view-navigation-pad").assertIsDisplayed()

        // The accessory row is app-owned and remains available above the Android keyboard.
        composeRule.onNodeWithText("Remote Esc").assertIsDisplayed()
        composeRule.onNodeWithText("Ctrl Alt Delete").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide native keyboard")
            .performScrollTo()
            .assertIsDisplayed()
        waitForBottomDockedViewPanelAndStablePreview(previewBeforeKeyboard.height)
        val previewWithKeyboard = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val viewPanelWithKeyboard = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        val keyboardAccessory = composeRule.onNodeWithTag("keyboard-accessory")
            .fetchSemanticsNode().boundsInRoot
        assertViewPanelContained(previewWithKeyboard, viewPanelWithKeyboard)
        assertTrue(
            "The view pad must snap to the preview bottom immediately above the keyboard accessory",
            kotlin.math.abs(viewPanelWithKeyboard.bottom - previewWithKeyboard.bottom) <= 1f &&
                viewPanelWithKeyboard.bottom <= keyboardAccessory.top + 1f,
        )
        assertTrue(
            "Opening the IME must move the preview upward instead of covering it",
            previewWithKeyboard.bottom < previewBeforeKeyboard.bottom,
        )
        val remoteInputCallsBeforeKeyboardPadGesture = backend.remoteInputCallCount
        composeRule.onNodeWithTag("view-navigation-pad").performTouchInput {
            pinch(
                start0 = Offset(centerX - 20f, centerY),
                end0 = Offset(centerX - 48f, centerY),
                start1 = Offset(centerX + 20f, centerY),
                end1 = Offset(centerX + 48f, centerY),
                durationMillis = 350,
            )
        }
        assertEquals(
            "The movable view pad must stay usable without emitting remote input while the IME is open",
            remoteInputCallsBeforeKeyboardPadGesture,
            backend.remoteInputCallCount,
        )
        assertEquals(
            "Using the view pad while typing must preserve the IME-resized preview",
            previewWithKeyboard,
            composeRule.onNodeWithTag("remote-preview").fetchSemanticsNode().boundsInRoot,
        )
        composeRule.onNodeWithContentDescription("Show view controls").performClick()
        composeRule.onNodeWithTag("console-quick-actions").assertDoesNotExist()
        composeRule.onNodeWithTag("view-navigation-controls").assertIsDisplayed()
        assertEquals(
            "Opening View controls while typing must preserve the IME-resized preview",
            previewWithKeyboard,
            composeRule.onNodeWithTag("remote-preview").fetchSemanticsNode().boundsInRoot,
        )
        composeRule.onNodeWithContentDescription("Zoom in")
            .assertIsDisplayed()
            .performClick()
        var imeEditor: View? = null
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.activity.currentFocus?.takeIf { it.onCheckIsTextEditor() }?.also {
                imeEditor = it
            } != null
        }

        // Verify the focused native text editor really forwards InputConnection commits.
        composeRule.runOnIdle {
            val editor = imeEditor
            assertNotNull("The native IME sink should hold focus", editor)
            val editorInfo = EditorInfo()
            val connection = editor!!.onCreateInputConnection(editorInfo)
            assertNotNull("The native IME sink should expose an InputConnection", connection)
            assertEquals(
                InputType.TYPE_CLASS_TEXT,
                editorInfo.inputType and InputType.TYPE_MASK_CLASS,
            )
            assertEquals(
                "The native editor must not advertise a password variation, which disables voice input",
                InputType.TYPE_TEXT_VARIATION_NORMAL,
                editorInfo.inputType and InputType.TYPE_MASK_VARIATION,
            )
            assertTrue(editorInfo.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
            assertTrue(editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
            assertEquals(
                "The native editor must not force IME incognito mode, which can hide voice input",
                0,
                editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            )
            assertTrue(connection!!.commitText("hello from Android", 1))
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            backend.committedText == listOf("hello from Android" to KeyboardLayout.Us)
        }

        composeRule.onNodeWithContentDescription("Hide view controls").performClick()
        composeRule.onNodeWithTag("console-quick-actions").assertIsDisplayed()
        val keyboardAction = composeRule.onNodeWithTag("console-keyboard-action")
        if (
            keyboardAction.fetchSemanticsNode().config[SemanticsProperties.StateDescription] ==
            "Visible"
        ) {
            keyboardAction.performClick()
        }
        composeRule.onNodeWithTag("view-navigation-pad").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open console controls")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performTouchInput { click(center) }
        composeRule.onNodeWithTag("console-control-sheet").assertIsDisplayed()

        val releaseCallsBeforePointerChange = backend.releaseAllCalls
        composeRule.onNodeWithContentDescription("Trackpad pointer")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithContentDescription("Direct pointer")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(
            "Changing pointer mode must release held input",
            releaseCallsBeforePointerChange + 1,
            backend.releaseAllCalls,
        )

        // The movable pad is a saved preference: the sheet can hide and restore it explicitly.
        composeRule.onNodeWithContentDescription("Hide view pad")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("console-control-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("view-navigation-pad").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("Dock view pad")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithTag("console-control-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("view-navigation-pad").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pan view left").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show view controls")
            .assertIsDisplayed()
            .assertHasClickAction()

        val previewWithCompactViewPad = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val compactViewPadBounds = composeRule.onNodeWithTag("view-navigation-pad")
            .fetchSemanticsNode().boundsInRoot
        val remoteInputCallsBeforeViewGesture = backend.remoteInputCallCount
        composeRule.onNodeWithTag("view-navigation-pad").performTouchInput {
            val gestureY = centerY
            pinch(
                start0 = Offset(centerX - 24f, gestureY),
                end0 = Offset(centerX - 88f, gestureY),
                start1 = Offset(centerX + 24f, gestureY),
                end1 = Offset(centerX + 88f, gestureY),
                durationMillis = 500,
            )
        }
        assertEquals(
            "Using the view pad must never send mouse or wheel input to the remote computer",
            remoteInputCallsBeforeViewGesture,
            backend.remoteInputCallCount,
        )

        // The secondary navigation buttons consume no height until explicitly expanded.
        composeRule.onNodeWithContentDescription("Show view controls").performClick()
        composeRule.onNodeWithContentDescription("Hide view controls").assertIsDisplayed()
        composeRule.onNodeWithTag("console-quick-actions").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Pan view left")
            .assertIsDisplayed()
            .assertHasClickAction()
        assertEquals(
            "Opening view controls as a popup must not resize the preview",
            previewWithCompactViewPad,
            composeRule.onNodeWithTag("remote-preview").fetchSemanticsNode().boundsInRoot,
        )
        assertEquals(
            "Opening view controls as a popup must not resize the gesture pad",
            compactViewPadBounds,
            composeRule.onNodeWithTag("view-navigation-pad").fetchSemanticsNode().boundsInRoot,
        )
        composeRule.onNodeWithContentDescription("Fit remote view")
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithContentDescription("Hide view controls").performClick()
        composeRule.onNodeWithTag("console-quick-actions").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pan view left").assertDoesNotExist()
        val previewAfterCollapsingViewControls = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Collapsing secondary view controls must restore the compact preview area",
            previewWithCompactViewPad,
            previewAfterCollapsingViewControls,
        )

        // Two-finger movement on the preview is always a remote wheel, even while zoomed.
        composeRule.onNodeWithTag("remote-input-layer").performTouchInput {
            val startY = height * 0.68f
            val endY = height * 0.30f
            pinch(
                start0 = Offset(centerX - 28f, startY),
                end0 = Offset(centerX - 28f, endY),
                start1 = Offset(centerX + 28f, startY),
                end1 = Offset(centerX + 28f, endY),
                durationMillis = 500,
            )
        }
        composeRule.waitUntil(timeoutMillis = 2_000) { backend.scrollSteps.isNotEmpty() }

        // The movable view pad remains present while the keyboard is shown and after it closes.
        composeRule.onNodeWithContentDescription("Show keyboard").performClick()
        composeRule.onNodeWithTag("view-navigation-pad").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide native keyboard")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("view-navigation-pad").assertIsDisplayed()
    }

    @Test
    fun phoneClipboardIsPreviewedBeforeTypingIntoTheBoundRemote() {
        val clipboard = ClipboardGateway {
            ClipboardReadResult.Available(
                ClipboardPayloadAnalyzer.analyzeDirectPlainText("safe remote text"),
            )
        }
        renderConsole(clipboardGateway = clipboard)

        composeRule.onNodeWithContentDescription("Type phone clipboard").performClick()
        composeRule.onNodeWithText("Type phone clipboard?").assertIsDisplayed()
        composeRule.onNodeWithText("safe remote text").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(backend.pastedText.isEmpty()) }

        composeRule.onNodeWithTag("clipboard-confirm").performClick()

        composeRule.runOnIdle { assertEquals(listOf("safe remote text"), backend.pastedText) }
    }

    @Test
    fun sharedPlainTextOpensTheSamePreviewWithoutAutomaticTyping() {
        val shared = ClipboardPayloadAnalyzer.analyzeDirectPlainText("shared remote text")
        var consumed = false
        renderConsole(
            pendingSharedPaste = shared,
            onSharedPasteConsumed = { consumed = it === shared },
        )

        composeRule.onNodeWithText("Type phone clipboard?").assertIsDisplayed()
        composeRule.onNodeWithText("shared remote text").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(consumed)
            assertTrue(backend.pastedText.isEmpty())
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertTrue(backend.pastedText.isEmpty()) }
    }

    @Test
    fun clipboardApprovalUsesTheKeyboardAccessoryTargetLayout() {
        val clipboard = ClipboardGateway {
            ClipboardReadResult.Available(
                ClipboardPayloadAnalyzer.analyzeDirectPlainText("symbol @"),
            )
        }
        renderConsole(clipboardGateway = clipboard)

        composeRule.onNodeWithContentDescription("Show keyboard").performClick()
        composeRule.onNodeWithText("US").performClick()
        composeRule.onNodeWithText("UK").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Type phone clipboard").performClick()
        composeRule.onNodeWithText("Target keyboard layout: UK").assertIsDisplayed()
        composeRule.onNodeWithTag("clipboard-confirm").performClick()

        composeRule.runOnIdle {
            assertEquals(KeyboardLayout.Uk, backend.pasteRequests.single().keyboardLayout)
        }
    }

    @Test
    fun backgroundSensitiveWorkGenerationClearsAnOpenClipboardPreview() {
        val sensitiveGeneration = mutableLongStateOf(0L)
        val clipboard = ClipboardGateway {
            ClipboardReadResult.Available(
                ClipboardPayloadAnalyzer.analyzeDirectPlainText("temporary preview"),
            )
        }
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
                    clipboardGateway = clipboard,
                    onSharedPasteConsumed = {},
                    sensitiveWorkGeneration = sensitiveGeneration.longValue,
                    onScrollSensitivityChange = {},
                    onMjpegFrameDetectionEnabledChange = {},
                    onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Type phone clipboard").performClick()
        composeRule.onNodeWithText("temporary preview").assertIsDisplayed()

        composeRule.runOnIdle { sensitiveGeneration.longValue++ }

        composeRule.onNodeWithText("Type phone clipboard?").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(backend.pastedText.isEmpty()) }
    }

    @Test
    fun dedicatedScrollPadSendsFourDirectionRemoteScrollAndWorksWithKeyboard() {
        renderConsole()

        val preview = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        assertViewNavigationContentSpansPreview(preview)
        val caret = composeRule.onNodeWithContentDescription("Show view controls")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val scrollPad = composeRule.onNodeWithTag("remote-scroll-pad")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val panel = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        val quickActions = composeRule.onNodeWithTag("console-quick-actions")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The remote scroll pad $scrollPad must occupy the area immediately right of the caret $caret",
            scrollPad.left >= caret.right - 1f && scrollPad.left - caret.right <= 16f,
        )
        assertEquals(
            "The dedicated scroll pad must meet the right edge of the full-width strip",
            preview.right,
            scrollPad.right,
            1f,
        )
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue(scrollPad.width >= 48f * density - 1f)
        assertTrue(scrollPad.height >= 48f * density - 1f)
        assertRectsDoNotOverlap(
            panel,
            quickActions,
            "Keyboard/settings shortcuts must not cover the navigation or scroll pad",
        )

        composeRule.onNodeWithTag("remote-scroll-pad").performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.82f),
                end = Offset(centerX, height * 0.18f),
                durationMillis = 400,
            )
        }
        composeRule.waitUntil(timeoutMillis = 2_000) { backend.scrollSteps.isNotEmpty() }
        assertTrue("An upward swipe must emit positive remote wheel steps", backend.scrollSteps.sum() > 0)

        composeRule.onNodeWithTag("remote-scroll-pad").performTouchInput {
            swipe(
                start = Offset(width * 0.82f, centerY),
                end = Offset(width * 0.18f, centerY),
                durationMillis = 400,
            )
        }
        composeRule.waitUntil(timeoutMillis = 2_000) { backend.horizontalScrollSteps.isNotEmpty() }
        assertTrue(
            "A left swipe must emit positive Shift-wheel compatibility steps",
            backend.horizontalScrollSteps.sum() > 0,
        )

        val preImePreviewHeight = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot.height
        val verticalCountBeforeKeyboard = backend.scrollSteps.size
        val horizontalCountBeforeKeyboard = backend.horizontalScrollSteps.size
        composeRule.onNodeWithContentDescription("Show keyboard").performClick()
        waitForBottomDockedViewPanelAndStablePreview(preImePreviewHeight)
        val imePreview = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        assertViewNavigationContentSpansPreview(imePreview)
        assertRectsDoNotOverlap(
            composeRule.onNodeWithTag("view-navigation-panel").fetchSemanticsNode().boundsInRoot,
            composeRule.onNodeWithTag("console-quick-actions").fetchSemanticsNode().boundsInRoot,
            "Keyboard/settings shortcuts must remain clear of the IME-docked strip",
        )
        composeRule.onNodeWithTag("remote-scroll-pad").assertIsDisplayed().performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.18f),
                end = Offset(centerX, height * 0.82f),
                durationMillis = 400,
            )
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            backend.scrollSteps.size > verticalCountBeforeKeyboard
        }
        assertTrue(
            "A downward swipe must emit negative remote wheel steps",
            backend.scrollSteps.drop(verticalCountBeforeKeyboard).sum() < 0,
        )

        composeRule.onNodeWithTag("remote-scroll-pad").performTouchInput {
            swipe(
                start = Offset(width * 0.18f, centerY),
                end = Offset(width * 0.82f, centerY),
                durationMillis = 400,
            )
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            backend.horizontalScrollSteps.size > horizontalCountBeforeKeyboard
        }
        assertTrue(
            "A right swipe must emit negative compatibility steps while the keyboard is open",
            backend.horizontalScrollSteps.drop(horizontalCountBeforeKeyboard).sum() < 0,
        )
    }

    @Test
    fun scrollPadSensitivityCanBeChangedFromConsoleSettings() {
        val sensitivity = androidx.compose.runtime.mutableFloatStateOf(1f)
        val connectedSession = backend.session.value
        composeRule.setContent {
            NanoKvmTheme {
                ConsoleScreen(
                    profile = profile,
                    session = connectedSession,
                    input = backend,
                    videoSurface = backend,
                    features = backend.features,
                    onDisconnect = {},
                    sessionDraftOwner = sessionDraftOwner,
                    clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                    onSharedPasteConsumed = {},
                    scrollSensitivity = sensitivity.floatValue,
                    onScrollSensitivityChange = { sensitivity.floatValue = it },
                    onMjpegFrameDetectionEnabledChange = {},
                    onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("Scroll sensitivity 1.0×")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Scroll sensitivity").assertIsDisplayed()

        val setProgress = composeRule.onNodeWithTag("scroll-sensitivity-slider")
            .fetchSemanticsNode().config[SemanticsActions.SetProgress]
        var changed = false
        composeRule.runOnIdle { changed = setProgress.action?.invoke(2.5f) == true }
        assertTrue("The sensitivity slider must accept an app setting", changed)
        composeRule.onNodeWithText("Apply").performClick()

        composeRule.runOnIdle { assertEquals(2.5f, sensitivity.floatValue) }
        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("Scroll sensitivity 2.5×")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun movableViewPadLeavesVideoAndRemoteInputAvailableBelowIt() {
        backend.surfaceFillColor = TEST_VIDEO_COLOR
        composeRule.setContent {
            NanoKvmTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = 500.dp)) {
                    RemoteViewport(
                        input = backend,
                        videoSurface = backend,
                        remoteWidth = 360,
                        remoteHeight = 500,
                        videoSurfaceGeneration = 0,
                        pointerMode = PointerMode.Direct,
                        fitRequest = 0,
                        viewNavigationVisible = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { backend.attachSurfaceCalls >= 1 }

        val previewBeforeMove = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val panelBeforeMove = composeRule.onNodeWithTag("view-navigation-panel")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val moveHandle = composeRule.onNodeWithContentDescription("Move pan and zoom pad")
            .assertIsDisplayed()
            .assertHasClickAction()
        val handleBounds = moveHandle.fetchSemanticsNode().boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue(handleBounds.width >= 48f * density - 1f)
        assertTrue(handleBounds.height >= 48f * density - 1f)
        assertViewPanelContained(previewBeforeMove, panelBeforeMove)

        val availableTravel = previewBeforeMove.height - panelBeforeMove.height
        assertTrue("The portrait viewport must leave vertical room to move the pad", availableTravel > 0f)
        val matrixBeforeMove = captureTextureTransform()
        val remoteInputBeforeMove = backend.remoteInputCallCount
        val attachCallsBeforeMove = backend.attachSurfaceCalls
        val detachCallsBeforeMove = backend.detachSurfaceCalls
        moveHandle.performTouchInput {
            swipe(
                start = center,
                end = Offset(centerX, centerY - availableTravel * 0.28f),
                durationMillis = 500,
            )
        }
        composeRule.waitForIdle()

        val previewAfterMove = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val panelAfterMove = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        val matrixAfterMove = captureTextureTransform()
        assertEquals(
            "Moving the overlay must not resize the remote preview",
            previewBeforeMove,
            previewAfterMove,
        )
        assertViewPanelContained(previewAfterMove, panelAfterMove)
        assertTrue(
            "Dragging the move handle must raise the view pad",
            panelAfterMove.top < panelBeforeMove.top - 4f,
        )
        assertTrue(
            "Moving the pad upward must expose remote video and input space below it",
            panelAfterMove.bottom < previewAfterMove.bottom - 4f,
        )
        assertTrue(
            "The moved pad should leave remote video visible above it as well",
            panelAfterMove.top > previewAfterMove.top + 4f,
        )
        assertMatrixEquals(
            "Moving the pad must not pan, zoom, or otherwise transform the video",
            matrixBeforeMove,
            matrixAfterMove,
        )
        assertEquals(remoteInputBeforeMove, backend.remoteInputCallCount)
        assertEquals(attachCallsBeforeMove, backend.attachSurfaceCalls)
        assertEquals(detachCallsBeforeMove, backend.detachSurfaceCalls)

        // The fake video exactly matches this viewport's aspect ratio. PixelCopy therefore proves
        // that the same live TextureView remains visible on both sides of the floating overlay.
        val textureLocation = IntArray(2)
        val textureView = composeRule.runOnIdle {
            findTextureView(composeRule.activity.window.decorView).also {
                assertNotNull("The remote viewport must contain a TextureView", it)
                it!!.getLocationInWindow(textureLocation)
            }!!
        }
        val sampleRootX = panelAfterMove.center.x
        val sampleAboveRootY = (previewAfterMove.top + panelAfterMove.top) / 2f
        val sampleBelowRootY = (panelAfterMove.bottom + previewAfterMove.bottom) / 2f
        val sampleWindowX = (
            textureLocation[0] + sampleRootX - previewAfterMove.left
            ).toInt().coerceIn(
                textureLocation[0],
                textureLocation[0] + textureView.width - 1,
            )
        val sampleAboveWindowY = (
            textureLocation[1] + sampleAboveRootY - previewAfterMove.top
            ).toInt().coerceIn(0, composeRule.activity.window.decorView.height - 1)
        val sampleBelowWindowY = (
            textureLocation[1] + sampleBelowRootY - previewAfterMove.top
            ).toInt().coerceIn(0, composeRule.activity.window.decorView.height - 1)
        val window = captureWindow()
        assertColorNear(TEST_VIDEO_COLOR, window.getPixel(sampleWindowX, sampleAboveWindowY))
        assertColorNear(TEST_VIDEO_COLOR, window.getPixel(sampleWindowX, sampleBelowWindowY))

        // A direct click in the revealed area must still reach the remote input layer.
        val inputBounds = composeRule.onNodeWithTag("remote-input-layer")
            .fetchSemanticsNode().boundsInRoot
        val remoteInputBeforeClick = backend.remoteInputCallCount
        composeRule.onNodeWithTag("remote-input-layer").performTouchInput {
            click(
                Offset(
                    x = centerX,
                    y = sampleBelowRootY - inputBounds.top,
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            backend.remoteInputCallCount > remoteInputBeforeClick
        }
    }

    @Test
    fun keyboardTemporarilyBottomDocksMovableViewPadAndRestoresItsPosition() {
        renderConsole()
        composeRule.onNodeWithContentDescription("Move pan and zoom pad")
            .assertIsDisplayed()
            .assertHasClickAction()

        // Save a normal position distinct from both the keyboard-time bottom and its temporary move.
        performViewPadCustomAction("Move view pad up")
        performViewPadCustomAction("Move view pad up")
        val previewBeforeKeyboard = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val panelBeforeKeyboard = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Two accessibility steps from the bottom should place the pad halfway down",
            0.5f,
            normalizedPanelPosition(previewBeforeKeyboard, panelBeforeKeyboard),
            0.03f,
        )

        composeRule.onNodeWithContentDescription("Show keyboard").performClick()
        waitForBottomDockedViewPanelAndStablePreview(previewBeforeKeyboard.height)
        val previewWithKeyboard = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val bottomDockedPanel = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        val keyboardAccessory = composeRule.onNodeWithTag("keyboard-accessory")
            .fetchSemanticsNode().boundsInRoot
        assertViewPanelContained(previewWithKeyboard, bottomDockedPanel)
        assertEquals(
            "Opening the keyboard must temporarily snap the pad to the resized preview bottom",
            1f,
            normalizedPanelPosition(previewWithKeyboard, bottomDockedPanel),
            0.02f,
        )
        assertTrue(bottomDockedPanel.bottom <= keyboardAccessory.top + 1f)

        val matrixBeforeImeMove = captureTextureTransform()
        val remoteInputBeforeImeMove = backend.remoteInputCallCount
        performViewPadCustomAction("Move view pad up")
        val temporarilyMovedPanel = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        assertViewPanelContained(previewWithKeyboard, temporarilyMovedPanel)
        assertTrue(
            "The move handle must remain usable while the keyboard is open",
            temporarilyMovedPanel.top < bottomDockedPanel.top - 4f,
        )
        assertEquals(
            0.75f,
            normalizedPanelPosition(previewWithKeyboard, temporarilyMovedPanel),
            0.03f,
        )
        assertMatrixEquals(
            "Moving the pad while typing must not transform the remote video",
            matrixBeforeImeMove,
            captureTextureTransform(),
        )
        assertEquals(remoteInputBeforeImeMove, backend.remoteInputCallCount)

        val panelBeforeImePinch = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        val matrixBeforeImePinch = captureTextureTransform()
        composeRule.onNodeWithTag("view-navigation-pad").performTouchInput {
            pinch(
                start0 = Offset(centerX - 24f, centerY),
                end0 = Offset(centerX - 72f, centerY),
                start1 = Offset(centerX + 24f, centerY),
                end1 = Offset(centerX + 72f, centerY),
                durationMillis = 500,
            )
        }
        composeRule.waitForIdle()
        val matrixAfterImePinch = captureTextureTransform()
        assertTrue(
            "Pan and zoom gestures must remain usable while the keyboard is open",
            matrixAfterImePinch[Matrix.MSCALE_X] > matrixBeforeImePinch[Matrix.MSCALE_X] + 0.02f,
        )
        assertEquals(
            "Pan and zoom gestures must not move the floating pad",
            panelBeforeImePinch,
            composeRule.onNodeWithTag("view-navigation-panel").fetchSemanticsNode().boundsInRoot,
        )
        assertEquals(remoteInputBeforeImeMove, backend.remoteInputCallCount)
        composeRule.runOnIdle {
            assertTrue(
                "Moving and using the pad must retain the native IME editor focus",
                composeRule.activity.currentFocus?.onCheckIsTextEditor() == true,
            )
        }

        composeRule.onNodeWithContentDescription("Hide native keyboard")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val preview = composeRule.onNodeWithTag("remote-preview")
                .fetchSemanticsNode().boundsInRoot
            val panel = composeRule.onNodeWithTag("view-navigation-panel")
                .fetchSemanticsNode().boundsInRoot
            kotlin.math.abs(preview.height - previewBeforeKeyboard.height) <= 2f &&
                kotlin.math.abs(normalizedPanelPosition(preview, panel) - 0.5f) <= 0.03f
        }
        val previewAfterKeyboard = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val restoredPanel = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(previewBeforeKeyboard, previewAfterKeyboard)
        assertEquals(
            "Closing the keyboard must restore the saved normal placement, not the temporary IME placement",
            panelBeforeKeyboard.top,
            restoredPanel.top,
            2f,
        )
        assertEquals(
            0.5f,
            normalizedPanelPosition(previewAfterKeyboard, restoredPanel),
            0.03f,
        )
    }

    @Test
    fun controlSheetShowsDiagnosticsWithoutResizingOrRecreatingVideoTarget() {
        renderConsole()
        composeRule.waitUntil(timeoutMillis = 5_000) { backend.attachSurfaceCalls >= 1 }

        // Connected diagnostics are available on demand and never obscure the remote image.
        composeRule.onNodeWithText("Lab NanoKVM").assertDoesNotExist()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.console_message_video_settings_applied),
        ).assertDoesNotExist()
        val previewBeforeSheet = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val actionBounds = composeRule.onNodeWithTag("console-quick-actions")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            actionBounds.left < previewBeforeSheet.right &&
                actionBounds.right <= previewBeforeSheet.right + 1f,
        )

        val density = composeRule.activity.resources.displayMetrics.density
        val minimumTouchTarget = 48f * density
        val keyboardTarget = composeRule.onNodeWithContentDescription("Show keyboard")
            .fetchSemanticsNode().boundsInRoot
        val controlsTarget = composeRule.onNodeWithContentDescription("Open console controls")
            .fetchSemanticsNode().boundsInRoot
        val controlsStatus = composeRule.onNodeWithTag("console-controls-action")
            .assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals(
            "Connected · H.264 direct · 30 fps · 8 ms · Drops 7 · Stalls 2",
            controlsStatus,
        )
        assertTrue(keyboardTarget.width >= minimumTouchTarget - 1f)
        assertTrue(keyboardTarget.height >= minimumTouchTarget - 1f)
        assertTrue(controlsTarget.width >= minimumTouchTarget - 1f)
        assertTrue(controlsTarget.height >= minimumTouchTarget - 1f)

        val attachCallsBeforeSheet = backend.attachSurfaceCalls
        val detachCallsBeforeSheet = backend.detachSurfaceCalls
        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithTag("console-control-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("console-status-summary").assertIsDisplayed()
        composeRule.onNodeWithTag("console-connection-status-label")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Connected", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Lab NanoKVM").assertIsDisplayed()
        composeRule.onNodeWithText("H.264 direct", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Drops 7", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Stalls 2", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.console_message_video_settings_applied),
        ).assertIsDisplayed()
        val previewWithSheet = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "The overlay sheet must not resize the video viewport",
            previewBeforeSheet,
            previewWithSheet,
        )
        assertEquals(
            "Opening console chrome must not recreate the video Surface",
            attachCallsBeforeSheet,
            backend.attachSurfaceCalls,
        )
        assertEquals(
            "Opening console chrome must not detach the video Surface",
            detachCallsBeforeSheet,
            backend.detachSurfaceCalls,
        )

        composeRule.onNodeWithContentDescription("Close controls")
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithTag("console-control-sheet").assertDoesNotExist()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.console_message_video_settings_applied),
        ).assertDoesNotExist()
        assertEquals(previewBeforeSheet, composeRule.onNodeWithTag("remote-preview").fetchSemanticsNode().boundsInRoot)
        assertEquals(attachCallsBeforeSheet, backend.attachSurfaceCalls)
        assertEquals(detachCallsBeforeSheet, backend.detachSurfaceCalls)
    }

    @Test
    fun moreActionsDiscoversVirtualMediaAndWakeOnLanState() {
        renderConsole()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("phase3-virtual-media-action")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("phase3-virtual-media-dialog").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(true, backend.phase3SurfaceIsVisible) }
        composeRule.onNodeWithText("USB HID mode").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("HID only").performScrollTo().performClick()
        composeRule.onNodeWithText("Authenticated session: 7").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(Phase3HidModeSelection.HidOnly), backend.phase3HidModeChanges)
        }
        composeRule.onNodeWithTag("phase3-network-toggle")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Authenticated session: 7").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm").performClick()
        composeRule.runOnIdle { assertEquals(listOf(false), backend.phase3NetworkChanges) }
        composeRule.onNodeWithText("Virtual media device (reported state)")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("phase3-virtual-media-list")
            .performScrollToNode(hasText("installer.iso"))
        composeRule.onNodeWithText("installer.iso").assertIsDisplayed()
        composeRule.onNodeWithText("Currently mounted").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.runOnIdle { assertEquals(false, backend.phase3SurfaceIsVisible) }

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("phase3-wol-action")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("phase3-wol-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Test server").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("00:11:22:33:44:55").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun phase3NoticesStayWithTheirOwningDialog() {
        val virtualMediaNoticeText = composeRule.activity.getString(
            R.string.feature_notice_phase3_guidance_refresh_select,
        )
        val wakeOnLanNoticeText = composeRule.activity.getString(
            R.string.feature_notice_phase3_guidance_mac,
        )
        renderConsole()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("phase3-virtual-media-action").performClick()
        composeRule.onNodeWithText(virtualMediaNoticeText).assertIsDisplayed()
        composeRule.onNodeWithText(wakeOnLanNoticeText).assertDoesNotExist()
        composeRule.onNodeWithText("Close").performClick()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("phase3-wol-action").performClick()
        composeRule.onNodeWithText(wakeOnLanNoticeText).assertIsDisplayed()
        composeRule.onNodeWithText(virtualMediaNoticeText).assertDoesNotExist()
    }

    @Test
    fun moreActionsOpensTheReadOnlyDeviceAndCapabilitySheet() {
        renderConsole()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("device-info-action").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("device-info-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("2.4.3").assertIsDisplayed()
        composeRule.onNodeWithText("NanoKVM-Full").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("192.0.2.250").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectedConsoleOverlaySurvivesStateRestoration() {
        val connectedSession = backend.session.value
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            NanoKvmTheme {
                ConsoleScreen(
                    profile = profile,
                    session = connectedSession,
                    input = backend,
                    videoSurface = backend,
                    features = backend.features,
                    onDisconnect = { backend.disconnectCalls++ },
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
        composeRule.onNodeWithTag("device-info-action").performClick()
        composeRule.onNodeWithTag("device-info-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Console actions").assertDoesNotExist()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("device-info-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Console actions").assertDoesNotExist()
    }

    @Test
    fun immersiveModeIsExplicitAndBackRestoresTheNormalConsole() {
        renderConsole()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("immersive-mode-action")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Exit full screen").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Enter full screen").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun physicalEscapeRestoresNormalConsoleFromImmersiveMode() {
        renderConsole()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("immersive-mode-action")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue(
                composeRule.activity.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE),
                ),
            )
            assertTrue(
                composeRule.activity.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE),
                ),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Enter full screen").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backClosesExpandedViewControlsBeforeRequestingDisconnect() {
        renderConsole()

        composeRule.onNodeWithContentDescription("Show view controls").performClick()
        composeRule.onNodeWithContentDescription("Hide view controls").assertIsDisplayed()
        composeRule.onNodeWithTag("console-quick-actions").assertDoesNotExist()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Show view controls").assertIsDisplayed()
        composeRule.onNodeWithTag("console-quick-actions-icons").assertIsDisplayed()
        composeRule.onNodeWithText("Disconnect from Lab NanoKVM?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, backend.disconnectCalls) }
    }

    @Test
    fun bareConsoleBackRequiresTargetAwareDisconnectConfirmation() {
        renderConsole()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithText("Disconnect from Lab NanoKVM?").assertIsDisplayed()
        composeRule.onNodeWithText("Target: ${profile.authority}", substring = true)
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, backend.disconnectCalls) }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(0, backend.disconnectCalls) }

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag("disconnect-confirmation-action").performClick()
        composeRule.runOnIdle { assertEquals(1, backend.disconnectCalls) }
    }

    @Test
    fun moreActionsDiscoversPicoClawButConsentIsTheFirstProtocolEntry() {
        renderConsole()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("picoclaw-action")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("picoclaw-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("picoclaw-risk-warning").assertIsDisplayed()
        composeRule.onNodeWithText("scheduled cron", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("MCP", substring = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(backend.picoClawSurfaceIsVisible)
            assertEquals(0, backend.picoClawEntryCalls)
        }

        composeRule.onNodeWithTag("picoclaw-consent-enter").performClick()
        composeRule.runOnIdle { assertEquals(1, backend.picoClawEntryCalls) }
    }

    @Test
    fun unsupportedPicoClawIsExplicitAndNeverOffersConsentEntry() {
        backend.mutablePicoClawState.value = PicoClawUiState(
            support = PicoClawSupport.Unsupported,
        )
        renderConsole()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("More actions")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("picoclaw-action").performScrollTo().performClick()

        composeRule.onNodeWithText("requires NanoKVM application 2.4.0", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("picoclaw-consent-enter").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, backend.picoClawEntryCalls) }
    }

    @Test
    fun activePicoClawLockRemainsGloballyDiscoverableOutsideControls() {
        backend.mutablePicoClawState.value = PicoClawUiState(
            support = PicoClawSupport.Supported,
            entered = true,
            manualInput = PicoClawManualInputUiState.Held,
        )
        renderConsole()

        composeRule.onNodeWithTag("picoclaw-global-hid-lock").assertIsDisplayed()
        composeRule.onNodeWithText("PicoClaw controls keyboard and mouse").assertIsDisplayed()
    }

    @Test
    fun lightThemeReconnectAndRetryStatesUseConsoleForegrounds() {
        val sessionState = MutableStateFlow(
            backend.session.value.copy(
                connection = ConnectionState.Reconnecting,
                status = ConsoleMessage.ReconnectAttemptDelayed(
                    attempt = 2,
                    maximumAttempts = 5,
                    delaySeconds = 3,
                ),
                reconnectAttempt = 2,
                reconnectMaximumAttempts = 5,
                nextReconnectDelayMillis = 3_000L,
            ),
        )
        composeRule.setContent {
            val session by sessionState.collectAsState()
            NanoKvmTheme(themeMode = ThemeMode.LIGHT, useDynamicColor = false) {
                ConsoleScreen(
                    profile = profile,
                    session = session,
                    input = backend,
                    videoSurface = backend,
                    features = backend.features,
                    onDisconnect = { backend.disconnectCalls++ },
                    sessionDraftOwner = sessionDraftOwner,
                    clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                    onSharedPasteConsumed = {},
                    onScrollSensitivityChange = {},
                    onMjpegFrameDetectionEnabledChange = {},
                    onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                )
            }
        }

        composeRule.onNodeWithTag("console-transient-status").assertIsDisplayed()
        assertTextColor("Reconnecting", DarkConsoleColorScheme.onSurface)
        assertTextColor("Stop reconnecting", DarkConsoleColorScheme.onSurface)
        assertTextColor("Disconnect", DarkConsoleColorScheme.onActive)
        assertTextColor("Attempt 2 of 5", DarkConsoleColorScheme.onSurface)
        assertTextColor("Next attempt in 3 seconds", DarkConsoleColorScheme.onSurface)
        assertTextColor(
            "Target: ${profile.name} (${profile.authority})",
            DarkConsoleColorScheme.onSurfaceMuted,
        )
        assertTextColor(
            "Reconnect attempt 2 of 5 in 3 seconds",
            DarkConsoleColorScheme.onSurfaceMuted,
        )

        composeRule.runOnIdle {
            sessionState.value = sessionState.value.copy(
                connection = ConnectionState.Failed,
                status = null,
                reconnectAttempt = null,
                reconnectMaximumAttempts = null,
                nextReconnectDelayMillis = null,
            )
        }
        assertTextColor("Connection failed", DarkConsoleColorScheme.onSurface)
        assertTextColor("Retry", DarkConsoleColorScheme.onSurface)
        assertTextColor("Disconnect", DarkConsoleColorScheme.onActive)

        composeRule.runOnIdle {
            sessionState.value = sessionState.value.copy(connection = ConnectionState.Disconnected)
        }
        assertTextColor("Disconnected", DarkConsoleColorScheme.onSurface)
        assertTextColor("Retry", DarkConsoleColorScheme.onSurface)
    }

    @Test
    fun lightThemeDefaultConsoleControlsMeetContrastOnDarkSurfaces() {
        renderConsole(themeMode = ThemeMode.LIGHT, useDynamicColor = false)

        composeRule.onNodeWithContentDescription("Show view controls").performClick()
        assertTextContrast("Fit", DarkConsoleColorScheme.controlSurfaceElevated)
        assertTextContrast("1:1", DarkConsoleColorScheme.controlSurfaceElevated)
        composeRule.onNodeWithContentDescription("Hide view controls").performClick()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithTag("console-pointer-mode-controls").performScrollTo()
        assertTextContrast("Direct pointer", DarkConsoleColorScheme.active)
        assertTextContrast("Trackpad pointer", DarkConsoleColorScheme.controlSurface)
        assertTextContrast("Capture input", DarkConsoleColorScheme.controlSurface)
    }

    @Test
    fun lightThemeDisabledLabelledQuickActionUsesReadableConsoleForeground() {
        val failedSession = backend.session.value.copy(connection = ConnectionState.Failed)
        composeRule.setContent {
            NanoKvmTheme(themeMode = ThemeMode.LIGHT, useDynamicColor = false) {
                Box(Modifier.requiredSize(width = 600.dp, height = 700.dp)) {
                    ConsoleScreen(
                        profile = profile,
                        session = failedSession,
                        input = backend,
                        videoSurface = backend,
                        features = backend.features,
                        onDisconnect = { backend.disconnectCalls++ },
                        sessionDraftOwner = sessionDraftOwner,
                        clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                        onSharedPasteConsumed = {},
                        onScrollSensitivityChange = {},
                        onMjpegFrameDetectionEnabledChange = {},
                        onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("console-quick-actions-labelled").assertIsDisplayed()
        composeRule.onNodeWithTag("console-clipboard-action").assertIsNotEnabled()
        assertTextColor("Clipboard", DarkConsoleColorScheme.onSurfaceMuted)
        assertTextContrast("Clipboard", DarkConsoleColorScheme.controlSurfaceElevated)

        composeRule.onNodeWithTag("console-controls-action").performClick()
        composeRule.onNodeWithTag("console-pointer-mode-controls").performScrollTo()
        composeRule.onNodeWithContentDescription("Capture input").assertIsNotEnabled()
        assertTextColor("Capture input", DarkConsoleColorScheme.onSurfaceMuted)
        assertTextContrast("Capture input", DarkConsoleColorScheme.controlSurface)
        composeRule.onNodeWithContentDescription("Power controls")
            .performScrollTo()
            .assertIsNotEnabled()
        assertTextColor("Power controls", DarkConsoleColorScheme.onSurfaceMuted)
        assertTextContrast("Power controls", DarkConsoleColorScheme.controlSurfaceElevated)
    }

    @Test
    fun controlSheetExposesAccessibleOneShotAuxiliaryMouseButtons() {
        renderConsole(themeMode = ThemeMode.LIGHT, useDynamicColor = false)
        val previewBeforeControls = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithContentDescription("Middle mouse click").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Mouse Back").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Mouse Forward").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithTag("console-control-sheet").assertIsDisplayed()
        // The horizontal rail is nested inside the vertically scrolling adaptive controls.
        // Scroll the rail itself through the vertical ancestor before scrolling its children.
        composeRule.onNodeWithTag("console-mouse-controls")
            .performScrollTo()
        val middleButtonTextLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("Middle mouse click", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(middleButtonTextLayouts)
            }
        assertEquals(
            "Mouse action labels must use the fixed dark-console foreground in a light app theme",
            DarkConsoleColorScheme.onSurface.toArgb(),
            middleButtonTextLayouts.single().layoutInput.style.color.toArgb(),
        )
        listOf("Middle mouse click", "Mouse Back", "Mouse Forward").forEach { description ->
            composeRule.onNodeWithContentDescription(description)
                .performScrollTo()
                .assertHasClickAction()
                .performClick()
        }

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    MouseButton.Middle to true,
                    MouseButton.Middle to false,
                    MouseButton.Back to true,
                    MouseButton.Back to false,
                    MouseButton.Forward to true,
                    MouseButton.Forward to false,
                ),
                backend.mouseButtonEvents,
            )
        }
        composeRule.onNodeWithTag("console-control-sheet").assertIsDisplayed()
        assertEquals(
            "Auxiliary mouse controls must remain temporary chrome over the full preview",
            previewBeforeControls,
            composeRule.onNodeWithTag("remote-preview").fetchSemanticsNode().boundsInRoot,
        )
    }

    @Test
    fun compactLandscapeEmbeddedActionsAndControlSheetRemainUsable() {
        renderConsole(compactLandscape = true)

        composeRule.onNodeWithTag("view-navigation-pad").assertIsDisplayed()
        val preview = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val panelAtBottom = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        assertViewPanelContained(preview, panelAtBottom)
        performViewPadCustomAction("Move view pad up")
        val raisedPanel = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        assertViewPanelContained(preview, raisedPanel)
        assertTrue(
            "The view pad must move upward and expose space below even in compact landscape",
            raisedPanel.top < panelAtBottom.top - 2f && raisedPanel.bottom < preview.bottom - 2f,
        )
        performViewPadCustomAction("Move view pad down")
        val returnedPanel = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        assertViewPanelContained(preview, returnedPanel)
        assertViewNavigationContentSpansPreview(preview)
        assertEquals(panelAtBottom.top, returnedPanel.top, 2f)
        val actions = composeRule.onNodeWithTag("console-quick-actions")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Compact shortcuts must remain inside the preview",
            actions.left >= preview.left - 1f &&
                actions.top >= preview.top - 1f &&
                actions.right <= preview.right + 1f &&
                actions.bottom <= preview.bottom + 1f,
        )
        assertRectsDoNotOverlap(
            returnedPanel,
            actions,
            "Compact shortcuts must not obscure the full-width navigation strip",
        )

        composeRule.onNodeWithContentDescription("Show keyboard")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithTag("console-control-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("console-control-scrim")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertContentDescriptionEquals("Close controls")
        composeRule.onNodeWithTag("console-pointer-mode-controls").performScrollTo()
        composeRule.onNodeWithContentDescription("Direct pointer")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithContentDescription("Trackpad pointer")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide view pad")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("view-navigation-pad").assertDoesNotExist()
        composeRule.onNodeWithTag("console-quick-actions").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("Dock view pad")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("view-navigation-pad").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show view controls").performClick()
        composeRule.onNodeWithContentDescription("Fit remote view")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
    }

    @Test
    fun viewNavigationPadPinchAndDragChangeTheVideoTransformWithoutRemoteInput() {
        val remoteWidth = mutableIntStateOf(1920)
        val remoteHeight = mutableIntStateOf(1080)
        val fitRequest = mutableIntStateOf(0)
        composeRule.setContent {
            NanoKvmTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = 500.dp)) {
                    RemoteViewport(
                        input = backend,
                        videoSurface = backend,
                        remoteWidth = remoteWidth.intValue,
                        remoteHeight = remoteHeight.intValue,
                        videoSurfaceGeneration = 0,
                        pointerMode = PointerMode.Direct,
                        fitRequest = fitRequest.intValue,
                        viewNavigationVisible = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { backend.attachSurfaceCalls >= 1 }

        val remoteInputCallsBeforeGestures = backend.remoteInputCallCount
        val panelBeforeGestures = composeRule.onNodeWithTag("view-navigation-panel")
            .fetchSemanticsNode().boundsInRoot
        val beforePinch = captureTextureTransform()
        composeRule.onNodeWithTag("view-navigation-pad").performTouchInput {
            pinch(
                start0 = Offset(centerX - 24f, centerY),
                end0 = Offset(centerX - 104f, centerY),
                start1 = Offset(centerX + 24f, centerY),
                end1 = Offset(centerX + 104f, centerY),
                durationMillis = 500,
            )
        }
        composeRule.waitForIdle()
        val afterPinch = captureTextureTransform()
        assertTrue(
            "Pinching the view pad must increase the video transform scale",
            afterPinch[Matrix.MSCALE_X] > beforePinch[Matrix.MSCALE_X] + 0.05f &&
                afterPinch[Matrix.MSCALE_Y] > beforePinch[Matrix.MSCALE_Y] + 0.05f,
        )
        assertEquals(
            "Pinching the view must not reposition its movable control panel",
            panelBeforeGestures,
            composeRule.onNodeWithTag("view-navigation-panel").fetchSemanticsNode().boundsInRoot,
        )

        // Replacing the dimensions resets the active transform. The pad gesture callback must
        // continue mutating that active state rather than a state captured by an earlier layout.
        composeRule.runOnIdle {
            remoteWidth.intValue = 1366
            remoteHeight.intValue = 768
            fitRequest.intValue++
        }
        composeRule.waitForIdle()
        val afterDimensionChange = captureTextureTransform()
        assertTrue(
            "Changing dimensions and fitting must reset the first pinch",
            afterDimensionChange[Matrix.MSCALE_X] < afterPinch[Matrix.MSCALE_X] - 0.05f &&
                afterDimensionChange[Matrix.MSCALE_Y] < afterPinch[Matrix.MSCALE_Y] - 0.05f,
        )

        // Separation grows only 1.4x over two seconds. Its individual injected deltas are below
        // the former 0.8% per-event cutoff, so they must accumulate into a visible final zoom.
        composeRule.onNodeWithTag("view-navigation-pad").performTouchInput {
            pinch(
                start0 = Offset(centerX - 80f, centerY),
                end0 = Offset(centerX - 112f, centerY),
                start1 = Offset(centerX + 80f, centerY),
                end1 = Offset(centerX + 112f, centerY),
                durationMillis = 2_000,
            )
        }
        composeRule.waitForIdle()
        val afterSlowPinch = captureTextureTransform()
        assertTrue(
            "A slow pinch must accumulate into a video transform scale change after dimensions " +
                "change (before=${afterDimensionChange[Matrix.MSCALE_X]}x" +
                "${afterDimensionChange[Matrix.MSCALE_Y]}, " +
                "after=${afterSlowPinch[Matrix.MSCALE_X]}x${afterSlowPinch[Matrix.MSCALE_Y]})",
            afterSlowPinch[Matrix.MSCALE_X] > afterDimensionChange[Matrix.MSCALE_X] + 0.02f &&
                afterSlowPinch[Matrix.MSCALE_Y] > afterDimensionChange[Matrix.MSCALE_Y] + 0.02f,
        )

        composeRule.onNodeWithTag("view-navigation-pad").performTouchInput {
            swipe(
                start = Offset(width * 0.30f, centerY),
                end = Offset(width * 0.65f, centerY),
                durationMillis = 500,
            )
        }
        composeRule.waitForIdle()
        val afterDrag = captureTextureTransform()
        assertEquals(afterSlowPinch[Matrix.MSCALE_X], afterDrag[Matrix.MSCALE_X], 0.01f)
        assertEquals(afterSlowPinch[Matrix.MSCALE_Y], afterDrag[Matrix.MSCALE_Y], 0.01f)
        assertTrue(
            "Dragging the view pad must translate the zoomed video",
            kotlin.math.abs(afterDrag[Matrix.MTRANS_X] - afterSlowPinch[Matrix.MTRANS_X]) > 4f ||
                kotlin.math.abs(afterDrag[Matrix.MTRANS_Y] - afterSlowPinch[Matrix.MTRANS_Y]) > 4f,
        )
        assertEquals(
            "Panning the remote view must not drag the navigation panel itself",
            panelBeforeGestures,
            composeRule.onNodeWithTag("view-navigation-panel").fetchSemanticsNode().boundsInRoot,
        )

        composeRule.onNodeWithContentDescription("Show view controls").performClick()
        composeRule.onNodeWithContentDescription("Zoom in").performClick()
        composeRule.waitForIdle()
        val afterButtonZoom = captureTextureTransform()
        assertTrue(
            "View-control callbacks must target the replacement transform after dimensions change",
            afterButtonZoom[Matrix.MSCALE_X] > afterDrag[Matrix.MSCALE_X] + 0.02f &&
                afterButtonZoom[Matrix.MSCALE_Y] > afterDrag[Matrix.MSCALE_Y] + 0.02f,
        )
        assertEquals(
            "View navigation gestures must not send mouse or wheel input to the remote computer",
            remoteInputCallsBeforeGestures,
            backend.remoteInputCallCount,
        )
    }

    @Test
    fun handledFitDoesNotReplayAndEraseLaterViewportTransformAfterStateRestoration() {
        val connectedSession = backend.session.value
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            NanoKvmTheme {
                ConsoleScreen(
                    profile = profile,
                    session = connectedSession,
                    input = backend,
                    videoSurface = backend,
                    features = backend.features,
                    onDisconnect = { backend.disconnectCalls++ },
                    sessionDraftOwner = sessionDraftOwner,
                    clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                    onSharedPasteConsumed = {},
                    onScrollSensitivityChange = {},
                    onMjpegFrameDetectionEnabledChange = {},
                    onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { backend.attachSurfaceCalls >= 1 }

        composeRule.onNodeWithContentDescription("Show view controls").performClick()
        composeRule.onNodeWithContentDescription("Fit remote view").performClick()
        composeRule.waitForIdle()
        val fittedTransform = captureTextureTransform()

        composeRule.onNodeWithTag("view-navigation-pad").performTouchInput {
            pinch(
                start0 = Offset(centerX - 24f, centerY),
                end0 = Offset(centerX - 104f, centerY),
                start1 = Offset(centerX + 24f, centerY),
                end1 = Offset(centerX + 104f, centerY),
                durationMillis = 500,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("view-navigation-pad").performTouchInput {
            swipe(
                start = Offset(width * 0.30f, height * 0.70f),
                end = Offset(width * 0.65f, height * 0.35f),
                durationMillis = 500,
            )
        }
        composeRule.waitForIdle()
        val transformedBeforeRestore = captureTextureTransform()
        assertTrue(
            "The test must establish a zoomed viewport after the handled Fit request",
            transformedBeforeRestore[Matrix.MSCALE_X] > fittedTransform[Matrix.MSCALE_X] + 0.05f &&
                transformedBeforeRestore[Matrix.MSCALE_Y] > fittedTransform[Matrix.MSCALE_Y] + 0.05f,
        )
        assertTrue(
            "The test must establish a panned viewport after the handled Fit request",
            kotlin.math.abs(
                transformedBeforeRestore[Matrix.MTRANS_X] - fittedTransform[Matrix.MTRANS_X],
            ) > 4f || kotlin.math.abs(
                transformedBeforeRestore[Matrix.MTRANS_Y] - fittedTransform[Matrix.MTRANS_Y],
            ) > 4f,
        )

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertMatrixEquals(
            message = "Recreation must not replay the already-handled Fit request",
            expected = transformedBeforeRestore,
            actual = captureTextureTransform(),
            tolerance = 0.02f,
        )
    }

    @Test
    fun changingSurfaceGenerationRecreatesTheVideoTarget() {
        val generation = mutableLongStateOf(0L)
        composeRule.setContent {
            NanoKvmTheme {
                RemoteViewport(
                    input = backend,
                    videoSurface = backend,
                    remoteWidth = 1920,
                    remoteHeight = 1080,
                    videoSurfaceGeneration = generation.longValue,
                    pointerMode = PointerMode.Direct,
                    fitRequest = 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { backend.attachSurfaceCalls >= 1 }
        val originalAttachCount = backend.attachSurfaceCalls

        composeRule.runOnIdle { generation.longValue++ }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            backend.attachSurfaceCalls > originalAttachCount && backend.detachSurfaceCalls >= 1
        }
    }

    @Test
    fun changingReportedVideoSizeKeepsTheFirstSurfaceVisible() {
        val remoteWidth = mutableIntStateOf(1920)
        val remoteHeight = mutableIntStateOf(1080)
        backend.surfaceFillColor = TEST_VIDEO_COLOR
        composeRule.setContent {
            NanoKvmTheme {
                RemoteViewport(
                    input = backend,
                    videoSurface = backend,
                    remoteWidth = remoteWidth.intValue,
                    remoteHeight = remoteHeight.intValue,
                    videoSurfaceGeneration = 0,
                    pointerMode = PointerMode.Direct,
                    fitRequest = 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { backend.attachSurfaceCalls == 1 }

        // The decoder commonly replaces the guessed 1080p dimensions after its first SPS.
        composeRule.runOnIdle {
            remoteWidth.intValue = 1366
            remoteHeight.intValue = 768
        }
        composeRule.waitForIdle()

        val textureView = composeRule.runOnIdle {
            findTextureView(composeRule.activity.window.decorView)
        }
        assertNotNull("The console must keep its TextureView after a size report", textureView)
        val location = IntArray(2)
        composeRule.runOnIdle { textureView!!.getLocationInWindow(location) }
        val window = captureWindow()
        val sampleY = (location[1] + textureView!!.height / 2).coerceIn(0, window.height - 1)
        val sampleLeft = (location[0] + textureView.width / 4).coerceIn(0, window.width - 1)
        val sampleRight = (location[0] + textureView.width * 3 / 4).coerceIn(0, window.width - 1)
        assertColorNear(TEST_VIDEO_COLOR, window.getPixel(sampleLeft, sampleY))
        assertColorNear(TEST_VIDEO_COLOR, window.getPixel(sampleRight, sampleY))
        assertEquals("A resolution report must not recreate the video Surface", 1, backend.attachSurfaceCalls)
    }

    @Test
    fun videoSettingsReflectCurrentSessionAndApplyTypedTransport() {
        renderConsole()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.console_message_video_settings_applied),
        ).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("Video settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("MJPEG").assertIsSelected()
        composeRule.onNodeWithText("WebRTC").assertIsDisplayed()
        composeRule.onNodeWithText("600p").assertIsSelected()
        composeRule.onNodeWithText("24 fps").assertIsSelected()
        composeRule.onNodeWithText("WebRTC").performClick()
        composeRule.onNodeWithText("720p").performClick()
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.onNodeWithTag("remote-preview").assertExists()

        composeRule.runOnIdle {
            assertEquals(
                VideoSettings(
                    transportPreference = VideoTransportPreference.WEBRTC,
                    resolutionHeight = 720,
                    framesPerSecond = 24,
                    bitrateKbps = 2_000,
                    jpegQuality = 60,
                ),
                backend.lastVideoSettings,
            )
        }
    }

    @Test
    fun frameDetectionPreferenceIsStagedUntilApplyAndDispatchedOnce() {
        val frameDetectionChanges = mutableListOf<Boolean>()
        renderConsole(
            mjpegFrameDetectionEnabled = false,
            onMjpegFrameDetectionEnabledChange = frameDetectionChanges::add,
        )

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("Video settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("mjpegFrameDetectionToggle")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        composeRule.runOnIdle { assertEquals(emptyList<Boolean>(), frameDetectionChanges) }

        composeRule.onNodeWithText("Apply").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(true), frameDetectionChanges)
            assertNull(backend.lastVideoSettings)
        }
    }

    @Test
    fun openingAndClosingAdaptiveControlsKeepsTheVideoSurfaceAttached() {
        renderConsole()
        composeRule.waitUntil(timeoutMillis = 5_000) { backend.attachSurfaceCalls == 1 }
        val originalAttachCount = backend.attachSurfaceCalls
        val originalDetachCount = backend.detachSurfaceCalls

        composeRule.onNodeWithContentDescription("Open console controls")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("console-control-sheet").assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals(originalAttachCount, backend.attachSurfaceCalls)
        assertEquals(originalDetachCount, backend.detachSurfaceCalls)

        composeRule.onNodeWithContentDescription("Close controls")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("console-control-sheet").assertDoesNotExist()
        composeRule.waitForIdle()
        assertEquals(
            "Changing console chrome must not recreate the video Surface",
            originalAttachCount,
            backend.attachSurfaceCalls,
        )
        assertEquals(originalDetachCount, backend.detachSurfaceCalls)
    }

    @Test
    fun resizingAcrossExpandedBreakpointMovesConsoleWithoutReattachingVideoSurface() {
        val layoutWidth = mutableIntStateOf(500)
        composeRule.setContent {
            val session by backend.session.collectAsState()
            NanoKvmTheme {
                Box(
                    Modifier.requiredSize(
                        width = layoutWidth.intValue.dp,
                        height = 700.dp,
                    ),
                ) {
                    ConsoleScreen(
                        profile = profile,
                        session = session,
                        input = backend,
                        videoSurface = backend,
                        features = backend.features,
                        onDisconnect = { backend.disconnectCalls++ },
                        sessionDraftOwner = sessionDraftOwner,
                        clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                        onSharedPasteConsumed = {},
                        onScrollSensitivityChange = {},
                        onMjpegFrameDetectionEnabledChange = {},
                        onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("console-layout-single-pane").assertIsDisplayed()
        composeRule.onNodeWithTag("console-quick-actions-icons").assertIsDisplayed()
        composeRule.onNodeWithTag("console-quick-actions-labelled").assertDoesNotExist()
        composeRule.onNodeWithText("Keyboard").assertDoesNotExist()
        composeRule.onNodeWithText("Clipboard").assertDoesNotExist()
        composeRule.onNodeWithText("Controls").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000) { backend.attachSurfaceCalls == 1 }
        val compactPreview = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        val originalTextureView = composeRule.runOnIdle {
            findTextureView(composeRule.activity.window.decorView)
        }
        assertNotNull(originalTextureView)
        val originalAttachCount = backend.attachSurfaceCalls
        val originalDetachCount = backend.detachSurfaceCalls

        composeRule.runOnIdle { layoutWidth.intValue = 900 }
        composeRule.onNodeWithTag("console-layout-supporting-pane").assertExists()
        composeRule.onNodeWithTag("console-quick-actions-icons").assertDoesNotExist()
        composeRule.onNodeWithTag("console-quick-actions-labelled").assertIsDisplayed()
        composeRule.onNodeWithText("Keyboard").assertExists()
        composeRule.onNodeWithText("Clipboard").assertExists()
        composeRule.onNodeWithText("Controls").assertExists()
        composeRule.waitForIdle()
        val expandedPreview = composeRule.onNodeWithTag("remote-preview")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The test host must really cross into a wider expanded composition",
            expandedPreview.width > compactPreview.width + 100f,
        )
        assertEquals(
            "Entering an expanded supporting-pane layout must retain the decoder Surface",
            originalAttachCount,
            backend.attachSurfaceCalls,
        )
        assertEquals(originalDetachCount, backend.detachSurfaceCalls)
        assertSame(
            originalTextureView,
            composeRule.runOnIdle { findTextureView(composeRule.activity.window.decorView) },
        )

        composeRule.runOnIdle { layoutWidth.intValue = 600 }
        composeRule.onNodeWithTag("console-layout-single-pane").assertExists()
        composeRule.onNodeWithTag("console-quick-actions-icons").assertDoesNotExist()
        composeRule.onNodeWithTag("console-quick-actions-labelled").assertIsDisplayed()
        composeRule.onNodeWithText("Keyboard").assertExists()
        composeRule.onNodeWithText("Clipboard").assertExists()
        composeRule.onNodeWithText("Controls").assertExists()
        composeRule.waitForIdle()
        assertEquals(
            "Returning from the expanded breakpoint must retain the decoder Surface",
            originalAttachCount,
            backend.attachSurfaceCalls,
        )
        assertEquals(originalDetachCount, backend.detachSurfaceCalls)
        assertSame(
            originalTextureView,
            composeRule.runOnIdle { findTextureView(composeRule.activity.window.decorView) },
        )
    }

    @Test
    fun expandedConsoleAdaptsAcrossCompactHeightWithoutReattachingVideoSurface() {
        val layoutHeight = mutableIntStateOf(700)
        composeRule.setContent {
            val session by backend.session.collectAsState()
            NanoKvmTheme {
                Box(
                    Modifier.requiredSize(
                        width = 900.dp,
                        height = layoutHeight.intValue.dp,
                    ),
                ) {
                    ConsoleScreen(
                        profile = profile,
                        session = session,
                        input = backend,
                        videoSurface = backend,
                        features = backend.features,
                        onDisconnect = { backend.disconnectCalls++ },
                        sessionDraftOwner = sessionDraftOwner,
                        clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                        onSharedPasteConsumed = {},
                        onScrollSensitivityChange = {},
                        onMjpegFrameDetectionEnabledChange = {},
                        onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("console-layout-supporting-pane").assertExists()
        composeRule.waitUntil(timeoutMillis = 5_000) { backend.attachSurfaceCalls == 1 }
        val originalTextureView = composeRule.runOnIdle {
            findTextureView(composeRule.activity.window.decorView)
        }
        assertNotNull(originalTextureView)
        val originalAttachCount = backend.attachSurfaceCalls
        val originalDetachCount = backend.detachSurfaceCalls

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithTag("console-control-sheet").assertIsDisplayed()

        composeRule.runOnIdle { layoutHeight.intValue = 479 }
        composeRule.onNodeWithTag("console-layout-single-pane").assertExists()
        composeRule.onNodeWithTag("console-control-scrim").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Power controls")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(originalAttachCount, backend.attachSurfaceCalls)
        assertEquals(originalDetachCount, backend.detachSurfaceCalls)
        assertSame(
            originalTextureView,
            composeRule.runOnIdle { findTextureView(composeRule.activity.window.decorView) },
        )

        composeRule.runOnIdle { layoutHeight.intValue = 700 }
        composeRule.onNodeWithTag("console-layout-supporting-pane").assertExists()
        composeRule.onNodeWithTag("console-control-scrim").assertDoesNotExist()
        composeRule.onNodeWithTag("console-control-sheet").assertIsDisplayed()
        assertEquals(originalAttachCount, backend.attachSurfaceCalls)
        assertEquals(originalDetachCount, backend.detachSurfaceCalls)
        assertSame(
            originalTextureView,
            composeRule.runOnIdle { findTextureView(composeRule.activity.window.decorView) },
        )
    }

    @Test
    fun twoHundredPercentTextKeepsPrimaryAndRecoveryActionsReadable() {
        @Suppress("UNCHECKED_CAST")
        val mutableSession = backend.session as MutableStateFlow<BackendSession>
        composeRule.setContent {
            val session by mutableSession.collectAsState()
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2f),
            ) {
                NanoKvmTheme {
                    Box(Modifier.requiredSize(width = 411.dp, height = 700.dp)) {
                        ConsoleScreen(
                            profile = profile,
                            session = session,
                            input = backend,
                            videoSurface = backend,
                            features = backend.features,
                            onDisconnect = { backend.disconnectCalls++ },
                            sessionDraftOwner = sessionDraftOwner,
                            clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                            onSharedPasteConsumed = {},
                            onScrollSensitivityChange = {},
                            onMjpegFrameDetectionEnabledChange = {},
                            onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("console-quick-actions-icons").assertIsDisplayed()
        composeRule.onNodeWithTag("console-quick-actions-labelled").assertDoesNotExist()
        composeRule.onNodeWithText("Keyboard").assertDoesNotExist()
        composeRule.onNodeWithText("Clipboard").assertDoesNotExist()
        composeRule.onNodeWithText("Controls").assertDoesNotExist()
        val preview = composeRule.onNodeWithTag("remote-preview").fetchSemanticsNode().boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        val quickActionStrip = composeRule.onNodeWithTag("console-quick-actions")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(quickActionStrip.width <= 144f * density + 1f)
        assertTrue(quickActionStrip.height <= 48f * density + 1f)
        val quickActions = listOf(
            composeRule.onNodeWithTag("console-keyboard-action")
                .fetchSemanticsNode().boundsInRoot,
            composeRule.onNodeWithTag("console-clipboard-action")
                .fetchSemanticsNode().boundsInRoot,
            composeRule.onNodeWithTag("console-controls-action")
                .fetchSemanticsNode().boundsInRoot,
        )
        quickActions.forEach { bounds ->
            assertTrue(bounds.left >= preview.left - 1f)
            assertTrue(bounds.right <= preview.right + 1f)
            assertTrue(bounds.top >= preview.top - 1f)
            assertTrue(bounds.bottom <= preview.bottom + 1f)
            assertTrue(bounds.width >= 48f * density - 1f)
            assertTrue(bounds.height >= 48f * density - 1f)
        }
        quickActions.zipWithNext().forEach { (first, second) ->
            assertTrue("Large-text quick actions must not overlap", first.right <= second.left + 1f)
        }
        composeRule.onNodeWithContentDescription("Show view controls").performClick()
        composeRule.onNodeWithTag("console-quick-actions").assertDoesNotExist()
        composeRule.onNodeWithTag("view-navigation-controls").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide view controls").performClick()
        composeRule.onNodeWithTag("console-quick-actions-icons").assertIsDisplayed()

        composeRule.runOnIdle {
            mutableSession.value = mutableSession.value.copy(connection = ConnectionState.Failed)
        }
        composeRule.onNodeWithTag("retry-connection-action").assertIsDisplayed()
        composeRule.onNodeWithTag("transient-disconnect-action").assertIsDisplayed()
        val retry = composeRule.onNodeWithTag("retry-connection-action")
            .fetchSemanticsNode().boundsInRoot
        val disconnect = composeRule.onNodeWithTag("transient-disconnect-action")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Large-text recovery actions must not overlap", retry.bottom <= disconnect.top + 1f)
        assertTrue(retry.left >= preview.left - 1f && retry.right <= preview.right + 1f)
        assertTrue(disconnect.left >= preview.left - 1f && disconnect.right <= preview.right + 1f)
    }

    @Test
    fun pendingDestructivePowerConfirmationIsInvalidatedBySessionChanges() {
        @Suppress("UNCHECKED_CAST")
        val mutableSession = backend.session as MutableStateFlow<BackendSession>
        composeRule.setContent {
            val session by mutableSession.collectAsState()
            NanoKvmTheme {
                ConsoleScreen(
                    profile = profile,
                    session = session,
                    input = backend,
                    videoSurface = backend,
                    features = backend.features,
                    onDisconnect = { backend.disconnectCalls++ },
                    sessionDraftOwner = sessionDraftOwner,
                    clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                    onSharedPasteConsumed = {},
                    onScrollSensitivityChange = {},
                    onMjpegFrameDetectionEnabledChange = {},
                    onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                )
            }
        }

        openResetConfirmation()
        composeRule.runOnIdle {
            mutableSession.value = mutableSession.value.copy(
                sessionGeneration = mutableSession.value.sessionGeneration + 1,
            )
        }
        composeRule.onNodeWithText("Reset the host now?").assertDoesNotExist()
        composeRule.onNodeWithText("Reset host").assertDoesNotExist()

        openResetConfirmation()
        composeRule.runOnIdle {
            mutableSession.value = mutableSession.value.copy(
                connection = ConnectionState.Reconnecting,
            )
        }
        composeRule.onNodeWithText("Reset the host now?").assertDoesNotExist()
        composeRule.onNodeWithText("Reset host").assertDoesNotExist()

        composeRule.runOnIdle {
            mutableSession.value = mutableSession.value.copy(
                connection = ConnectionState.Connected,
                sessionGeneration = mutableSession.value.sessionGeneration + 1,
            )
            assertTrue(
                "A stale confirmation must never dispatch a destructive command to the new session",
                backend.powerActions.isEmpty(),
            )
        }
    }

    @Test
    fun destructivePowerControlsAreDisabledWhenTheSessionIsNotUsable() {
        @Suppress("UNCHECKED_CAST")
        val mutableSession = backend.session as MutableStateFlow<BackendSession>
        mutableSession.value = mutableSession.value.copy(connection = ConnectionState.Failed)
        renderConsole()

        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("Power controls")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Press power button").assertDoesNotExist()
    }

    @Test
    fun destructivePowerConfirmationNamesAndBindsTheExactDestination() {
        renderConsole()

        openResetConfirmation()
        composeRule.onNodeWithText("Reset host").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(PowerAction.Reset), backend.powerActions)
            assertEquals(1, backend.powerDestinations.size)
            assertEquals(profile.id, backend.powerDestinations.single().profileId)
            assertEquals(profile.authority, backend.powerDestinations.single().authority)
            assertEquals(
                backend.session.value.sessionGeneration,
                backend.powerDestinations.single().sessionGeneration,
            )
        }
        composeRule.onNodeWithText("Reset the host now?").assertDoesNotExist()
    }

    private fun openResetConfirmation() {
        composeRule.onNodeWithContentDescription("Open console controls").performClick()
        composeRule.onNodeWithContentDescription("Power controls")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Reset button").performClick()
        composeRule.onNodeWithText("Reset the host now?").assertIsDisplayed()
        composeRule.onNodeWithText("Target: Lab NanoKVM (${profile.authority})").assertIsDisplayed()
        composeRule.onNodeWithText("Reset host").assertIsDisplayed()
    }

    private fun assertTextColor(
        text: String,
        expected: androidx.compose.ui.graphics.Color,
    ) {
        val actual = resolvedTextColor(text)
        assertEquals(
            "'$text' must resolve from the fixed console palette",
            expected.toArgb(),
            actual.toArgb(),
        )
    }

    private fun assertTextContrast(
        text: String,
        background: androidx.compose.ui.graphics.Color,
        minimum: Double = 4.5,
    ) {
        val foreground = resolvedTextColor(text)
        val contrast = colorContrastRatio(foreground.toArgb(), background.toArgb())
        assertTrue(
            "'$text' contrast was $contrast against ${background.toArgb().toUInt().toString(16)}; " +
                "expected at least $minimum",
            contrast >= minimum,
        )
    }

    private fun resolvedTextColor(text: String): androidx.compose.ui.graphics.Color {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(layouts)
            }
        assertEquals("Expected one text layout for '$text'", 1, layouts.size)
        return layouts.single().layoutInput.style.color
    }

    private fun performViewPadCustomAction(label: String) {
        val actions = composeRule.onNodeWithTag("view-navigation-move-handle")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val requestedAction = actions.firstOrNull { it.label == label }
        assertNotNull("Expected accessibility action '$label' on the view-pad move handle", requestedAction)
        var actionResult = false
        composeRule.runOnIdle { actionResult = requestedAction!!.action() }
        assertTrue("Accessibility action '$label' should move the view pad", actionResult)
        composeRule.waitForIdle()
    }

    private fun assertViewPanelContained(preview: Rect, panel: Rect) {
        assertTrue(
            "View pad $panel must remain within remote preview $preview",
            panel.left >= preview.left - 1f &&
                panel.top >= preview.top - 1f &&
                panel.right <= preview.right + 1f &&
                panel.bottom <= preview.bottom + 1f,
        )
        assertEquals(
            "The view pad must meet the preview's left edge",
            preview.left,
            panel.left,
            1f,
        )
        assertEquals(
            "The view pad must meet the preview's right edge",
            preview.right,
            panel.right,
            1f,
        )
        assertViewNavigationContentSpansPreview(preview)
    }

    private fun assertViewNavigationContentSpansPreview(preview: Rect) {
        val content = composeRule.onNodeWithTag("view-navigation-content")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "The visible navigation strip must meet the preview's left edge",
            preview.left,
            content.left,
            1f,
        )
        assertEquals(
            "The visible navigation strip must meet the preview's right edge",
            preview.right,
            content.right,
            1f,
        )
    }

    private fun assertRectsDoNotOverlap(first: Rect, second: Rect, message: String) {
        assertTrue(
            "$message: $first vs $second",
            first.right <= second.left + 1f ||
                second.right <= first.left + 1f ||
                first.bottom <= second.top + 1f ||
                second.bottom <= first.top + 1f,
        )
    }

    private fun waitForBottomDockedViewPanelAndStablePreview(preImePreviewHeight: Float) {
        var previousHeight = Float.NaN
        var stableSince = 0L
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val preview = composeRule.onNodeWithTag("remote-preview")
                .fetchSemanticsNode().boundsInRoot
            val panel = composeRule.onNodeWithTag("view-navigation-panel")
                .fetchSemanticsNode().boundsInRoot
            val now = SystemClock.uptimeMillis()
            val imeVisible = ViewCompat.getRootWindowInsets(
                composeRule.activity.window.decorView,
            )?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val previewResized = preview.height < preImePreviewHeight - 2f
            val docked = kotlin.math.abs(panel.bottom - preview.bottom) <= 2f
            if (
                !imeVisible || !previewResized || !docked || previousHeight.isNaN() ||
                kotlin.math.abs(preview.height - previousHeight) > 1f
            ) {
                stableSince = now
            }
            previousHeight = preview.height
            imeVisible && previewResized && docked && now - stableSince >= 250L
        }
    }

    private fun normalizedPanelPosition(preview: Rect, panel: Rect): Float {
        val travel = preview.height - panel.height
        assertTrue("The preview must be taller than the movable view pad", travel > 0f)
        return ((panel.top - preview.top) / travel).coerceIn(0f, 1f)
    }

    private fun assertMatrixEquals(
        message: String,
        expected: FloatArray,
        actual: FloatArray,
        tolerance: Float = 0.001f,
    ) {
        assertEquals("Matrix lengths differ", expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("$message (matrix index $index)", expected[index], actual[index], tolerance)
        }
    }

    private fun renderConsole(
        compactLandscape: Boolean = false,
        clipboardGateway: ClipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
        pendingSharedPaste: org.nanokvm.mobile.clipboard.ClipboardPayload? = null,
        onSharedPasteConsumed: (org.nanokvm.mobile.clipboard.ClipboardPayload) -> Unit = {},
        mjpegFrameDetectionEnabled: Boolean = false,
        onMjpegFrameDetectionEnabledChange: (Boolean) -> Unit = {},
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        useDynamicColor: Boolean = true,
    ) {
        val connectedSession = backend.session.value
        composeRule.setContent {
            NanoKvmTheme(themeMode = themeMode, useDynamicColor = useDynamicColor) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (compactLandscape) {
                        Box(Modifier.requiredSize(width = 360.dp, height = 240.dp)) {
                            ConsoleScreen(
                                profile = profile,
                                session = connectedSession,
                                input = backend,
                                videoSurface = backend,
                                features = backend.features,
                                onDisconnect = { backend.disconnectCalls++ },
                                sessionDraftOwner = sessionDraftOwner,
                                clipboardGateway = clipboardGateway,
                                pendingSharedPaste = pendingSharedPaste,
                                onSharedPasteConsumed = onSharedPasteConsumed,
                                onScrollSensitivityChange = {},
                                mjpegFrameDetectionEnabled = mjpegFrameDetectionEnabled,
                                onMjpegFrameDetectionEnabledChange =
                                    onMjpegFrameDetectionEnabledChange,
                                onPasswordChange = { _, _, password, _ ->
                                    password.fill('\u0000')
                                },
                            )
                        }
                    } else {
                        ConsoleScreen(
                            profile = profile,
                            session = connectedSession,
                            input = backend,
                            videoSurface = backend,
                            features = backend.features,
                            onDisconnect = { backend.disconnectCalls++ },
                            sessionDraftOwner = sessionDraftOwner,
                            clipboardGateway = clipboardGateway,
                            pendingSharedPaste = pendingSharedPaste,
                            onSharedPasteConsumed = onSharedPasteConsumed,
                            onScrollSensitivityChange = {},
                            mjpegFrameDetectionEnabled = mjpegFrameDetectionEnabled,
                            onMjpegFrameDetectionEnabledChange =
                                onMjpegFrameDetectionEnabledChange,
                            onPasswordChange = { _, _, password, _ ->
                                password.fill('\u0000')
                            },
                        )
                    }
                }
            }
        }
    }

    private fun captureWindow(): Bitmap {
        val decor = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val result = AtomicInteger(-1)
        val completed = CountDownLatch(1)
        composeRule.runOnIdle {
            PixelCopy.request(
                composeRule.activity.window,
                bitmap,
                {
                    result.set(it)
                    completed.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
        }
        assertTrue("PixelCopy timed out", completed.await(5, TimeUnit.SECONDS))
        assertEquals("PixelCopy should capture the TextureView", PixelCopy.SUCCESS, result.get())
        return bitmap
    }

    private fun captureTextureTransform(): FloatArray {
        val values = FloatArray(9)
        composeRule.runOnIdle {
            val textureView = findTextureView(composeRule.activity.window.decorView)
            assertNotNull("The remote viewport must contain a TextureView", textureView)
            val matrix = Matrix()
            textureView!!.getTransform(matrix)
            matrix.getValues(values)
        }
        return values
    }
}

private fun findTextureView(view: View): TextureView? {
    if (view is TextureView) return view
    if (view !is ViewGroup) return null
    for (index in 0 until view.childCount) {
        findTextureView(view.getChildAt(index))?.let { return it }
    }
    return null
}

private fun assertColorNear(expected: Int, actual: Int) {
    val tolerance = 18
    assertTrue(
        "Expected video colour ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
        kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
    )
}

private fun colorContrastRatio(first: Int, second: Int): Double {
    fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val encoded = value / 255.0
            return if (encoded <= 0.04045) {
                encoded / 12.92
            } else {
                Math.pow((encoded + 0.055) / 1.055, 2.4)
            }
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    return (maxOf(firstLuminance, secondLuminance) + 0.05) /
        (minOf(firstLuminance, secondLuminance) + 0.05)
}

private val TEST_VIDEO_COLOR = Color.rgb(31, 190, 142)

private class RecordingConsoleBackend : ConsoleBackend {
    val mutablePicoClawState = MutableStateFlow(
        PicoClawUiState(support = PicoClawSupport.Supported),
    )
    override val session: StateFlow<BackendSession> = MutableStateFlow(
        BackendSession(
            connection = ConnectionState.Connected,
            remoteWidth = 1920,
            remoteHeight = 1080,
            streamLabel = VideoStreamDescriptor.DirectH264,
            videoSettings = VideoSettings(
                transportPreference = VideoTransportPreference.MJPEG,
                resolutionHeight = 600,
                framesPerSecond = 24,
                bitrateKbps = 2_000,
                jpegQuality = 60,
            ),
            framesPerSecond = 30,
            roundTripMs = 8,
            droppedFrames = 7L,
            videoStallEvents = 2L,
            sessionGeneration = 7L,
            deviceStatus = NanoKvmDeviceStatus(
                applicationVersion = "2.4.3",
                imageVersion = "2026.07",
                hardwareVersion = "NanoKVM-Full",
                deviceKey = "test-device-key",
                mdnsName = "nanokvm.local",
                networkAddresses = listOf("192.0.2.250"),
                networkInterfaces = listOf(
                    NanoKvmNetworkInterfaceStatus(
                        name = "eth0",
                        address = "192.0.2.250",
                        version = "v4",
                        type = "wired",
                    ),
                ),
            ),
            phase3 = Phase3FeatureUiState(
                available = true,
                hidMode = Phase3HidModeUiState(Phase3HidModeSelection.Normal),
                virtualMedia = Phase3VirtualMediaUiState(
                    loaded = true,
                    images = listOf(
                        Phase3MediaImageUiState(
                            id = 11L,
                            displayName = "installer.iso",
                            mounted = true,
                        ),
                    ),
                    mountedDisplayName = "installer.iso",
                    cdRomEnabled = true,
                    networkEnabled = true,
                    mediaEnabled = true,
                    diskEnabled = true,
                    remoteTransferEnabled = true,
                ),
                wakeOnLanLoaded = true,
                wakeOnLanTargets = listOf(
                    Phase3WakeOnLanTargetUiState(
                        id = 12L,
                        macAddress = "00:11:22:33:44:55",
                        name = "Test server",
                    ),
                ),
                virtualMediaNotice = Phase3Notice.Guidance(
                    Phase3Notice.GuidanceReason.RefreshMediaBeforeSelectingImage,
                ),
                wakeOnLanNotice = Phase3Notice.Guidance(
                    Phase3Notice.GuidanceReason.EnterValidMacAddress,
                ),
            ),
        ).withActionFeedback(ConsoleMessage.VideoSettingsApplied),
    )

    val committedText = mutableListOf<Pair<String, KeyboardLayout>>()
    val pastedText = mutableListOf<String>()
    val pasteRequests = mutableListOf<ApprovedPasteRequest>()
    var releaseAllCalls = 0
    var disconnectCalls = 0
    var lastVideoSettings: VideoSettings? = null
    var surfaceFillColor: Int? = null
    var absoluteMoveCalls = 0
    var relativeMoveCalls = 0
    var mouseButtonCalls = 0
    val mouseButtonEvents = mutableListOf<Pair<MouseButton, Boolean>>()
    val scrollSteps = mutableListOf<Int>()
    val horizontalScrollSteps = mutableListOf<Int>()
    val powerActions = mutableListOf<PowerAction>()
    val powerDestinations = mutableListOf<ApprovedCoreDestination>()
    @Volatile var attachSurfaceCalls = 0
    @Volatile var detachSurfaceCalls = 0
    @Volatile var phase3SurfaceIsVisible = false
    val phase3HidModeChanges = mutableListOf<Phase3HidModeSelection>()
    val phase3NetworkChanges = mutableListOf<Boolean>()
    @Volatile var picoClawSurfaceIsVisible = false
    var picoClawEntryCalls = 0
    private val phase3Controls = object : NoOpPhase3Controls() {
        override fun setPhase3SurfaceVisible(visible: Boolean) {
            phase3SurfaceIsVisible = visible
        }

        override fun setPhase3HidMode(
            destination: org.nanokvm.mobile.runtime.ApprovedPhase3Destination,
            selection: Phase3HidModeSelection,
        ) {
            phase3HidModeChanges += selection
        }

        override fun setPhase3NetworkEnabled(
            destination: org.nanokvm.mobile.runtime.ApprovedPhase3Destination,
            enabled: Boolean,
        ) {
            phase3NetworkChanges += enabled
        }
    }
    private val picoClawControls = object : NoOpPicoClawControls() {
        override val picoClawState: StateFlow<PicoClawUiState>
            get() = mutablePicoClawState

        override fun setPicoClawSurfaceVisible(visible: Boolean) {
            picoClawSurfaceIsVisible = visible
        }

        override fun enterPicoClaw(destination: ApprovedPicoClawDestination) {
            picoClawEntryCalls++
            mutablePicoClawState.value = mutablePicoClawState.value.copy(
                entered = true,
                installed = true,
            )
        }
    }
    override val features = ConsoleFeatureBundle(
        core = this,
        phase3 = phase3Controls,
        picoClaw = picoClawControls,
    )

    override suspend fun preflightTrust(profile: HostProfile): TrustPreflightOutcome =
        TrustPreflightOutcome.Failed(
            failure = ConnectionFailure.Unexpected,
            retryable = false,
        )

    override suspend fun connect(request: ConnectRequest): ConnectOutcome = ConnectOutcome.Connected
    override suspend fun disconnect() {
        disconnectCalls++
    }

    override fun reconnect() = Unit
    override fun cancelReconnect() = Unit
    override fun setForeground(isForeground: Boolean) = Unit
    override fun attachVideoSurface(surface: Surface, width: Int, height: Int) {
        attachSurfaceCalls++
        surfaceFillColor?.let { color ->
            runCatching {
                val canvas = surface.lockCanvas(null)
                canvas.drawColor(color)
                surface.unlockCanvasAndPost(canvas)
            }
        }
    }
    override fun resizeVideoSurface(width: Int, height: Int) = Unit
    override fun detachVideoSurface(surface: Surface) {
        detachSurfaceCalls++
    }
    override fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton>) {
        absoluteMoveCalls++
    }
    override fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) {
        relativeMoveCalls++
    }
    override fun mouseButton(button: MouseButton, pressed: Boolean) {
        mouseButtonCalls++
        mouseButtonEvents += button to pressed
    }
    override fun scrollWheel(steps: Int) {
        scrollSteps += steps
    }
    override fun scrollHorizontal(steps: Int) {
        horizontalScrollSteps += steps
    }

    val remoteInputCallCount: Int
        get() = absoluteMoveCalls + relativeMoveCalls + mouseButtonCalls +
            scrollSteps.size + horizontalScrollSteps.size

    override fun typeCommittedText(text: String, layout: KeyboardLayout) {
        committedText += text to layout
    }

    override fun key(key: RemoteKey, pressed: Boolean) = Unit
    override fun releaseAllInput() {
        releaseAllCalls++
    }

    override fun updateVideo(settings: VideoSettings) {
        lastVideoSettings = settings
    }
    override fun setMjpegFrameDetectionPreference(enabled: Boolean) = Unit
    override fun setMjpegFrameDetectionEnabled(enabled: Boolean) = Unit
    override fun resetHid() = Unit
    override fun power(destination: ApprovedCoreDestination, action: PowerAction) {
        powerDestinations += destination
        powerActions += action
    }
    override fun pasteText(request: ApprovedPasteRequest) {
        pasteRequests += request
        pastedText += request.content
    }
    override fun cancelPaste() = Unit
}

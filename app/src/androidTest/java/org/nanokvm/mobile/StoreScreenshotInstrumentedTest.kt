package org.nanokvm.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.clipboard.ClipboardGateway
import org.nanokvm.mobile.clipboard.ClipboardReadResult
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.data.ThemeMode
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.VideoStreamDescriptor
import org.nanokvm.mobile.ui.ProfileMutationUiState
import org.nanokvm.mobile.ui.screens.ConsoleScreen
import org.nanokvm.mobile.ui.screens.ConsoleSessionDraftOwner
import org.nanokvm.mobile.ui.screens.ProfileEditorScreen
import org.nanokvm.mobile.ui.screens.ProfilesScreen
import org.nanokvm.mobile.ui.theme.DarkConsoleColorScheme
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

/**
 * Produces truthful, frame-free Google Play screenshots from the real Compose screens.
 *
 * The production app keeps FLAG_SECURE enabled. This test-only host deliberately bypasses
 * MainActivity and supplies deterministic, non-sensitive state to the same composables instead.
 */
class StoreScreenshotInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun configureWindow() {
        composeRule.runOnIdle {
            composeRule.activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(
                composeRule.activity.window,
                composeRule.activity.window.decorView,
            ).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    @Test
    fun capture01Connections() {
        val profiles = listOf(
            HostProfile(
                id = "studio-rack",
                name = "Studio Rack",
                host = "kvm.example.com",
                username = "operator",
                trustedCertificateSha256 = "AA".repeat(32),
            ),
            HostProfile(
                id = "workshop",
                name = "Workshop",
                host = "192.0.2.44",
                username = "admin",
            ),
        )

        composeRule.setContent {
            NanoKvmTheme(themeMode = ThemeMode.DARK, useDynamicColor = false) {
                ProfilesScreen(
                    profiles = profiles,
                    profileCatalogResolved = true,
                    savedPasswordProfileIds = setOf("studio-rack"),
                    profileStorageIssue = null,
                    profileStorageBusy = false,
                    passwordEntryProfile = null,
                    canSavePassword = true,
                    themeMode = ThemeMode.DARK,
                    useDynamicColor = false,
                    dynamicColorAvailable = true,
                    onAdd = {},
                    onEdit = {},
                    onPrepareConnection = {},
                    onSubmitPassword = { _, password, _ -> password.fill('\u0000') },
                    onDismissPassword = {},
                    onRemoveSavedCredential = {},
                    onResetProfileStorage = {},
                    onRetryProfileStorage = {},
                    onThemeModeChange = {},
                    onUseDynamicColorChange = {},
                )
            }
        }

        composeRule.onNodeWithText("Studio Rack").assertIsDisplayed()
        composeRule.onNodeWithText("Workshop").assertIsDisplayed()
        captureWindow("screenshot-phone-01-connections.png")
    }

    @Test
    fun capture02ProfileEditor() {
        composeRule.setContent {
            NanoKvmTheme(themeMode = ThemeMode.DARK, useDynamicColor = false) {
                ProfileEditorScreen(
                    initial = HostProfile(
                        id = "studio-rack-editor",
                        name = "Studio Rack",
                        host = "kvm.example.com",
                        port = 443,
                        useHttps = true,
                        username = "operator",
                    ),
                    isNew = true,
                    mutation = ProfileMutationUiState.Idle,
                    hasSavedPassword = false,
                    onSave = {},
                    onDelete = null,
                    onRemoveSavedPassword = {},
                    onForgetCertificate = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.profile_editor_add_title),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Studio Rack").assertIsDisplayed()
        captureWindow("screenshot-phone-02-profile-editor.png")
    }

    @Test
    fun capture03Console() {
        val backend = renderConsole()
        try {
            captureWindow("screenshot-phone-03-console.png")
        } finally {
            backend.surfaceBitmap?.recycle()
        }
    }

    @Test
    fun capture04VideoSettings() {
        val backend = renderConsole()
        try {
            composeRule.onNodeWithContentDescription("Open console controls")
                .assertIsDisplayed()
                .performClick()
            composeRule.onNodeWithContentDescription("Video settings")
                .performScrollTo()
                .performClick()
            composeRule.onNodeWithText("Video settings").assertIsDisplayed()
            composeRule.waitForIdle()
            captureWindow("screenshot-phone-04-video-settings.png")
        } finally {
            backend.surfaceBitmap?.recycle()
        }
    }

    private fun renderConsole(): RecordingConsoleBackend {
        val backend = RecordingConsoleBackend(picoClawEnabled = false)
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open(
            "play-store/remote-workstation-fixture.png",
        ).use(BitmapFactory::decodeStream)
        checkNotNull(fixture) { "Unable to decode the Play screenshot framebuffer fixture" }
        backend.surfaceBitmap = fixture

        @Suppress("UNCHECKED_CAST")
        val session = backend.session as MutableStateFlow<BackendSession>
        session.value = session.value.copy(
            streamLabel = VideoStreamDescriptor.Mjpeg,
            framesPerSecond = 30,
            roundTripMs = 8,
            droppedFrames = 0,
            videoStallEvents = 0,
            lastActionFeedback = null,
        )

        composeRule.setContent {
            NanoKvmTheme(themeMode = ThemeMode.DARK, useDynamicColor = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkConsoleColorScheme.canvas),
                    contentAlignment = Alignment.TopStart,
                ) {
                    ConsoleScreen(
                        profile = HostProfile(
                            id = "studio-rack-console",
                            name = "Studio Rack",
                            host = "kvm.example.com",
                            username = "operator",
                        ),
                        session = session.value,
                        input = backend,
                        videoSurface = backend,
                        features = backend.features,
                        onDisconnect = {},
                        sessionDraftOwner = ConsoleSessionDraftOwner(),
                        clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                        onSharedPasteConsumed = {},
                        onScrollSensitivityChange = {},
                        onMjpegFrameDetectionEnabledChange = {},
                        onPasswordChange = { _, _, password, _ -> password.fill('\u0000') },
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            backend.successfulSurfaceDraws > 0 || backend.lastSurfaceDrawFailure != null
        }
        check(backend.lastSurfaceDrawFailure == null) {
            "Unable to draw the generated framebuffer: ${backend.lastSurfaceDrawFailure}"
        }
        check(backend.successfulSurfaceDraws > 0) {
            "The generated framebuffer was not posted to the video surface"
        }
        composeRule.waitForIdle()
        return backend
    }

    private fun captureWindow(fileName: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val bitmap = checkNotNull(
            instrumentation.uiAutomation.takeScreenshot(),
        ) { "UiAutomation was unable to capture the test display" }
        assertEquals("Capture width must match the Play phone canvas", 1080, bitmap.width)
        assertEquals("Capture height must match the Play phone canvas", 1920, bitmap.height)
        if (fileName == "screenshot-phone-03-console.png") {
            assertRemoteFixtureVisible(bitmap)
        }

        try {
            val outputDirectory = File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                OUTPUT_DIRECTORY,
            )
            assertTrue(
                "Unable to create screenshot output directory",
                outputDirectory.isDirectory || outputDirectory.mkdirs(),
            )
            FileOutputStream(File(outputDirectory, fileName), false).use { output ->
                assertTrue("Unable to encode $fileName", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertRemoteFixtureVisible(bitmap: Bitmap) {
        var navySamples = 0
        for (y in 600 until 1_400 step 12) {
            for (x in 0 until bitmap.width step 12) {
                val pixel = bitmap.getPixel(x, y)
                val red = Color.red(pixel)
                val blue = Color.blue(pixel)
                if (blue >= 24 && blue > red + 3) navySamples++
            }
        }
        assertTrue(
            "The generated remote framebuffer should be visibly composited into the console",
            navySamples >= 500,
        )
    }

    private companion object {
        const val OUTPUT_DIRECTORY = "play-store-screenshots"
    }
}

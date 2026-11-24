package org.nanokvm.mobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.data.ProfileCatalogState
import org.nanokvm.video.CometMedia

/** Exercise the production Activity, TLS inspector, profile codec, both sockets and MediaCodec.
 * The loopback peer advertises a localhost-only certificate just like a stock RM1 on a LAN IP.
 */
class CometConsoleInstrumentedTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun signInRenderPointLoseHdmiAndExpire() {
        val app = ui.activity.application as NanoKvmApplication
        val repository = app.container.profileRepository
        val leaf = HeldCertificate.Builder().commonName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(leaf).build()
        val control = AtomicReference<WebSocket>()
        val expire = AtomicBoolean()
        val frames = CopyOnWriteArrayList<String>()
        val unexpected = CopyOnWriteArrayList<String>()
        val startedStreams = AtomicInteger()
        val holdNextMouseOutput = AtomicBoolean()
        val mouseOutputStarted = CountDownLatch(1)
        val finishMouseOutput = CountDownLatch(1)
        val replacementControl = CountDownLatch(1)
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("comet-green-64x64.h264").use { it.readBytes() }
        val picture = (byteArrayOf(1, 1) + fixture).toByteString()

        fun peer(media: Boolean) = object : WebSocketListener() {
            private var streaming = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (media) webSocket.send("""{"event_type":"media","event":{"video":{"h264":{}}}}""")
                else {
                    control.set(webSocket)
                    if (mouseOutputStarted.count == 0L) replacementControl.countDown()
                    webSocket.send("""{"event_type":"streamer","event":{"streamer":{"hdmi":{"signal":false}}}}""")
                    webSocket.send("""{"event_type":"streamer","event":{"streamer":{"hdmi":{"signal":true},"source":{"resolution":{"width":64,"height":64}}}}}""")
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (media && text == CometMedia.START_H264) {
                    streaming = true
                    startedStreams.incrementAndGet()
                    webSocket.send(picture)
                } else unexpected.add("Unexpected socket text")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                when {
                    bytes.hex() == "00" -> webSocket.send(byteArrayOf(-1).toByteString())
                    media && bytes.hex() == "01" && streaming -> webSocket.send(picture)
                    !media -> frames.add(bytes.hex())
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }

        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/auth/login") {
                        return if (request.method == "POST" && request.body.readUtf8() == "user=admin&passwd=fixture012345")
                            MockResponse().addHeader("Set-Cookie", "auth_token=fixture; Path=/; Secure")
                        else MockResponse().setResponseCode(403)
                    }
                    if (request.getHeader("Cookie") != "auth_token=fixture" || expire.get()) {
                        return MockResponse().setResponseCode(403)
                    }
                    return when (request.path) {
                        "/api/ws" -> MockResponse().withWebSocketUpgrade(peer(false))
                        "/api/media/ws" -> MockResponse().withWebSocketUpgrade(peer(true))
                        "/api/hid/set_params?mouse_output=usb" -> {
                            if (holdNextMouseOutput.compareAndSet(true, false)) {
                                mouseOutputStarted.countDown()
                                if (!finishMouseOutput.await(12, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                            }
                            MockResponse()
                        }
                        "/api/streamer/set_params?gl_webrtc=false",
                        "/api/auth/check" -> MockResponse()
                        else -> { unexpected.add(request.path.orEmpty()); MockResponse().setResponseCode(404) }
                    }
                }
            }
            server.start()
            val profileName = "RM1 loopback ${server.port}"
            var createdProfileId: String? = null
            try {
                ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("Add a Comet profile").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithContentDescription("Add a Comet profile").performClick()
                val editor = ui.onNodeWithTag("profile-editor-list")
                for ((label, value) in listOf("Name" to profileName, "Host or IP address" to "127.0.0.1", "Port" to server.port.toString())) {
                    ui.onNode(hasSetTextAction() and hasText(label)).performTextReplacement(value)
                }
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                val totpLabel = "Ask for a verification code at sign-in"
                editor.performScrollToNode(hasText(totpLabel))
                ui.onNodeWithText(totpLabel).performClick()
                editor.performScrollToNode(hasText("Save connection"))
                ui.onNodeWithText("Save connection").performClick()
                ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("Edit $profileName").fetchSemanticsNodes().isNotEmpty() }
                val catalog = runBlocking { repository.profiles.first() } as ProfileCatalogState.Ready
                val profile = catalog.profiles.single { it.name == profileName }
                createdProfileId = profile.id
                assertTrue(profile.requiresTotp)
                ui.onNodeWithTag("connect-profile-${profile.id}").performClick()
                waitText("Trust and remember")
                ui.onNodeWithText("Trust and remember").performScrollTo().performClick()
                waitText("Password")
                ui.onNode(hasSetTextAction()).performTextInput("fixture")
                ui.onAllNodesWithText("Connect").onLast().performClick()
                waitText("Six-digit verification code")
                ui.onNode(hasSetTextAction()).performTextInput("012345")
                ui.onAllNodesWithText("Connect").onLast().performClick()

                waitForGreenPicture()
                ui.onNodeWithTag("console-degraded-status").assertDoesNotExist()
                ui.onNodeWithTag("remote-input-layer").performTouchInput { click(center) }
                ui.waitUntil(5000) { "02006c656674" in frames }
                assertTrue(frames.indexOfFirst { it.startsWith("03") } in 0 until frames.indexOf("02016c656674"))
                assertTrue(frames.indexOf("02016c656674") < frames.indexOf("02006c656674"))
                ui.onNodeWithContentDescription("Show keyboard").performClick()
                commitWithKeyboard("z")
                ui.waitUntil(5000) { "01004b65795a" in frames }
                ui.onNodeWithText("Ctrl Alt Delete").performScrollTo().performClick()
                ui.waitUntil(5000) { "010044656c657465" in frames }
                val beforeRotation = ui.activity
                val wasPortrait = beforeRotation.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                ui.runOnIdle {
                    beforeRotation.requestedOrientation = if (wasPortrait) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                ui.waitUntil(10_000) { ui.activity !== beforeRotation }
                waitForGreenPicture()
                commitWithKeyboard("y")
                ui.waitUntil(5000) { "01004b657959" in frames }
                ui.onNodeWithContentDescription("Hide native keyboard").performScrollTo().performClick()
                frames.clear()

                val originalControl = control.get()
                originalControl.close(1001, "fixture interruption")
                waitText("Retry")
                holdNextMouseOutput.set(true)
                ui.onNodeWithTag("retry-connection-action").performClick()
                ui.waitUntil(15_000) { control.get() !== originalControl && startedStreams.get() == 2 }
                assertTrue("Retry must select its mouse output", mouseOutputStarted.await(5, TimeUnit.SECONDS))
                val oldControl = control.get()
                ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                assertFalse("A new control socket opened before the old mouse-output POST finished",
                    replacementControl.await(700, TimeUnit.MILLISECONDS))
                finishMouseOutput.countDown()
                ui.waitUntil(15_000) { control.get() !== oldControl && startedStreams.get() == 3 }
                ui.runOnIdle {
                    val activity = ui.activity
                    val launcherIntent = activity.intent
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity,
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, "draft"))
                    // ActivityScenario identifies lifecycle events by the original launcher intent.
                    // The app intentionally scrubs the delivered share and clears its intent.
                    activity.intent = launcherIntent
                }
                ui.waitUntil(10_000) { ui.onAllNodesWithTag("clipboard-editor").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("clipboard-editor").performTextReplacement("K\nx")
                assertFalse(frames.any { it.startsWith("01") })
                ui.onNodeWithText("Send").performClick()
                ui.waitUntil(5000) { "01004b657958" in frames }
                assertTrue("010153686966744c656674" in frames && "0101456e746572" in frames)
                control.get().send("""{"event_type":"streamer","event":{"streamer":{"hdmi":{"signal":false}}}}""")
                waitText("No HDMI signal", substring = true)
                control.get().send("""{"event_type":"streamer","event":{"streamer":{"hdmi":{"signal":true}}}}""")
                ui.waitUntil(5000) { ui.onAllNodesWithText("No HDMI signal", substring = true).fetchSemanticsNodes().isEmpty() }
                expire.set(true)
                control.get().close(1000, null)
                waitText("login expired", substring = true)
                assertTrue(unexpected.toString(), unexpected.isEmpty())
            } finally {
                finishMouseOutput.countDown()
                try {
                    ui.activityRule.scenario.close()
                } finally {
                    createdProfileId?.let { id -> runBlocking { repository.delete(id) } }
                }
            }
        }
    }

    private fun commitWithKeyboard(text: String) {
        ui.waitUntil(5000) { ui.activity.currentFocus?.onCheckIsTextEditor() == true }
        ui.runOnIdle {
            val editor = requireNotNull(ui.activity.currentFocus)
            val connection = requireNotNull(editor.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue(connection.commitText(text, 1))
        }
    }

    private fun waitForGreenPicture() {
        ui.waitUntil(30_000) {
            ui.runOnIdle {
                val bitmap = findTexture(ui.activity.window.decorView)?.getBitmap(1, 1)
                val color = bitmap?.getPixel(0, 0) ?: 0
                bitmap?.recycle()
                Color.green(color) > 150 && Color.red(color) < 80 && Color.blue(color) < 80
            }
        }
    }

    private fun waitText(value: String, substring: Boolean = false) {
        ui.waitUntil(15_000) { ui.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun findTexture(view: View): TextureView? = when (view) {
        is TextureView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findTexture(view.getChildAt(it)) }
        else -> null
    }
}

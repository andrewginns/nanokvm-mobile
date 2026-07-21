package org.nanokvm.mobile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.ui.screens.AboutDialog
import org.nanokvm.mobile.ui.screens.AboutReleaseInfo
import org.nanokvm.mobile.ui.screens.BundledAboutDocument
import org.nanokvm.mobile.ui.theme.NanoKvmTheme
import java.security.MessageDigest

class AboutDialogInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun releaseIdentitySourceAndOfflineDocumentsAreReachable() {
        val uriHandler = RecordingUriHandler()
        val sourceUrl = "https://github.com/andrewginns/nanokvm-mobile/tree/v0.3.6"
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                NanoKvmTheme {
                    AboutDialog(
                        releaseInfo = AboutReleaseInfo(
                            versionName = "0.3.6",
                            versionCode = 13,
                            exactSourceUrl = sourceUrl,
                            signingCertificateSha256 = listOf("AA".repeat(32)),
                            isDevelopmentBuild = false,
                        ),
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Version 0.3.6 · code 13").assertIsDisplayed()
        composeRule.onNodeWithTag("about-source-url").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("about-open-source").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(sourceUrl, uriHandler.openedUri) }
        composeRule.onNodeWithText("Installed signing identity")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:" +
                "AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA",
            substring = true,
        ).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("about-content")
            .performScrollToNode(hasTestTag("about-document-privacy"))
        composeRule.onNodeWithTag("about-document-privacy").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("about-document-content")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("# Privacy notice", substring = true).assertIsDisplayed()
    }

    @Test
    fun exactPinnedWebRtcLicenceMaterialsArePackaged() {
        BundledAboutDocument.entries.forEach { document ->
            val length = composeRule.activity.assets
                .open(document.assetPath)
                .use { it.available() }
            assertTrue("Bundled About document is empty: " + document.assetPath, length > 0)
        }

        val completeNotices = composeRule.activity.assets
            .open("open_source_licenses/WEBRTC.md")
            .use { it.readBytes() }
        val wrapperLicense = composeRule.activity.assets
            .open("open_source_licenses/WEBRTC_SDK_ANDROID_LICENSE.txt")
            .use { it.readBytes() }

        assertEquals(788_358, completeNotices.size)
        assertEquals(
            "d1f9382c6878ac024155fd6d44a5977329108bb8b0a01cea40e4a2f1d7de252e",
            sha256(completeNotices),
        )
        assertEquals(1_068, wrapperLicense.size)
        assertEquals(
            "e6b282fe6c0fb353928923470457f31b44cbab203effd60c0cde4a5bb96c8aec",
            sha256(wrapperLicense),
        )
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private class RecordingUriHandler : UriHandler {
    var openedUri: String? = null

    override fun openUri(uri: String) {
        openedUri = uri
    }
}

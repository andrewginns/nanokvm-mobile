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
                            sourceUrl = sourceUrl,
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
    fun exactRuntimeLicenceMaterialsArePackaged() {
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
        val apacheLicense = composeRule.activity.assets
            .open("open_source_licenses/APACHE-2.0.txt")
            .use { it.readBytes() }
        val protobufLicense = composeRule.activity.assets
            .open("open_source_licenses/PROTOBUF-4.28.2-LICENSE.txt")
            .use { it.readBytes() }
        val ijgNotice = composeRule.activity.assets
            .open("open_source_licenses/README.ijg")
            .use { it.readBytes() }
        val runtimeComponentLicenses = composeRule.activity.assets
            .open("open_source_licenses/RUNTIME_COMPONENT_LICENSES.md")
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
        assertEquals(11_358, apacheLicense.size)
        assertEquals(
            "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            sha256(apacheLicense),
        )
        assertEquals(1_732, protobufLicense.size)
        assertEquals(
            "6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d",
            sha256(protobufLicense),
        )
        assertEquals(12_799, ijgNotice.size)
        assertEquals(
            "75815e3bf6484201a3c3d17a1bbf10f2e8e3237f84df10a2357ea896db2a81d6",
            sha256(ijgNotice),
        )
        assertEquals(8_818, runtimeComponentLicenses.size)
        assertEquals(
            "536db15041cb08129b58a6ccab1d294dc691c27ef13b0cf0569b23d3a2890f8a",
            sha256(runtimeComponentLicenses),
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

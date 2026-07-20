package org.nanokvm.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.runtime.CertificateDetails
import org.nanokvm.mobile.runtime.CertificatePresentationReason
import org.nanokvm.mobile.ui.screens.CertificateReviewScreen
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class CertificateReviewUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longCertificateDetailsRemainReachableAndFingerprintCanBeCopied() {
        var rememberedTrustCount = 0
        val certificate = CertificateDetails(
            sha256 = LONG_FINGERPRINT,
            subject = "CN=NanoKVM in a deliberately long equipment-rack location name",
            issuer = "CN=Private NanoKVM certificate authority for the local trusted network",
            subjectAlternativeNames = listOf("nanokvm.example.internal", "192.0.2.250"),
            validFrom = "17 July 2026 09:00:00 UTC",
            validUntil = "17 July 2036 09:00:00 UTC",
            reason = CertificatePresentationReason.PrivateCertificateNotTrusted,
            metadataTruncated = true,
        )

        composeRule.setContent {
            NanoKvmTheme {
                CertificateReviewScreen(
                    profile = HostProfile(name = "Rack console", host = "nanokvm.example.internal"),
                    certificate = certificate,
                    onTrustOnce = {},
                    onTrustAndRemember = { rememberedTrustCount += 1 },
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.certificate_reason_private_not_trusted),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.certificate_metadata_truncated_warning),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(LONG_FINGERPRINT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy certificate fingerprint").performClick()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Fingerprint copied").assertIsDisplayed()
        composeRule.onNodeWithText("Trust and remember").performScrollTo().performClick()

        assertEquals(1, rememberedTrustCount)
    }

    @Test
    fun certificateChangeShowsBothFingerprintsAndExplicitReplacementActions() {
        val previousFingerprint = LONG_FINGERPRINT.replace("AA:BB", "10:20")
        val certificate = CertificateDetails(
            sha256 = LONG_FINGERPRINT,
            subject = "CN=NanoKVM",
            issuer = "CN=NanoKVM",
            subjectAlternativeNames = listOf("nanokvm.example.internal"),
            validFrom = "17 July 2026 09:00:00 UTC",
            validUntil = "17 July 2036 09:00:00 UTC",
            reason = CertificatePresentationReason.DiffersFromSavedCertificate,
            metadataTruncated = false,
        )

        composeRule.setContent {
            NanoKvmTheme {
                CertificateReviewScreen(
                    profile = HostProfile(
                        name = "Rack console",
                        host = "nanokvm.example.internal",
                        trustedCertificateSha256 = previousFingerprint,
                    ),
                    certificate = certificate,
                    onTrustOnce = {},
                    onTrustAndRemember = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("Certificate changed").assertIsDisplayed()
        composeRule.onNodeWithText("Previously saved SHA-256 fingerprint").assertIsDisplayed()
        composeRule.onNodeWithText(previousFingerprint).assertIsDisplayed()
        composeRule.onNodeWithText("New SHA-256 fingerprint").assertIsDisplayed()
        composeRule.onNodeWithText(LONG_FINGERPRINT).assertIsDisplayed()
        composeRule.onNodeWithText("Replace saved certificate").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Connect once with new certificate").performScrollTo().assertIsDisplayed()
    }

    private companion object {
        const val LONG_FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:" +
                "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
    }
}

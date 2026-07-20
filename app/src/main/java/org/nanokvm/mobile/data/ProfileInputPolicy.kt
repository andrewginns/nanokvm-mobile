package org.nanokvm.mobile.data

import org.nanokvm.protocol.CertificateFingerprint
import org.nanokvm.protocol.NanoKvmEndpoint

/**
 * One bounded policy for profile drafts and persisted records.
 *
 * The editor validates the HTTPS profile it intends to save. Persistence validates the same
 * object again, while legacy HTTP records are checked as the HTTPS profile the editor will offer
 * to save. This keeps input rules at one boundary without introducing a validation framework.
 */
internal object ProfileInputPolicy {
    const val MAX_NAME_CHARS: Int = 128
    const val MAX_HOST_CHARS: Int = 253
    const val MAX_USERNAME_CHARS: Int = 256

    private const val MAX_ID_CHARS: Int = 128

    fun isValid(profile: HostProfile): Boolean = issue(profile) == null

    fun requireValid(profile: HostProfile): HostProfile {
        val issue = issue(profile)
        require(issue == null) { issue?.message ?: "Invalid profile" }
        return profile
    }

    /** Converts a legacy HTTP record into the HTTPS destination the editor validates. */
    fun prospectiveHttps(profile: HostProfile): HostProfile = profile.copy(
        port = if (!profile.useHttps && profile.port == 80) 443 else profile.port,
        useHttps = true,
    )

    fun boundName(value: String): String = value.take(MAX_NAME_CHARS)

    fun boundHost(value: String): String = value.take(MAX_HOST_CHARS)

    fun boundUsername(value: String): String = value.take(MAX_USERNAME_CHARS)

    private fun issue(profile: HostProfile): ProfileInputIssue? {
        if (!validText(profile.id, MAX_ID_CHARS)) return ProfileInputIssue.InvalidId
        if (!validText(profile.name, MAX_NAME_CHARS)) return ProfileInputIssue.InvalidName
        if (!validText(profile.host, MAX_HOST_CHARS)) return ProfileInputIssue.InvalidHost
        if (!validText(profile.username, MAX_USERNAME_CHARS)) {
            return ProfileInputIssue.InvalidUsername
        }
        if (profile.port !in 1..65_535) return ProfileInputIssue.InvalidPort
        if (!profile.useHttps) return ProfileInputIssue.HttpsRequired
        val savedFingerprint = profile.trustedCertificateSha256
        if (
            savedFingerprint != null &&
            (
                savedFingerprint.length > MAX_CERTIFICATE_FINGERPRINT_CHARS ||
                    runCatching { CertificateFingerprint.parse(savedFingerprint) }.isFailure
            )
        ) {
            return ProfileInputIssue.InvalidCertificateFingerprint
        }

        val endpoint = try {
            NanoKvmEndpoint.parse(profile.baseUrl)
        } catch (_: IllegalArgumentException) {
            null
        }
        val comparableHost = profile.host
            .removePrefix("[")
            .removeSuffix("]")
        if (
            endpoint == null ||
            !endpoint.isSecure ||
            endpoint.baseUrl.port != profile.port ||
            !endpoint.baseUrl.host.equals(comparableHost, ignoreCase = true)
        ) {
            return ProfileInputIssue.InvalidEndpoint
        }
        return null
    }

    private fun validText(value: String, maxChars: Int): Boolean =
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= maxChars &&
            value.none { character -> character.isISOControl() }

    private const val MAX_CERTIFICATE_FINGERPRINT_CHARS: Int = 160
}

private enum class ProfileInputIssue(val message: String) {
    InvalidId("Profile ID is invalid"),
    InvalidName("Profile name is invalid"),
    InvalidHost("Profile host is invalid"),
    InvalidUsername("Profile username is invalid"),
    InvalidPort("Profile port is invalid"),
    HttpsRequired("Profile must use HTTPS"),
    InvalidCertificateFingerprint("Profile certificate fingerprint is invalid"),
    InvalidEndpoint("Profile does not describe a valid HTTPS NanoKVM origin"),
}

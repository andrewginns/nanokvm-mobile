package org.nanokvm.mobile.ui

import org.nanokvm.mobile.runtime.ConnectionFailure

/**
 * Closed app-level notices shown outside the console's live status banner.
 *
 * This intentionally has no free-form text variant. New operator-facing copy must declare its
 * semantic case here and add an exhaustive display mapping next to the Compose resource boundary.
 */
sealed interface AppNotice {
    data class Simple(val kind: SimpleNotice) : AppNotice

    data class Password(val kind: PasswordNotice) : AppNotice

    data class Share(val kind: ShareNotice) : AppNotice

    data class Credential(val kind: CredentialNotice) : AppNotice

    data class Core(val failure: ConnectionFailure) : AppNotice
}

enum class SimpleNotice {
    ScrollSensitivitySaveFailed,
    ThemePreferenceSaveFailed,
    DeviceColourPreferenceSaveFailed,
    MjpegFrameDetectionPreferenceSaveFailed,
    ProfileSaveFailed,
    CertificateDecisionUpdateFailed,
    CertificateDecisionSaveFailed,
    ProfileDeleteAfterCredentialRemovalFailed,
    ProfileResetFailed,
    DisconnectCleanupFailed,
}

enum class PasswordNotice {
    ChangeUnavailable,
    DestinationChanged,
    Changed,
    ManualVerificationRequired,
    Rejected,
    SessionExpired,
    InvalidRequest,
    AndroidAuthenticationFailed,
    LocalPreparationFailed,
    Busy,
    DisconnectUnverified,
    ProfileUpdateFailed,
    CredentialRetentionFailed,
    LocalStateUnverified,
}

enum class ShareNotice {
    PlainTextOnly,
    TooLarge,
    Empty,
    ChooseConnection,
}

enum class CredentialNotice {
    RemoveBeforeEndpointChange,
    ProtectedKeyCreationFailed,
    ProfileDeleteCredentialRemovalUnverified,
    ProtectedPasswordRemovalUnverified,
    SavedPasswordUnlockFailed,
    SavedPasswordUnlockAndRemovalFailed,
    PasswordProtectionFailed,
    ConnectedPasswordSaveFailed,
    DeviceProtectionUnavailable,
    AuthenticationStartFailed,
    AuthenticationFailedUsePassword,
}

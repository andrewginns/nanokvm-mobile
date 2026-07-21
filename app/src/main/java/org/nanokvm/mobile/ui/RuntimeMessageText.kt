package org.nanokvm.mobile.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.CertificatePresentationReason
import org.nanokvm.mobile.runtime.ConnectionFailure
import org.nanokvm.mobile.runtime.ConsoleMessage
import org.nanokvm.mobile.runtime.VideoStreamDescriptor
import org.nanokvm.mobile.runtime.VideoTransportDescriptor

@Composable
internal fun ConnectionFailure.displayText(): String = when (this) {
    ConnectionFailure.SessionClosed -> stringResource(R.string.console_failure_session_closed)
    ConnectionFailure.AppInBackground ->
        stringResource(R.string.console_failure_app_in_background)
    ConnectionFailure.InvalidAddress -> stringResource(R.string.console_failure_invalid_address)
    ConnectionFailure.HttpsRequired -> stringResource(R.string.console_failure_https_required)
    ConnectionFailure.InvalidSavedCertificate ->
        stringResource(R.string.console_failure_invalid_saved_certificate)
    ConnectionFailure.CertificateChanged ->
        stringResource(R.string.console_failure_certificate_changed)
    is ConnectionFailure.CertificateHostnameMismatch -> stringResource(
        R.string.console_failure_certificate_hostname_mismatch,
        expectedHost,
    )
    ConnectionFailure.CertificateDateInvalid ->
        stringResource(R.string.console_failure_certificate_date_invalid)
    ConnectionFailure.CertificateInspectionFailed ->
        stringResource(R.string.console_failure_certificate_inspection_failed)
    is ConnectionFailure.RequestRejected -> responseCode?.let { code ->
        stringResource(R.string.console_failure_request_rejected_code, code)
    } ?: stringResource(R.string.console_failure_request_rejected)
    ConnectionFailure.UnsupportedApplicationVersion ->
        stringResource(R.string.console_failure_unsupported_application_version)
    ConnectionFailure.ProtocolError -> stringResource(R.string.console_failure_protocol_error)
    ConnectionFailure.TimedOut -> stringResource(R.string.console_failure_timed_out)
    ConnectionFailure.Unreachable -> stringResource(R.string.console_failure_unreachable)
    ConnectionFailure.Unexpected -> stringResource(R.string.console_failure_unexpected)
}

@Composable
internal fun CertificatePresentationReason.displayText(): String = stringResource(
    certificatePresentationReasonResource(this),
)

@StringRes
internal fun certificatePresentationReasonResource(reason: CertificatePresentationReason): Int =
    when (reason) {
        CertificatePresentationReason.NotTrustedByAndroid ->
            R.string.certificate_reason_not_trusted
        CertificatePresentationReason.TrustedByAndroid ->
            R.string.certificate_reason_trusted_by_android
        CertificatePresentationReason.MatchesSavedCertificate ->
            R.string.certificate_reason_matches_saved
        CertificatePresentationReason.PrivateCertificateNotTrusted ->
            R.string.certificate_reason_private_not_trusted
        CertificatePresentationReason.DiffersFromSavedCertificate ->
            R.string.certificate_reason_differs_from_saved
    }

@Composable
internal fun VideoStreamDescriptor.displayText(): String = stringResource(
    videoStreamDescriptorResource(this),
)

@StringRes
internal fun videoStreamDescriptorResource(descriptor: VideoStreamDescriptor): Int =
    if (descriptor.isFallback) {
        when (descriptor.transport) {
            VideoTransportDescriptor.WebRtc -> R.string.console_stream_webrtc_fallback
            VideoTransportDescriptor.DirectH264 -> R.string.console_stream_h264_direct_fallback
            VideoTransportDescriptor.Mjpeg -> R.string.console_stream_mjpeg_fallback
        }
    } else {
        videoTransportDescriptorResource(descriptor.transport)
    }

@Composable
private fun VideoTransportDescriptor.displayText(): String = stringResource(
    videoTransportDescriptorResource(this),
)

@StringRes
internal fun videoTransportDescriptorResource(descriptor: VideoTransportDescriptor): Int =
    when (descriptor) {
        VideoTransportDescriptor.WebRtc -> R.string.console_stream_webrtc
        VideoTransportDescriptor.DirectH264 -> R.string.console_stream_h264_direct
        VideoTransportDescriptor.Mjpeg -> R.string.console_stream_mjpeg
    }

@Composable
internal fun ConsoleMessage.displayText(): String = when (this) {
    is ConsoleMessage.VerifyingDestination -> stringResource(
        R.string.console_message_verifying_destination,
        authority,
    )
    is ConsoleMessage.AuthenticatingDestination -> stringResource(
        R.string.console_message_authenticating_destination,
        authority,
    )
    ConsoleMessage.ConnectedToNanoKvm -> stringResource(R.string.console_message_connected)
    ConsoleMessage.PausedInBackground ->
        stringResource(R.string.console_message_paused_in_background)
    is ConsoleMessage.UnsupportedKeyboardCharacters -> pluralStringResource(
        R.plurals.console_message_unsupported_keyboard_characters,
        count,
        count,
    )
    ConsoleMessage.ConnectBeforeChangingVideoSettings ->
        stringResource(R.string.console_message_connect_before_video_settings)
    ConsoleMessage.ApplyingVideoSettings ->
        stringResource(R.string.console_message_applying_video_settings)
    ConsoleMessage.VideoSettingsApplied ->
        stringResource(R.string.console_message_video_settings_applied)
    is ConsoleMessage.VideoSettingsNotApplied -> stringResource(
        R.string.console_message_video_settings_not_applied,
        failure.displayText(),
    )
    is ConsoleMessage.ClipboardTextOutsideByteLimit -> stringResource(
        R.string.console_message_clipboard_byte_limit,
        maximumBytes,
    )
    ConsoleMessage.PreparingToReconnect ->
        stringResource(R.string.console_message_preparing_reconnect)
    is ConsoleMessage.ReconnectAttempt -> stringResource(
        R.string.console_message_reconnect_attempt,
        attempt,
        maximumAttempts,
    )
    is ConsoleMessage.ReconnectAttemptDelayed -> stringResource(
        R.string.console_message_reconnect_attempt_delayed,
        attempt,
        maximumAttempts,
        pluralStringResource(
            R.plurals.console_message_reconnect_delay,
            delaySeconds,
            delaySeconds,
        ),
    )
    is ConsoleMessage.ReconnectStopped -> stringResource(
        R.string.console_message_reconnect_stopped,
        pluralStringResource(
            R.plurals.console_message_reconnect_attempt_count,
            attempts,
            attempts,
        ),
        failure.displayText(),
    )
    ConsoleMessage.ReconnectCancelled ->
        stringResource(R.string.console_message_reconnect_cancelled)
    is ConsoleMessage.ConnectionFailed -> failure.displayText()
    is ConsoleMessage.CommandFailed -> failure.displayText()
    ConsoleMessage.TypingApprovedClipboardText ->
        stringResource(R.string.console_message_typing_clipboard)
    ConsoleMessage.ClipboardSessionChanged ->
        stringResource(R.string.console_message_clipboard_session_changed)
    ConsoleMessage.ClipboardTypingAlreadyActive ->
        stringResource(R.string.console_message_clipboard_already_active)
    ConsoleMessage.ClipboardTextTyped ->
        stringResource(R.string.console_message_clipboard_typed)
    is ConsoleMessage.ClipboardUnsupportedCharacters -> pluralStringResource(
        R.plurals.console_message_clipboard_unsupported,
        count,
        count,
    )
    is ConsoleMessage.ClipboardTypingCancelled -> pluralStringResource(
        R.plurals.console_message_clipboard_cancelled,
        totalKeystrokes,
        sentKeystrokes,
        totalKeystrokes,
    )
    is ConsoleMessage.ClipboardInputConnectionLost -> pluralStringResource(
        R.plurals.console_message_clipboard_connection_lost,
        totalKeystrokes,
        sentKeystrokes,
        totalKeystrokes,
    )
    ConsoleMessage.AuthenticationExpired ->
        stringResource(R.string.console_message_authentication_expired)
    ConsoleMessage.HidInterfaceReset -> stringResource(R.string.console_message_hid_reset)
    ConsoleMessage.CtrlAltDeleteSent ->
        stringResource(R.string.console_message_ctrl_alt_delete_sent)
    ConsoleMessage.HostControlSent ->
        stringResource(R.string.console_message_host_control_sent)
    ConsoleMessage.HostControlSessionChanged ->
        stringResource(R.string.console_message_host_control_session_changed)
    is ConsoleMessage.ConnectingVideo -> stringResource(
        R.string.console_message_connecting_video,
        transport.displayText(),
    )
    is ConsoleMessage.VideoFallback -> stringResource(
        R.string.console_message_video_fallback,
        from.displayText(),
        to.displayText(),
    )
    ConsoleMessage.MjpegFrameDetectionUnavailable ->
        stringResource(R.string.console_message_mjpeg_detection_unavailable)
    ConsoleMessage.MjpegFrameDetectionNotAcknowledged ->
        stringResource(R.string.console_message_mjpeg_detection_not_acknowledged)
}

@Composable
internal fun AppNotice.displayText(): String = when (this) {
    is AppNotice.Simple -> stringResource(simpleNoticeResource(kind))
    is AppNotice.Password -> stringResource(passwordNoticeResource(kind))
    is AppNotice.Share -> stringResource(shareNoticeResource(kind))
    is AppNotice.Credential -> stringResource(credentialNoticeResource(kind))
    is AppNotice.Core -> failure.displayText()
}

@StringRes
internal fun profileStorageIssueMessageResource(issue: ProfileStorageIssue): Int = when (issue) {
    ProfileStorageIssue.Corrupted -> R.string.notice_profile_storage_damaged
    ProfileStorageIssue.Unavailable -> R.string.notice_profile_storage_unreadable
}

@StringRes
internal fun simpleNoticeResource(notice: SimpleNotice): Int = when (notice) {
    SimpleNotice.ScrollSensitivitySaveFailed -> R.string.notice_scroll_sensitivity_save_failed
    SimpleNotice.ThemePreferenceSaveFailed -> R.string.notice_theme_preference_save_failed
    SimpleNotice.DeviceColourPreferenceSaveFailed ->
        R.string.notice_device_colour_preference_save_failed
    SimpleNotice.MjpegFrameDetectionPreferenceSaveFailed ->
        R.string.notice_mjpeg_detection_preference_save_failed
    SimpleNotice.ProfileSaveFailed -> R.string.notice_profile_save_failed
    SimpleNotice.CertificateDecisionUpdateFailed ->
        R.string.notice_certificate_decision_update_failed
    SimpleNotice.CertificateDecisionSaveFailed ->
        R.string.notice_certificate_decision_save_failed
    SimpleNotice.ProfileDeleteAfterCredentialRemovalFailed ->
        R.string.notice_profile_delete_after_credential_removal_failed
    SimpleNotice.ProfileResetFailed -> R.string.notice_profile_reset_failed
    SimpleNotice.DisconnectCleanupFailed -> R.string.notice_disconnect_cleanup_failed
}

@StringRes
internal fun passwordNoticeResource(notice: PasswordNotice): Int = when (notice) {
    PasswordNotice.ChangeUnavailable -> R.string.notice_password_change_unavailable
    PasswordNotice.DestinationChanged -> R.string.notice_password_destination_changed
    PasswordNotice.Changed -> R.string.notice_password_changed
    PasswordNotice.ManualVerificationRequired ->
        R.string.notice_password_manual_verification_required
    PasswordNotice.Rejected -> R.string.notice_password_rejected
    PasswordNotice.SessionExpired -> R.string.notice_password_session_expired
    PasswordNotice.InvalidRequest -> R.string.notice_password_invalid_request
    PasswordNotice.AndroidAuthenticationFailed ->
        R.string.notice_password_android_authentication_failed
    PasswordNotice.LocalPreparationFailed ->
        R.string.notice_password_local_preparation_failed
    PasswordNotice.Busy -> R.string.notice_password_busy
    PasswordNotice.DisconnectUnverified ->
        R.string.notice_password_disconnect_unverified
    PasswordNotice.ProfileUpdateFailed -> R.string.notice_password_profile_update_failed
    PasswordNotice.CredentialRetentionFailed ->
        R.string.notice_password_credential_retention_failed
    PasswordNotice.LocalStateUnverified -> R.string.notice_password_local_state_unverified
}

@StringRes
internal fun shareNoticeResource(notice: ShareNotice): Int = when (notice) {
    ShareNotice.PlainTextOnly -> R.string.share_plain_text_only
    ShareNotice.TooLarge -> R.string.share_text_too_long
    ShareNotice.Empty -> R.string.share_text_empty
    ShareNotice.ChooseConnection -> R.string.share_choose_connection
}

@StringRes
internal fun credentialNoticeResource(notice: CredentialNotice): Int = when (notice) {
    CredentialNotice.RemoveBeforeEndpointChange ->
        R.string.notice_credential_remove_before_endpoint_change
    CredentialNotice.ProtectedKeyCreationFailed ->
        R.string.notice_credential_key_creation_failed
    CredentialNotice.ProfileDeleteCredentialRemovalUnverified ->
        R.string.notice_credential_profile_delete_unverified
    CredentialNotice.ProtectedPasswordRemovalUnverified ->
        R.string.notice_credential_removal_unverified
    CredentialNotice.SavedPasswordUnlockFailed -> R.string.notice_credential_unlock_failed
    CredentialNotice.SavedPasswordUnlockAndRemovalFailed ->
        R.string.notice_credential_unlock_and_removal_failed
    CredentialNotice.PasswordProtectionFailed ->
        R.string.notice_credential_protection_failed
    CredentialNotice.ConnectedPasswordSaveFailed ->
        R.string.notice_credential_connected_save_failed
    CredentialNotice.DeviceProtectionUnavailable ->
        R.string.notice_credential_device_protection_unavailable
    CredentialNotice.AuthenticationStartFailed ->
        R.string.notice_credential_authentication_start_failed
    CredentialNotice.AuthenticationFailedUsePassword ->
        R.string.notice_credential_authentication_failed
}

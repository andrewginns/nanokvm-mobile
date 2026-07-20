package org.nanokvm.mobile.runtime

/**
 * A closed, presentation-independent description of a connection failure.
 *
 * Deliberately do not add a String, Throwable, or server-message escape hatch here. Network and
 * protocol details are useful in bounded diagnostics, but rendering arbitrary exception or server
 * text in the operator UI makes localization, redaction, and consistent recovery guidance
 * impossible.
 */
sealed interface ConnectionFailure {
    data object SessionClosed : ConnectionFailure
    data object AppInBackground : ConnectionFailure
    data object InvalidAddress : ConnectionFailure
    data object HttpsRequired : ConnectionFailure
    data object InvalidSavedCertificate : ConnectionFailure
    data object CertificateChanged : ConnectionFailure
    data class CertificateHostnameMismatch(val expectedHost: String) : ConnectionFailure
    data object CertificateDateInvalid : ConnectionFailure
    data object CertificateInspectionFailed : ConnectionFailure
    data class RequestRejected(val responseCode: Int?) : ConnectionFailure
    data object UnsupportedApplicationVersion : ConnectionFailure
    data object ProtocolError : ConnectionFailure
    data object TimedOut : ConnectionFailure
    data object Unreachable : ConnectionFailure
    data object Unexpected : ConnectionFailure
}

/** Why a certificate is being presented in connection or review UI. */
enum class CertificatePresentationReason {
    NotTrustedByAndroid,
    TrustedByAndroid,
    MatchesSavedCertificate,
    PrivateCertificateNotTrusted,
    DiffersFromSavedCertificate,
}

/** A base video transport; fallback is session state rather than another transport identity. */
enum class VideoTransportDescriptor {
    WebRtc,
    DirectH264,
    Mjpeg,
}

/** A short, stable label for the active video stream. */
data class VideoStreamDescriptor(
    val transport: VideoTransportDescriptor,
    val isFallback: Boolean = false,
) {
    companion object {
        val WebRtc = VideoStreamDescriptor(VideoTransportDescriptor.WebRtc)
        val DirectH264 = VideoStreamDescriptor(VideoTransportDescriptor.DirectH264)
        val Mjpeg = VideoStreamDescriptor(VideoTransportDescriptor.Mjpeg)
        val WebRtcFallback = VideoStreamDescriptor(VideoTransportDescriptor.WebRtc, isFallback = true)
        val DirectH264Fallback =
            VideoStreamDescriptor(VideoTransportDescriptor.DirectH264, isFallback = true)
        val MjpegFallback = VideoStreamDescriptor(VideoTransportDescriptor.Mjpeg, isFallback = true)
    }
}

/**
 * Semantic messages emitted by the core console session.
 *
 * Values are limited to operator-entered identifiers and bounded primitives. Failure details stay
 * typed through [ConnectionFailure]; no arbitrary appliance or exception text crosses this seam.
 */
sealed interface ConsoleMessage {
    /** Latest connection/video state. Intermediate values are deliberately conflated. */
    sealed interface Status : ConsoleMessage

    /** User-action feedback retained independently from unrelated connection/video changes. */
    sealed interface ActionFeedback : ConsoleMessage

    data class VerifyingDestination(val authority: String) : Status
    data class AuthenticatingDestination(val authority: String) : Status
    data object ConnectedToNanoKvm : Status
    data object PausedInBackground : Status
    data class UnsupportedKeyboardCharacters(val count: Int) : ActionFeedback
    data object ConnectBeforeChangingVideoSettings : ActionFeedback
    data object ApplyingVideoSettings : ActionFeedback
    data object VideoSettingsApplied : ActionFeedback
    data class VideoSettingsNotApplied(val failure: ConnectionFailure) : ActionFeedback
    data class ClipboardTextOutsideByteLimit(val maximumBytes: Int) : ActionFeedback
    data object PreparingToReconnect : Status
    data class ReconnectAttempt(
        val attempt: Int,
        val maximumAttempts: Int,
    ) : Status

    data class ReconnectAttemptDelayed(
        val attempt: Int,
        val maximumAttempts: Int,
        val delaySeconds: Int,
    ) : Status

    data class ReconnectStopped(
        val attempts: Int,
        val failure: ConnectionFailure,
    ) : Status

    data class ConnectionFailed(val failure: ConnectionFailure) : Status
    data class CommandFailed(val failure: ConnectionFailure) : ActionFeedback
    data object TypingApprovedClipboardText : ActionFeedback
    data object ClipboardSessionChanged : ActionFeedback
    data object ClipboardTypingAlreadyActive : ActionFeedback
    data object ClipboardTextTyped : ActionFeedback
    data class ClipboardUnsupportedCharacters(val count: Int) : ActionFeedback
    data class ClipboardTypingCancelled(
        val sentKeystrokes: Int,
        val totalKeystrokes: Int,
    ) : ActionFeedback

    data class ClipboardInputConnectionLost(
        val sentKeystrokes: Int,
        val totalKeystrokes: Int,
    ) : ActionFeedback

    data object AuthenticationExpired : Status
    data object HidInterfaceReset : ActionFeedback
    data object CtrlAltDeleteSent : ActionFeedback
    data object HostControlSent : ActionFeedback
    data class ConnectingVideo(val transport: VideoTransportDescriptor) : Status
    data class VideoFallback(
        val from: VideoTransportDescriptor,
        val to: VideoTransportDescriptor,
    ) : Status

    data object MjpegFrameDetectionUnavailable : ActionFeedback
    data object MjpegFrameDetectionNotAcknowledged : ActionFeedback
}

data class SequencedConsoleActionFeedback(
    val revision: Long,
    val content: ConsoleMessage.ActionFeedback,
)

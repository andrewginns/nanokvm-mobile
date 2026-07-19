package org.nanokvm.protocol

import kotlinx.coroutines.CancellationException

enum class CertificateTrustSource {
    SYSTEM,
    SAVED_LEAF_PIN,
}

enum class TrustPreflightRejection {
    INSECURE_ENDPOINT,
    PIN_MISMATCH,
    HOSTNAME_MISMATCH,
    CERTIFICATE_DATE_INVALID,
    INSPECTION_FAILED,
}

/** Result of a TLS handshake that deliberately sends no HTTP or other application data. */
sealed interface EndpointTrustPreflightResult {
    data class Trusted(
        val inspection: CertificateInspection,
        val source: CertificateTrustSource,
    ) : EndpointTrustPreflightResult

    /** A valid, hostname-matching certificate that is neither system-trusted nor already pinned. */
    data class ReviewRequired(
        val inspection: CertificateInspection,
    ) : EndpointTrustPreflightResult

    data class Rejected(
        val reason: TrustPreflightRejection,
        val inspection: CertificateInspection? = null,
        val cause: Throwable? = null,
    ) : EndpointTrustPreflightResult
}

/** Performs and evaluates the credential-free trust gate for a NanoKVM endpoint. */
object EndpointTrustPreflight {
    @JvmStatic
    suspend fun inspect(
        endpoint: NanoKvmEndpoint,
        savedLeafPin: CertificateFingerprint? = null,
    ): EndpointTrustPreflightResult {
        if (!endpoint.isSecure) {
            return EndpointTrustPreflightResult.Rejected(TrustPreflightRejection.INSECURE_ENDPOINT)
        }
        val inspection = try {
            CertificateInspector.inspect(endpoint)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return EndpointTrustPreflightResult.Rejected(
                reason = TrustPreflightRejection.INSPECTION_FAILED,
                cause = error,
            )
        }
        return evaluate(inspection, savedLeafPin)
    }

    internal fun evaluate(
        inspection: CertificateInspection,
        savedLeafPin: CertificateFingerprint?,
    ): EndpointTrustPreflightResult {
        if (!inspection.hostnameVerified) {
            return EndpointTrustPreflightResult.Rejected(
                TrustPreflightRejection.HOSTNAME_MISMATCH,
                inspection,
            )
        }
        if (!inspection.currentlyValid) {
            return EndpointTrustPreflightResult.Rejected(
                TrustPreflightRejection.CERTIFICATE_DATE_INVALID,
                inspection,
            )
        }
        if (savedLeafPin != null) {
            return if (savedLeafPin == inspection.fingerprint) {
                EndpointTrustPreflightResult.Trusted(
                    inspection,
                    CertificateTrustSource.SAVED_LEAF_PIN,
                )
            } else {
                EndpointTrustPreflightResult.Rejected(
                    TrustPreflightRejection.PIN_MISMATCH,
                    inspection,
                )
            }
        }
        return if (inspection.systemTrusted) {
            EndpointTrustPreflightResult.Trusted(inspection, CertificateTrustSource.SYSTEM)
        } else {
            EndpointTrustPreflightResult.ReviewRequired(inspection)
        }
    }
}

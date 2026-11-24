package org.nanokvm.mobile.runtime

import java.time.format.DateTimeFormatter
import org.nanokvm.protocol.CertificateInspection

internal fun CertificateInspection.toCertificateDetails(
    reason: CertificatePresentationReason,
): CertificateDetails = CertificateDetails(
    sha256 = fingerprint.colonSeparated(),
    subject = subject,
    issuer = issuer,
    subjectAlternativeNames = subjectAlternativeNames.dnsNames + subjectAlternativeNames.ipAddresses,
    validFrom = DateTimeFormatter.ISO_INSTANT.format(validFrom),
    validUntil = DateTimeFormatter.ISO_INSTANT.format(validUntil),
    reason = reason,
    metadataTruncated = metadataTruncated,
)

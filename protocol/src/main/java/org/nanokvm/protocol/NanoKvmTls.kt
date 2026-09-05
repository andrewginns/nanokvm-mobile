package org.nanokvm.protocol

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** SHA-256 over the full DER-encoded X.509 certificate (not an SPKI pin). */
@JvmInline
value class CertificateFingerprint private constructor(val hex: String) {
    fun colonSeparated(): String = hex.chunked(2).joinToString(":")

    override fun toString(): String = colonSeparated()

    companion object {
        @JvmStatic
        fun parse(value: String): CertificateFingerprint {
            val normalized = value
                .trim()
                .removePrefix("SHA256:")
                .removePrefix("sha256:")
                .replace(":", "")
                .replace(" ", "")
                .uppercase(Locale.ROOT)
            require(normalized.length == 64 && normalized.all { it in "0123456789ABCDEF" }) {
                "A SHA-256 certificate fingerprint must contain 64 hexadecimal digits"
            }
            return CertificateFingerprint(normalized)
        }

        @JvmStatic
        fun sha256OfDer(derEncodedCertificate: ByteArray): CertificateFingerprint {
            val digest = MessageDigest.getInstance("SHA-256").digest(derEncodedCertificate)
            return CertificateFingerprint(digest.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) })
        }

        @JvmStatic
        fun from(certificate: X509Certificate): CertificateFingerprint =
            sha256OfDer(certificate.encoded)
    }
}

/** TLS trust is always explicit and scoped to one [NanoKvmEndpoint]. */
sealed interface TlsMode {
    /** Use Android/JVM's normal CA trust and hostname verification. */
    data object SystemTrusted : TlsMode

    /** Trust exactly one DER certificate fingerprint while retaining hostname verification. */
    data class PinnedCertificate(val fingerprint: CertificateFingerprint) : TlsMode
}

internal fun OkHttpClient.Builder.applyTlsMode(
    endpoint: NanoKvmEndpoint,
    mode: TlsMode,
): OkHttpClient.Builder {
    val fingerprint = when (mode) {
        TlsMode.SystemTrusted -> return this
        is TlsMode.PinnedCertificate -> mode.fingerprint
    }
    require(endpoint.isSecure) { "Certificate trust modes can only be used with HTTPS endpoints" }

    val trustManager = EndpointCertificateTrustManager(endpoint, fingerprint)
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(trustManager), null)
    return sslSocketFactory(context.socketFactory, trustManager)
}

/**
 * Replaces CA-chain trust only for an endpoint-scoped full-DER pin.
 *
 * Certificate validity is checked here and OkHttp's default hostname verifier remains enabled.
 * This custom manager is necessary because platform trust rejects the self-signed certificates
 * that these explicit trust modes are designed to support.
 */
@SuppressLint("CustomX509TrustManager")
private class EndpointCertificateTrustManager(
    private val endpoint: NanoKvmEndpoint,
    private val fingerprint: CertificateFingerprint,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Server sent no certificate")
        leaf.checkValidity()
        val observed = CertificateFingerprint.from(leaf)

        if (observed != fingerprint) {
            throw CertificateException(
                "Certificate fingerprint mismatch for ${endpoint.authorityKey}: observed $observed",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

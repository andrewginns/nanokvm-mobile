package org.nanokvm.protocol

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

enum class TofuDecision {
    TRUSTED_FIRST_USE,
    TRUSTED_EXISTING,
    REJECTED_CHANGED,
}

/**
 * An atomic trust-on-first-use store. Implementations must compare and store in one operation.
 * The key is the endpoint authority (`host:port`).
 */
fun interface TofuPinStore {
    fun verifyOrStore(authority: String, observed: CertificateFingerprint): TofuDecision
}

class InMemoryTofuPinStore : TofuPinStore {
    private val fingerprints = ConcurrentHashMap<String, CertificateFingerprint>()

    override fun verifyOrStore(
        authority: String,
        observed: CertificateFingerprint,
    ): TofuDecision {
        val existing = fingerprints.putIfAbsent(authority, observed)
        return when {
            existing == null -> TofuDecision.TRUSTED_FIRST_USE
            existing == observed -> TofuDecision.TRUSTED_EXISTING
            else -> TofuDecision.REJECTED_CHANGED
        }
    }

    fun fingerprint(authority: String): CertificateFingerprint? = fingerprints[authority]
}

/** TLS trust is always explicit and scoped to one [NanoKvmEndpoint]. */
sealed interface TlsMode {
    /** Use Android/JVM's normal CA trust and hostname verification. */
    data object SystemTrusted : TlsMode

    /** Trust exactly one DER certificate fingerprint while retaining hostname verification. */
    data class PinnedCertificate(val fingerprint: CertificateFingerprint) : TlsMode

    /**
     * Trust the first valid-dated certificate seen for this authority, then reject changes.
     * The default hostname verifier still checks that the certificate identifies the endpoint.
     */
    data class TrustOnFirstUse(
        val store: TofuPinStore,
        val onFirstTrust: ((CertificateFingerprint) -> Unit)? = null,
    ) : TlsMode
}

internal fun OkHttpClient.Builder.applyTlsMode(
    endpoint: NanoKvmEndpoint,
    mode: TlsMode,
): OkHttpClient.Builder {
    if (mode is TlsMode.SystemTrusted) return this
    require(endpoint.isSecure) { "Certificate trust modes can only be used with HTTPS endpoints" }

    val trustManager = EndpointCertificateTrustManager(endpoint, mode)
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(trustManager), null)
    return sslSocketFactory(context.socketFactory, trustManager)
}

private class EndpointCertificateTrustManager(
    private val endpoint: NanoKvmEndpoint,
    private val mode: TlsMode,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Server sent no certificate")
        leaf.checkValidity()
        val observed = CertificateFingerprint.from(leaf)

        when (val configured = mode) {
            TlsMode.SystemTrusted -> error("System trust must use the platform trust manager")
            is TlsMode.PinnedCertificate -> if (observed != configured.fingerprint) {
                throw CertificateException(
                    "Certificate fingerprint mismatch for ${endpoint.authorityKey}: observed $observed",
                )
            }
            is TlsMode.TrustOnFirstUse -> when (
                configured.store.verifyOrStore(endpoint.authorityKey, observed)
            ) {
                TofuDecision.TRUSTED_FIRST_USE -> configured.onFirstTrust?.invoke(observed)
                TofuDecision.TRUSTED_EXISTING -> Unit
                TofuDecision.REJECTED_CHANGED -> throw CertificateException(
                    "Certificate changed for ${endpoint.authorityKey}: observed $observed",
                )
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

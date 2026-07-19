package org.nanokvm.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class CertificateSubjectAlternativeNames(
    val dnsNames: List<String>,
    val ipAddresses: List<String>,
)

/** Display-safe metadata captured during a one-shot TLS inspection handshake. */
data class CertificateInspection(
    val fingerprint: CertificateFingerprint,
    val subject: String,
    val issuer: String,
    val subjectAlternativeNames: CertificateSubjectAlternativeNames,
    val validFrom: Instant,
    val validUntil: Instant,
    val currentlyValid: Boolean,
    /** Result from the platform HTTPS hostname verifier; approval should require this to be true. */
    val hostnameVerified: Boolean,
    /** Whether the platform CA trust manager accepts the presented chain. */
    val systemTrusted: Boolean,
    val publicKeyAlgorithm: String,
    val chainLength: Int,
    val tlsProtocol: String,
    val cipherSuite: String,
)

/**
 * Performs a one-shot, no-application-data TLS handshake so onboarding can review a self-signed
 * certificate before pinning it.
 *
 * The permissive trust manager is private to an ephemeral SSL context and socket, never installed
 * globally or exposed as a reusable client. The platform hostname verifier is run independently
 * and its result is explicit in [CertificateInspection.hostnameVerified].
 */
object CertificateInspector {
    @JvmStatic
    suspend fun inspect(
        endpoint: NanoKvmEndpoint,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 10_000,
    ): CertificateInspection = withContext(Dispatchers.IO) {
        inspectBlocking(endpoint, connectTimeoutMillis, readTimeoutMillis)
    }

    @JvmStatic
    @JvmOverloads
    fun inspectBlocking(
        endpoint: NanoKvmEndpoint,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 10_000,
    ): CertificateInspection {
        require(endpoint.isSecure) { "Certificate inspection requires an HTTPS endpoint" }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0) { "Timeouts must be positive" }

        val trustManager = InspectionOnlyTrustManager()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), null)

        Socket().use { plainSocket ->
            plainSocket.connect(
                InetSocketAddress(endpoint.baseUrl.host, endpoint.baseUrl.port),
                connectTimeoutMillis,
            )
            val layered = context.socketFactory.createSocket(
                plainSocket,
                endpoint.baseUrl.host,
                endpoint.baseUrl.port,
                true,
            ) as SSLSocket
            layered.use { tlsSocket ->
                tlsSocket.soTimeout = readTimeoutMillis
                tlsSocket.startHandshake()
                val session = tlsSocket.session
                val chain = session.peerCertificates.mapNotNull { it as? X509Certificate }
                val leaf = chain.firstOrNull()
                    ?: throw CertificateException("Server sent no X.509 certificate")
                val now = Instant.now()
                val alternatives = leaf.subjectAlternativeNames.orEmpty()
                val dnsNames = alternatives
                    .filter { it.size >= 2 && it[0] == 2 }
                    .mapNotNull { it[1] as? String }
                    .distinct()
                val ipAddresses = alternatives
                    .filter { it.size >= 2 && it[0] == 7 }
                    .mapNotNull { it[1] as? String }
                    .distinct()
                val systemTrusted = runCatching {
                    DEFAULT_TRUST_MANAGER.checkServerTrusted(
                        chain.toTypedArray(),
                        leaf.publicKey.algorithm,
                    )
                }.isSuccess

                return CertificateInspection(
                    fingerprint = CertificateFingerprint.from(leaf),
                    subject = leaf.subjectX500Principal.name,
                    issuer = leaf.issuerX500Principal.name,
                    subjectAlternativeNames = CertificateSubjectAlternativeNames(dnsNames, ipAddresses),
                    validFrom = leaf.notBefore.toInstant(),
                    validUntil = leaf.notAfter.toInstant(),
                    currentlyValid = !now.isBefore(leaf.notBefore.toInstant()) &&
                        !now.isAfter(leaf.notAfter.toInstant()),
                    hostnameVerified = DEFAULT_HOSTNAME_VERIFIER
                        .verify(endpoint.baseUrl.host, session),
                    systemTrusted = systemTrusted,
                    publicKeyAlgorithm = leaf.publicKey.algorithm,
                    chainLength = chain.size,
                    tlsProtocol = session.protocol,
                    cipherSuite = session.cipherSuite,
                )
            }
        }
    }

    private val DEFAULT_HOSTNAME_VERIFIER = OkHttpClient().hostnameVerifier
    private val DEFAULT_TRUST_MANAGER: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(null as KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }
}

private class InspectionOnlyTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Server sent no certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

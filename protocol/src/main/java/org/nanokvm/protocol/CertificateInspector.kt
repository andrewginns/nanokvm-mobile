package org.nanokvm.protocol

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal const val MAX_CERTIFICATE_PRINCIPAL_DISPLAY_UTF8_BYTES: Int = 1_024
internal const val MAX_CERTIFICATE_SAN_DISPLAY_UTF8_BYTES: Int = 512
internal const val MAX_CERTIFICATE_DISPLAY_SAN_COUNT: Int = 64

private const val CERTIFICATE_METADATA_NEUTRAL_SUBSTITUTE: Int = 0xFFFD

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
    /**
     * True when display metadata was shortened, omitted, or visibly neutralized. The conservative
     * default prevents manually constructed inspections from claiming that unbounded metadata is
     * complete; the real inspector always supplies the measured value.
     */
    val metadataTruncated: Boolean = true,
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
    ): CertificateInspection = coroutineScope {
        val sockets = InspectionSockets()
        try {
            // Await from the caller coroutine so cancellation can run the finally block and close
            // the sockets even while the IO worker is blocked in connect or TLS negotiation.
            async(Dispatchers.IO) {
                try {
                    inspectBlocking(endpoint, connectTimeoutMillis, readTimeoutMillis, sockets)
                } catch (error: IOException) {
                    // Closing a blocked socket commonly surfaces as SocketException. Preserve an
                    // unrelated transport failure, but never let that close race replace the
                    // cancellation which initiated it.
                    currentCoroutineContext().ensureActive()
                    throw error
                }
            }.await()
        } finally {
            sockets.close()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun inspectBlocking(
        endpoint: NanoKvmEndpoint,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 10_000,
    ): CertificateInspection {
        val sockets = InspectionSockets()
        return try {
            inspectBlocking(endpoint, connectTimeoutMillis, readTimeoutMillis, sockets)
        } finally {
            sockets.close()
        }
    }

    private fun inspectBlocking(
        endpoint: NanoKvmEndpoint,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        sockets: InspectionSockets,
    ): CertificateInspection {
        require(endpoint.isSecure) { "Certificate inspection requires an HTTPS endpoint" }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0) { "Timeouts must be positive" }

        val trustManager = InspectionOnlyTrustManager()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), null)

        sockets.trackPlain(Socket()).use { plainSocket ->
            plainSocket.connect(
                InetSocketAddress(endpoint.baseUrl.host, endpoint.baseUrl.port),
                connectTimeoutMillis,
            )
            val layered = sockets.trackLayered(
                context.socketFactory.createSocket(
                    plainSocket,
                    endpoint.baseUrl.host,
                    endpoint.baseUrl.port,
                    true,
                ) as SSLSocket,
            )
            layered.use { tlsSocket ->
                tlsSocket.soTimeout = readTimeoutMillis
                tlsSocket.startHandshake()
                val session = tlsSocket.session
                val chain = session.peerCertificates.mapNotNull { it as? X509Certificate }
                val leaf = chain.firstOrNull()
                    ?: throw CertificateException("Server sent no X.509 certificate")
                val now = Instant.now()
                val systemTrusted = runCatching {
                    DEFAULT_TRUST_MANAGER.checkServerTrusted(
                        chain.toTypedArray(),
                        leaf.publicKey.algorithm,
                    )
                }.isSuccess
                val hostnameVerified = DEFAULT_HOSTNAME_VERIFIER
                    .verify(endpoint.baseUrl.host, session)
                val alternatives = leaf.subjectAlternativeNames.orEmpty()
                val metadata = boundCertificateDisplayMetadata(
                    subject = leaf.subjectX500Principal.name,
                    issuer = leaf.issuerX500Principal.name,
                    dnsNames = alternatives.asSequence().mapNotNull { alternative ->
                        alternative.takeIf { it.size >= 2 && it[0] == 2 }
                            ?.get(1) as? String
                    }.asIterable(),
                    ipAddresses = alternatives.asSequence().mapNotNull { alternative ->
                        alternative.takeIf { it.size >= 2 && it[0] == 7 }
                            ?.get(1) as? String
                    }.asIterable(),
                    verifiedHost = endpoint.baseUrl.host.takeIf { hostnameVerified },
                )

                return CertificateInspection(
                    fingerprint = CertificateFingerprint.from(leaf),
                    subject = metadata.subject,
                    issuer = metadata.issuer,
                    subjectAlternativeNames = metadata.subjectAlternativeNames,
                    validFrom = leaf.notBefore.toInstant(),
                    validUntil = leaf.notAfter.toInstant(),
                    currentlyValid = !now.isBefore(leaf.notBefore.toInstant()) &&
                        !now.isAfter(leaf.notAfter.toInstant()),
                    hostnameVerified = hostnameVerified,
                    systemTrusted = systemTrusted,
                    publicKeyAlgorithm = leaf.publicKey.algorithm,
                    chainLength = chain.size,
                    tlsProtocol = session.protocol,
                    cipherSuite = session.cipherSuite,
                    metadataTruncated = metadata.metadataTruncated,
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

internal data class BoundedCertificateDisplayMetadata(
    val subject: String,
    val issuer: String,
    val subjectAlternativeNames: CertificateSubjectAlternativeNames,
    val metadataTruncated: Boolean,
)

/**
 * Produces the only certificate metadata retained for display.
 *
 * The SAN collection is bounded as it is copied. If platform hostname verification succeeded, a
 * matching SAN is reserved a slot even when it occurred after the ordinary display limit. This
 * keeps bounding from hiding the certificate identity on which the trust decision relied.
 */
internal fun boundCertificateDisplayMetadata(
    subject: String,
    issuer: String,
    dnsNames: Iterable<String>,
    ipAddresses: Iterable<String>,
    verifiedHost: String?,
): BoundedCertificateDisplayMetadata {
    val boundedSubject = subject.boundCertificateDisplayText(
        MAX_CERTIFICATE_PRINCIPAL_DISPLAY_UTF8_BYTES,
    )
    val boundedIssuer = issuer.boundCertificateDisplayText(
        MAX_CERTIFICATE_PRINCIPAL_DISPLAY_UTF8_BYTES,
    )
    val sans = BoundedCertificateSans(verifiedHost)
    dnsNames.forEach { sans.add(CertificateSanKind.Dns, it) }
    ipAddresses.forEach { sans.add(CertificateSanKind.IpAddress, it) }
    val boundedSans = sans.finish()
    return BoundedCertificateDisplayMetadata(
        subject = boundedSubject.value,
        issuer = boundedIssuer.value,
        subjectAlternativeNames = CertificateSubjectAlternativeNames(
            dnsNames = boundedSans.values
                .filter { it.kind == CertificateSanKind.Dns }
                .map(BoundedCertificateSan::value),
            ipAddresses = boundedSans.values
                .filter { it.kind == CertificateSanKind.IpAddress }
                .map(BoundedCertificateSan::value),
        ),
        metadataTruncated = boundedSubject.changed || boundedIssuer.changed || boundedSans.changed,
    )
}

private enum class CertificateSanKind { Dns, IpAddress }

private data class RawCertificateSan(
    val kind: CertificateSanKind,
    val value: String,
)

private data class BoundedCertificateSan(
    val kind: CertificateSanKind,
    val value: String,
)

private data class BoundedCertificateSansResult(
    val values: List<BoundedCertificateSan>,
    val changed: Boolean,
)

private class BoundedCertificateSans(
    private val verifiedHost: String?,
) {
    private val retained = ArrayList<RawCertificateSan>(MAX_CERTIFICATE_DISPLAY_SAN_COUNT)
    private var matching: RawCertificateSan? = null
    private var omitted = false

    fun add(kind: CertificateSanKind, value: String) {
        val candidate = RawCertificateSan(kind, value)
        if (retained.any { it == candidate }) return
        if (matching == null && verifiedHost != null && candidate.matchesHost(verifiedHost)) {
            matching = candidate
        }
        if (retained.size < MAX_CERTIFICATE_DISPLAY_SAN_COUNT) {
            retained += candidate
        } else {
            omitted = true
        }
    }

    fun finish(): BoundedCertificateSansResult {
        val matchingSan = matching
        if (matchingSan != null && matchingSan !in retained) {
            retained[retained.lastIndex] = matchingSan
            omitted = true
        }
        var changed = omitted
        val bounded = retained.map { raw ->
            val text = raw.value.boundCertificateDisplayText(
                MAX_CERTIFICATE_SAN_DISPLAY_UTF8_BYTES,
            )
            changed = changed || text.changed
            BoundedCertificateSan(raw.kind, text.value)
        }
        return BoundedCertificateSansResult(bounded, changed)
    }
}

private data class BoundedCertificateDisplayText(
    val value: String,
    val changed: Boolean,
)

/** Bounds after neutralization so the retained value itself always obeys the UTF-8 limit. */
private fun String.boundCertificateDisplayText(maxUtf8Bytes: Int): BoundedCertificateDisplayText {
    val output = StringBuilder(length.coerceAtMost(maxUtf8Bytes))
    var utf8Bytes = 0
    var index = 0
    var changed = false
    while (index < length) {
        val sourceCodePoint = codePointAt(index)
        val sourceWidth = Character.charCount(sourceCodePoint)
        val displayCodePoint = if (sourceCodePoint.isUnsafeCertificateDisplayCodePoint()) {
            changed = true
            CERTIFICATE_METADATA_NEUTRAL_SUBSTITUTE
        } else {
            sourceCodePoint
        }
        val encodedWidth = displayCodePoint.utf8Width()
        if (utf8Bytes + encodedWidth > maxUtf8Bytes) {
            changed = true
            break
        }
        output.appendCodePoint(displayCodePoint)
        utf8Bytes += encodedWidth
        index += sourceWidth
    }
    if (index < length) changed = true
    return BoundedCertificateDisplayText(output.toString(), changed)
}

private fun Int.isUnsafeCertificateDisplayCodePoint(): Boolean {
    val type = Character.getType(this)
    return Character.isISOControl(this) ||
        type == Character.FORMAT.toInt() ||
        type == Character.LINE_SEPARATOR.toInt() ||
        type == Character.PARAGRAPH_SEPARATOR.toInt() ||
        type == Character.SURROGATE.toInt()
}

private fun Int.utf8Width(): Int = when {
    this <= 0x7F -> 1
    this <= 0x7FF -> 2
    this <= 0xFFFF -> 3
    else -> 4
}

private fun RawCertificateSan.matchesHost(host: String): Boolean =
    if (host.isIpLiteralCandidate()) {
        kind == CertificateSanKind.IpAddress && value.matchesIpHost(host)
    } else {
        kind == CertificateSanKind.Dns && value.matchesDnsHost(host)
    }

/** Mirrors the wildcard shape accepted by the platform/OkHttp verifier for canonical DNS hosts. */
private fun String.matchesDnsHost(host: String): Boolean {
    if (isEmpty() || host.isEmpty() || startsWith('.') || endsWith("..") ||
        host.startsWith('.') || host.endsWith("..")
    ) {
        return false
    }
    val canonicalHost = host.lowercase(Locale.US).withTerminalDot()
    val canonicalPattern = lowercase(Locale.US).withTerminalDot()
    if ('*' !in canonicalPattern) return canonicalHost == canonicalPattern
    if (!canonicalPattern.startsWith("*.") || canonicalPattern.indexOf('*', startIndex = 1) != -1) {
        return false
    }
    if (canonicalHost.length < canonicalPattern.length) return false
    if (!canonicalHost.endsWith(canonicalPattern.substring(1))) return false
    val wildcardPrefixEnd = canonicalHost.length - canonicalPattern.length
    return wildcardPrefixEnd <= 0 || canonicalHost.lastIndexOf('.', wildcardPrefixEnd - 1) == -1
}

private fun String.withTerminalDot(): String = if (endsWith('.')) this else "$this."

private fun String.matchesIpHost(host: String): Boolean {
    if (!isIpLiteralCandidate() || !host.isIpLiteralCandidate()) return false
    val candidateAddress = runCatching { InetAddress.getByName(removeIpv6Brackets()).address }
        .getOrNull() ?: return false
    val hostAddress = runCatching { InetAddress.getByName(host.removeIpv6Brackets()).address }
        .getOrNull() ?: return false
    return candidateAddress.contentEquals(hostAddress)
}

private fun String.isIpLiteralCandidate(): Boolean =
    ':' in this || ('.' in this && all { it.isDigit() || it == '.' })

private fun String.removeIpv6Brackets(): String = removePrefix("[").removeSuffix("]")

/**
 * Tracks both socket layers so coroutine cancellation can close whichever layer is currently
 * blocking. Registration is race-safe: a socket created after cancellation is closed immediately.
 */
private class InspectionSockets : Closeable {
    private val lock = Any()
    private var closed = false
    private var plain: Socket? = null
    private var layered: SSLSocket? = null

    fun trackPlain(socket: Socket): Socket = socket.also(::track)

    fun trackLayered(socket: SSLSocket): SSLSocket = socket.also(::track)

    private fun track(socket: Socket) {
        val closeImmediately = synchronized(lock) {
            if (closed) {
                true
            } else {
                if (socket is SSLSocket) layered = socket else plain = socket
                false
            }
        }
        if (closeImmediately) runCatching { socket.close() }
    }

    override fun close() {
        val sockets = synchronized(lock) {
            if (closed) return
            closed = true
            listOfNotNull(plain, layered).also {
                layered = null
                plain = null
            }
        }
        // Force the transport closed first so closing the TLS wrapper cannot wait on its peer.
        sockets.forEach { socket -> runCatching { socket.close() } }
    }
}

/**
 * Accepts a presented chain only for an ephemeral, no-application-data inspection handshake.
 * The resulting metadata reports platform chain trust and hostname verification separately; this
 * manager is never installed on a reusable HTTP client and therefore must not be generalized.
 */
@SuppressLint("CustomX509TrustManager")
private class InspectionOnlyTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Server sent no certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

package org.nanokvm.protocol

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A normalized NanoKVM server origin.
 *
 * NanoKVM serves its API at the root of an origin, so paths, query parameters, fragments and
 * embedded credentials are deliberately rejected. If the scheme is omitted, HTTPS is assumed.
 */
@JvmInline
value class NanoKvmEndpoint private constructor(val baseUrl: HttpUrl) {
    val isSecure: Boolean get() = baseUrl.isHttps

    /** A stable key suitable for host-scoped certificate and credential storage. */
    val authorityKey: String
        get() = "${baseUrl.host}:${baseUrl.port}"

    fun apiUrl(path: String): HttpUrl {
        val relativePath = path.trim().removePrefix("/")
        require(relativePath.isNotEmpty()) { "API path must not be empty" }
        return baseUrl.newBuilder().addPathSegments(relativePath).build()
    }

    /** WebSocket spelling of an API URL. OkHttp canonicalizes this back to HTTP(S) internally. */
    fun webSocketUrl(path: String): String {
        val httpUrl = apiUrl(path).toString()
        return when {
            httpUrl.startsWith("https://") -> "wss://${httpUrl.removePrefix("https://")}"
            else -> "ws://${httpUrl.removePrefix("http://")}"
        }
    }

    override fun toString(): String = baseUrl.toString().removeSuffix("/")

    companion object {
        @JvmStatic
        fun parse(input: String): NanoKvmEndpoint {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty()) { "Server address must not be empty" }

            val candidate = if (SCHEME.matcher(trimmed).find()) trimmed else "https://$trimmed"
            val parsed = candidate.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid NanoKVM server address")

            require(parsed.scheme == "http" || parsed.scheme == "https") {
                "Only HTTP and HTTPS NanoKVM addresses are supported"
            }
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "Credentials must not be embedded in the server address"
            }
            require(parsed.query == null && parsed.fragment == null) {
                "The NanoKVM server address must not contain a query or fragment"
            }
            require(parsed.encodedPath == "/") {
                "NanoKVM must be configured as a server origin, without a path"
            }

            val normalized = parsed.newBuilder()
                .username("")
                .password("")
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
            return NanoKvmEndpoint(normalized)
        }

        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").toPattern()
    }
}

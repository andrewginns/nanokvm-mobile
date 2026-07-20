package org.nanokvm.protocol

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.net.ProtocolException
import java.util.concurrent.TimeUnit

/**
 * Owns the origin-scoped HTTP transport, token store, REST API and input WebSocket factory.
 */
class NanoKvmClient private constructor(
    val endpoint: NanoKvmEndpoint,
    val tokenStore: SessionTokenStore,
    /** Client-owned transport carrying this endpoint's cookie and TLS policy; do not close it. */
    val transport: OkHttpClient,
    private val ownsHttpClient: Boolean,
    val api: NanoKvmApi,
) : Closeable {
    /** Builds an origin-scoped WebSocket handshake with the current NanoKVM cookie attached. */
    fun webSocketRequest(path: String): Request {
        val builder = Request.Builder().url(endpoint.webSocketUrl(path))
        tokenStore.read()?.let { token ->
            validateCookieValue(token)
            builder.header("Cookie", "nano-kvm-token=$token")
        }
        return builder.build()
    }

    fun newInputSocket(heartbeatIntervalMillis: Long = 10_000L): NanoKvmInputSocket =
        NanoKvmInputSocket(
            endpoint = endpoint,
            httpClient = transport,
            tokenStore = tokenStore,
            heartbeatIntervalMillis = heartbeatIntervalMillis,
        )

    /** Creates an explicit, non-reconnecting root/serial terminal connection. */
    fun newTerminalSocket(): NanoKvmTerminalSocket =
        NanoKvmTerminalSocket(
            endpoint = endpoint,
            httpClient = transport,
            tokenStore = tokenStore,
        )

    /** Forget this client session locally. Server logout is intentionally not called. */
    fun forgetSession() {
        api.invalidateSessionScopedHandles()
        tokenStore.write(null)
    }

    override fun close() {
        if (ownsHttpClient) {
            transport.dispatcher.executorService.shutdown()
            transport.connectionPool.evictAll()
            transport.cache?.close()
        }
    }

    companion object {
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        private const val WEBSOCKET_CLOSE_TIMEOUT_SECONDS = 2L

        @JvmStatic
        @JvmOverloads
        fun create(
            endpoint: NanoKvmEndpoint,
            tlsMode: TlsMode = TlsMode.SystemTrusted,
            tokenStore: SessionTokenStore = InMemorySessionTokenStore(),
        ): NanoKvmClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                // Once an application message is rejected, do not leave a hostile peer up to
                // OkHttp's one-minute default to acknowledge the close handshake.
                .webSocketCloseTimeout(WEBSOCKET_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Appliance mutations include toggles and physical controls; never replay them.
                .retryOnConnectionFailure(false)
                // Redirects can cross origins or replay a mutation body. Surface every 3xx.
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(UncompressedWebSocketInterceptor)
                .addNetworkInterceptor(OriginCookieInterceptor(endpoint, tokenStore))
                .applyTlsMode(endpoint, tlsMode)
            val httpClient = builder.build()
            return createInternal(endpoint, tokenStore, httpClient, ownsHttpClient = true)
        }

        /** Allows a caller to share a preconfigured transport; it remains caller-owned. */
        @JvmStatic
        fun using(
            endpoint: NanoKvmEndpoint,
            httpClient: OkHttpClient,
            tokenStore: SessionTokenStore = InMemorySessionTokenStore(),
        ): NanoKvmClient {
            val scopedClient = httpClient.newBuilder()
                // Do not inherit replay behavior from a caller-owned transport.
                .retryOnConnectionFailure(false)
                .webSocketCloseTimeout(WEBSOCKET_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(UncompressedWebSocketInterceptor)
                .addNetworkInterceptor(OriginCookieInterceptor(endpoint, tokenStore))
                .build()
            return createInternal(endpoint, tokenStore, scopedClient, ownsHttpClient = false)
        }

        private fun createInternal(
            endpoint: NanoKvmEndpoint,
            tokenStore: SessionTokenStore,
            httpClient: OkHttpClient,
            ownsHttpClient: Boolean,
        ): NanoKvmClient {
            val api = NanoKvmApi(endpoint, httpClient, tokenStore, DEFAULT_JSON)
            return NanoKvmClient(endpoint, tokenStore, httpClient, ownsHttpClient, api)
        }
    }
}

/**
 * OkHttp advertises per-message deflate for every WebSocket and has no public opt-out. Removing the
 * offer prevents a small compressed wire message from inflating into an attacker-sized buffer
 * before an application callback can enforce its limit. An unsolicited server negotiation is a
 * handshake failure; without negotiation, RSV1 fails at the frame header.
 */
private object UncompressedWebSocketInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        if (!request.header("Upgrade").equals("websocket", ignoreCase = true)) {
            return chain.proceed(request)
        }
        val uncompressedRequest = request.newBuilder()
            .removeHeader("Sec-WebSocket-Extensions")
            .build()
        val response = chain.proceed(uncompressedRequest)
        if (response.header("Sec-WebSocket-Extensions") != null) {
            response.close()
            throw ProtocolException("WebSocket compression negotiation is not permitted")
        }
        return response
    }
}

private class OriginCookieInterceptor(
    private val endpoint: NanoKvmEndpoint,
    private val tokenStore: SessionTokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val sameOrigin = original.url.scheme == endpoint.baseUrl.scheme &&
            original.url.host == endpoint.baseUrl.host &&
            original.url.port == endpoint.baseUrl.port
        val otherCookies = original.header("Cookie")
            ?.split(';')
            ?.map(String::trim)
            ?.filter { it.isNotEmpty() && !it.startsWith("nano-kvm-token=") }
            .orEmpty()
        val token = tokenStore.read()
        val cookies = buildList {
            addAll(otherCookies)
            if (sameOrigin && token != null) {
                validateCookieValue(token)
                add("nano-kvm-token=$token")
            }
        }
        val request = original.newBuilder().apply {
            if (cookies.isEmpty()) removeHeader("Cookie") else header("Cookie", cookies.joinToString("; "))
        }.build()
        return chain.proceed(request)
    }
}

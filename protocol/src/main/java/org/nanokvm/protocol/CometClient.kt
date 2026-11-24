package org.nanokvm.protocol

import java.io.Closeable
import java.io.IOException
import java.net.ProtocolException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class CometHttpFailure(val status: Int) : IOException("Comet returned HTTP $status")

/** One selected HTTPS origin and one memory-only login. No redirect or request replay. */
class CometClient(
    private val endpoint: NanoKvmEndpoint,
    reviewedPin: CertificateFingerprint?,
) : Closeable {
    init { require(endpoint.isSecure) }

    private val transport = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .webSocketCloseTimeout(2, TimeUnit.SECONDS)
        .addInterceptor(UncompressedWebSocketInterceptor)
        .apply {
            if (reviewedPin != null) {
                applyTlsMode(endpoint, TlsMode.PinnedCertificate(reviewedPin))
                // Stock GLKVM certificates can name localhost. Trust the exact leaf approved
                // for this origin, including on the subsequent WebSocket handshakes.
                hostnameVerifier { host, session ->
                    host == endpoint.baseUrl.host &&
                        (session.peerCertificates.firstOrNull() as? X509Certificate)?.let {
                            CertificateFingerprint.from(it) == reviewedPin
                        } == true
                }
            }
        }.build()

    @Volatile private var cookie: String? = null

    fun login(user: String, password: CharArray, totp: CharArray) {
        require(totp.isEmpty() || totp.size == 6 && totp.all { it in '0'..'9' })
        val url = endpoint.apiUrl("api/auth/login")
        val form = FormBody.Builder().add("user", user)
            .add("passwd", password.concatToString() + totp.concatToString()).build()
        transport.newCall(Request.Builder().url(url).post(form).build()).execute().use { response ->
            if (!response.isSuccessful) throw CometHttpFailure(response.code)
            cookie = Cookie.parseAll(url, response.headers)
                .singleOrNull { it.name == "auth_token" }?.value
                ?.takeIf { it.length in 1..512 && it.all { ch -> ch.isLetterOrDigit() && ch.code < 128 } }
                ?: throw ProtocolException("Missing session cookie")
        }
    }

    private fun request(path: String) = Request.Builder().url(endpoint.apiUrl(path))
        .header("Cookie", "auth_token=" + (cookie ?: throw IOException("Session retired")))
        .header("Origin", endpoint.toString())

    fun openSocket(media: Boolean, listener: WebSocketListener): WebSocket =
        transport.newWebSocket(request(if (media) "api/media/ws" else "api/ws").build(), listener)

    fun checkSession() = execute(request("api/auth/check").build())

    fun enableDirectStream() {
        val url = endpoint.apiUrl("api/streamer/set_params").newBuilder()
            .addQueryParameter("gl_webrtc", "false").build()
        execute(request("api/streamer/set_params").url(url).post(FormBody.Builder().build()).build())
    }

    fun selectMouseOutput(relative: Boolean) {
        val url = endpoint.apiUrl("api/hid/set_params").newBuilder()
            .addQueryParameter("mouse_output", if (relative) "usb_rel" else "usb").build()
        execute(request("api/hid/set_params").url(url).post(FormBody.Builder().build()).build())
    }

    private fun execute(request: Request) {
        transport.newCall(request).execute().use {
            if (!it.isSuccessful) throw CometHttpFailure(it.code)
        }
    }

    override fun close() {
        cookie = null
        // Preserve the queued HID releases and close handshake on an established socket.
        transport.dispatcher.runningCalls()
            .filterNot { it.request().url.encodedPath.endsWith("/ws") }
            .forEach { it.cancel() }
        transport.connectionPool.evictAll()
        transport.dispatcher.executorService.shutdown()
    }
}

package org.nanokvm.video

import okhttp3.HttpUrl

internal fun HttpUrl.nanoKvmEndpoint(path: String): HttpUrl =
    resolve(path) ?: error("Unable to resolve NanoKVM endpoint $path against $this")

internal fun nanoKvmCookie(token: String): String {
    require(token.isNotBlank()) { "NanoKVM token must not be blank" }
    require(token.none { it == '\r' || it == '\n' || it == ';' }) { "Invalid NanoKVM token" }
    return "nano-kvm-token=$token"
}

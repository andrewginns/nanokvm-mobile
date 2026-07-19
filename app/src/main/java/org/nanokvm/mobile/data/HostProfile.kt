package org.nanokvm.mobile.data

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "NanoKVM",
    val host: String = "192.0.2.250",
    val port: Int = 443,
    val useHttps: Boolean = true,
    val username: String = "admin",
    val trustedCertificateSha256: String? = null,
) {
    val authority: String
        get() {
            val defaultPort = if (useHttps) 443 else 80
            return if (port == defaultPort) host else "$host:$port"
        }

    val baseUrl: String
        get() = "${if (useHttps) "https" else "http"}://$authority"

    companion object {
        val Default = HostProfile(id = "default-nanokvm")
    }
}

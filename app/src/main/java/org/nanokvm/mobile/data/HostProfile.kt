package org.nanokvm.mobile.data

import org.nanokvm.mobile.DeviceTarget
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = DeviceTarget.profileName,
    val host: String = "192.0.2.250",
    val port: Int = 443,
    val useHttps: Boolean = true,
    val username: String = "admin",
    val requiresTotp: Boolean = false,
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
        val Default = HostProfile(id = DeviceTarget.defaultProfileId)
    }
}

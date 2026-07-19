package org.nanokvm.mobile.runtime

import org.nanokvm.protocol.NanoKvmAccount
import org.nanokvm.protocol.NanoKvmApi
import org.nanokvm.protocol.NanoKvmApplicationVersions
import org.nanokvm.protocol.NanoKvmDnsConfiguration
import org.nanokvm.protocol.NanoKvmHostname
import org.nanokvm.protocol.NanoKvmMdnsState
import org.nanokvm.protocol.NanoKvmOledConfiguration
import org.nanokvm.protocol.NanoKvmOledSleepPreset
import org.nanokvm.protocol.NanoKvmPasswordStatus
import org.nanokvm.protocol.NanoKvmPreviewUpdates
import org.nanokvm.protocol.NanoKvmSshState
import org.nanokvm.protocol.NanoKvmWebTitle
import org.nanokvm.protocol.NanoKvmTailscaleActionApproval
import org.nanokvm.protocol.NanoKvmTailscaleLoginResult
import org.nanokvm.protocol.NanoKvmTailscaleStatus
import org.nanokvm.protocol.NanoKvmWifiCredentials
import org.nanokvm.protocol.NanoKvmWifiStatus

/**
 * Testable boundary for the privileged NanoKVM administration surface.
 *
 * Every mutating method represents exactly one HTTP dispatch. The domain gateway is responsible
 * for destination checks, read-before-write behaviour, and post-dispatch reconciliation.
 */
internal interface NanoKvmAdministrationPort {
    suspend fun currentAccount(): NanoKvmAccount
    suspend fun passwordStatus(): NanoKvmPasswordStatus
    suspend fun changePassword(username: String, password: CharArray)

    suspend fun applicationVersions(): NanoKvmApplicationVersions
    suspend fun previewUpdates(): NanoKvmPreviewUpdates
    suspend fun setPreviewUpdates(enabled: Boolean)
    suspend fun startOnlineUpdate()

    suspend fun rebootSystem()

    suspend fun oledConfiguration(): NanoKvmOledConfiguration
    suspend fun setOledSleep(preset: NanoKvmOledSleepPreset)

    suspend fun sshState(): NanoKvmSshState
    suspend fun setSshEnabled(enabled: Boolean)

    suspend fun hostname(): NanoKvmHostname
    suspend fun setHostname(hostname: String)

    suspend fun mdnsState(): NanoKvmMdnsState
    suspend fun setMdnsEnabled(enabled: Boolean)

    suspend fun webTitle(): NanoKvmWebTitle
    suspend fun setWebTitle(title: String)
    suspend fun resetWebTitle()

    suspend fun dnsConfiguration(): NanoKvmDnsConfiguration
    suspend fun setManualDns(servers: List<String>)
    suspend fun setDhcpDns()

    suspend fun wifiStatus(): NanoKvmWifiStatus
    suspend fun connectWifi(credentials: NanoKvmWifiCredentials)
    suspend fun disconnectWifi()

    suspend fun tailscaleStatus(): NanoKvmTailscaleStatus
    suspend fun installTailscale(approval: NanoKvmTailscaleActionApproval)
    suspend fun uninstallTailscale(approval: NanoKvmTailscaleActionApproval)
    suspend fun startTailscale(approval: NanoKvmTailscaleActionApproval)
    suspend fun stopTailscale(approval: NanoKvmTailscaleActionApproval)
    suspend fun restartTailscale(approval: NanoKvmTailscaleActionApproval)
    suspend fun bringTailscaleUp(approval: NanoKvmTailscaleActionApproval)
    suspend fun bringTailscaleDown(approval: NanoKvmTailscaleActionApproval)
    suspend fun loginTailscale(
        approval: NanoKvmTailscaleActionApproval,
    ): NanoKvmTailscaleLoginResult
    suspend fun logoutTailscale(approval: NanoKvmTailscaleActionApproval)
}

/** Direct adapter for the official NanoKVM administration API. */
internal class NanoKvmProtocolAdministrationPort(
    private val api: NanoKvmApi,
) : NanoKvmAdministrationPort {
    override suspend fun currentAccount(): NanoKvmAccount = api.currentAccount()

    override suspend fun passwordStatus(): NanoKvmPasswordStatus = api.passwordStatus()

    override suspend fun changePassword(username: String, password: CharArray) =
        api.changePassword(username, password)

    override suspend fun applicationVersions(): NanoKvmApplicationVersions =
        api.applicationVersions()

    override suspend fun previewUpdates(): NanoKvmPreviewUpdates = api.previewUpdates()

    override suspend fun setPreviewUpdates(enabled: Boolean) = api.setPreviewUpdates(enabled)

    override suspend fun startOnlineUpdate() = api.startOnlineUpdate()

    override suspend fun rebootSystem() = api.rebootSystem()

    override suspend fun oledConfiguration(): NanoKvmOledConfiguration = api.oledConfiguration()

    override suspend fun setOledSleep(preset: NanoKvmOledSleepPreset) = api.setOledSleep(preset)

    override suspend fun sshState(): NanoKvmSshState = api.sshState()

    override suspend fun setSshEnabled(enabled: Boolean) = api.setSshEnabled(enabled)

    override suspend fun hostname(): NanoKvmHostname = api.hostname()

    override suspend fun setHostname(hostname: String) = api.setHostname(hostname)

    override suspend fun mdnsState(): NanoKvmMdnsState = api.mdnsState()

    override suspend fun setMdnsEnabled(enabled: Boolean) = api.setMdnsEnabled(enabled)

    override suspend fun webTitle(): NanoKvmWebTitle = api.webTitle()

    override suspend fun setWebTitle(title: String) = api.setWebTitle(title)

    override suspend fun resetWebTitle() = api.resetWebTitle()

    override suspend fun dnsConfiguration(): NanoKvmDnsConfiguration = api.dnsConfiguration()

    override suspend fun setManualDns(servers: List<String>) = api.setManualDns(servers)

    override suspend fun setDhcpDns() = api.setDhcpDns()

    override suspend fun wifiStatus(): NanoKvmWifiStatus = api.wifiStatus()

    override suspend fun connectWifi(credentials: NanoKvmWifiCredentials) =
        api.connectWifi(credentials)

    override suspend fun disconnectWifi() = api.disconnectWifi()

    override suspend fun tailscaleStatus(): NanoKvmTailscaleStatus = api.tailscaleStatus()

    override suspend fun installTailscale(approval: NanoKvmTailscaleActionApproval) =
        api.installTailscale(approval)

    override suspend fun uninstallTailscale(approval: NanoKvmTailscaleActionApproval) =
        api.uninstallTailscale(approval)

    override suspend fun startTailscale(approval: NanoKvmTailscaleActionApproval) =
        api.startTailscale(approval)

    override suspend fun stopTailscale(approval: NanoKvmTailscaleActionApproval) =
        api.stopTailscale(approval)

    override suspend fun restartTailscale(approval: NanoKvmTailscaleActionApproval) =
        api.restartTailscale(approval)

    override suspend fun bringTailscaleUp(approval: NanoKvmTailscaleActionApproval) =
        api.bringTailscaleUp(approval)

    override suspend fun bringTailscaleDown(approval: NanoKvmTailscaleActionApproval) =
        api.bringTailscaleDown(approval)

    override suspend fun loginTailscale(
        approval: NanoKvmTailscaleActionApproval,
    ): NanoKvmTailscaleLoginResult = api.loginTailscale(approval)

    override suspend fun logoutTailscale(approval: NanoKvmTailscaleActionApproval) =
        api.logoutTailscale(approval)
}

/**
 * Creates a privileged gateway scoped to the exact authenticated profile, authority, and backend
 * generation. [currentBinding] must return null as soon as command acceptance closes.
 */
internal fun AuthenticatedNanoKvmSession.createAdministrationGateway(
    sessionGeneration: Long,
    currentBinding: () -> NanoKvmSessionBinding?,
): NanoKvmAdministrationGateway {
    val capturedBinding = NanoKvmSessionBinding(profileId, authority, sessionGeneration)
    return NanoKvmAdministrationGateway(
        port = NanoKvmProtocolAdministrationPort(client.api),
        binding = capturedBinding,
        currentBinding = currentBinding,
    )
}

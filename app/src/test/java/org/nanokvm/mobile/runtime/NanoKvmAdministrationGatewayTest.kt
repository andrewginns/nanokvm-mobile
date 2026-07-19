package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.NanoKvmAccount
import org.nanokvm.protocol.NanoKvmApplicationVersions
import org.nanokvm.protocol.NanoKvmDnsConfiguration
import org.nanokvm.protocol.NanoKvmDnsInfo
import org.nanokvm.protocol.NanoKvmDnsMode
import org.nanokvm.protocol.NanoKvmHostname
import org.nanokvm.protocol.NanoKvmIpAddress
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
import org.nanokvm.protocol.NanoKvmTailscaleCommand
import org.nanokvm.protocol.NanoKvmTailscaleState
import org.nanokvm.protocol.NanoKvmWifiCredentials
import org.nanokvm.protocol.NanoKvmWifiSsid
import org.nanokvm.protocol.NanoKvmWifiStatus

class NanoKvmAdministrationGatewayTest {
    @Test
    fun `missing optional DNS endpoint is unsupported but server failure is connection`() = runTest {
        val current = binding()
        val port = FakeAdministrationPort()
        val gateway = gateway(port, current) { current }

        listOf(404, 405, 501).forEach { status ->
            port.dnsReadFailure = HttpResponseException(status)
            val result = gateway.refreshDns() as NanoKvmAdministrationReadResult.Failure
            assertEquals(NanoKvmAdministrationError.Kind.UNSUPPORTED, result.error.kind)
        }
        port.dnsReadFailure = HttpResponseException(500)
        val failure = gateway.refreshDns() as NanoKvmAdministrationReadResult.Failure
        assertEquals(NanoKvmAdministrationError.Kind.CONNECTION, failure.error.kind)
    }

    @Test
    fun `reported FQDN is readable while hostname writes remain single label`() = runTest {
        val current = binding()
        val port = FakeAdministrationPort().apply {
            hostnameValue = "rack.nanokvm.local"
        }
        val gateway = gateway(port, current) { current }

        assertEquals("rack.nanokvm.local", success(gateway.refreshHostname()).hostname)
        assertRejected(
            gateway.setHostname("rack.nanokvm.local"),
            NanoKvmAdministrationError.Kind.INVALID_REQUEST,
        )
        assertEquals(0, port.setHostnameCalls)
    }

    @Test
    fun `manual wifi connect owns password and dispatches once after fresh status`() = runTest {
        val port = FakeAdministrationPort()
        val password = charArrayOf('n', 'e', 't', '-', 's', 'e', 'c', 'r', 'e', 't')
        val current = binding()
        val gateway = gateway(port, current) { current }

        val result = gateway.connectWifi("manual-ssid", password)

        assertApplied(result)
        assertEquals(1, port.connectWifiCalls)
        assertEquals(2, port.wifiReads)
        assertEquals("manual-ssid", port.wifiSsid)
        assertTrue(password.all { it == '\u0000' })
        assertFalse(result.toString().contains("net-secret"))
    }

    @Test
    fun `tailscale restart is dispatched once but status cannot falsely prove it`() = runTest {
        val port = FakeAdministrationPort().apply {
            tailscaleState = NanoKvmTailscaleState.Running
        }
        val current = binding()
        val gateway = gateway(port, current) { current }

        val result = gateway.executeTailscale(NanoKvmTailscaleCommand.RESTART)

        assertTrue(result is NanoKvmAdministrationMutationResult.Accepted<*>)
        assertEquals(1, port.restartTailscaleCalls)
        assertEquals(2, port.tailscaleReads)
    }

    @Test
    fun `tailscale login approval is based on the fresh status and never replayed`() = runTest {
        val port = FakeAdministrationPort().apply {
            tailscaleState = NanoKvmTailscaleState.NotLoggedIn
        }
        val current = binding()
        val gateway = gateway(port, current) { current }

        val result = gateway.loginTailscale()

        assertTrue(result is NanoKvmAdministrationTailscaleLoginOutcome.Completed)
        assertEquals(1, port.loginTailscaleCalls)
        assertEquals(2, port.tailscaleReads)
        assertFalse(result.toString().contains("login.tailscale.com"))
    }

    @Test
    fun `live binding is revalidated immediately before setting dispatch`() = runTest {
        val port = FakeAdministrationPort()
        val captured = binding(generation = 4)
        var checks = 0
        val gateway = gateway(port, captured) {
            checks += 1
            if (checks >= 3) binding(generation = 5) else captured
        }

        val result = gateway.setSshEnabled(true)

        assertRejected(result, NanoKvmAdministrationError.Kind.SESSION_CHANGED)
        assertEquals(0, port.setSshCalls)
        assertEquals(1, port.sshReads)
    }

    @Test
    fun `ambiguous setting failure is read back and never replayed`() = runTest {
        val port = FakeAdministrationPort().apply { failSshAfterApplying = true }
        val current = binding()
        val gateway = gateway(port, current) { current }

        val result = gateway.setSshEnabled(true)

        assertTrue(result is NanoKvmAdministrationMutationResult.Reconciled<*>)
        val reconciled = result as NanoKvmAdministrationMutationResult.Reconciled<*>
        assertEquals(NanoKvmAdministrationObservation.DESIRED_STATE, reconciled.observation)
        assertEquals(NanoKvmAdministrationError.Kind.CONNECTION, reconciled.dispatchError.kind)
        assertEquals(NanoKvmAdministrationImpact.SECURITY_ACCESS_CHANGE, reconciled.impact)
        assertEquals(1, port.setSshCalls)
        assertEquals(2, port.sshReads)
    }

    @Test
    fun `failed ambiguous refresh remains indeterminate without replay`() = runTest {
        val port = FakeAdministrationPort().apply {
            failPreviewAfterApplying = true
            failPreviewReadAfterMutation = true
        }
        val current = binding()
        val gateway = gateway(port, current) { current }

        val result = gateway.setPreviewUpdatesEnabled(true)

        assertTrue(result is NanoKvmAdministrationMutationResult.Indeterminate<*>)
        val indeterminate = result as NanoKvmAdministrationMutationResult.Indeterminate<*>
        assertEquals(NanoKvmAdministrationError.Kind.CONNECTION, indeterminate.dispatchError.kind)
        assertEquals(NanoKvmAdministrationError.Kind.CONNECTION, indeterminate.refreshError?.kind)
        assertEquals(
            NanoKvmAdministrationGuidance.REFRESH_AUTHORITATIVE_STATE,
            indeterminate.guidance,
        )
        assertEquals(1, port.setPreviewCalls)
        assertEquals(2, port.previewReads)
    }

    @Test
    fun `exact desired setting is read before write and skips dispatch`() = runTest {
        val port = FakeAdministrationPort().apply { sshEnabled = true }
        val current = binding()
        val gateway = gateway(port, current) { current }

        val result = gateway.setSshEnabled(true)

        assertTrue(result is NanoKvmAdministrationMutationResult.AlreadySatisfied<*>)
        assertEquals(0, port.setSshCalls)
        assertEquals(1, port.sshReads)
    }

    @Test
    fun `password array is owned cleared and success requires credential invalidation`() = runTest {
        val port = FakeAdministrationPort()
        val current = binding()
        val gateway = gateway(port, current) { current }
        val password = charArrayOf('n', 'e', 'w', '-', 'p', 'a', 's', 's')

        val result = gateway.changePassword("admin", password)

        assertSame(NanoKvmAdministrationMutationResult.CredentialsChanged, result)
        assertTrue(NanoKvmAdministrationMutationResult.CredentialsChanged.mustInvalidateSavedCredential)
        assertTrue(NanoKvmAdministrationMutationResult.CredentialsChanged.mustEndAuthenticatedSession)
        assertEquals(
            NanoKvmAdministrationGuidance.CLEAR_SAVED_CREDENTIAL_AND_END_SESSION,
            result.guidance,
        )
        assertEquals(1, port.passwordChangeCalls)
        assertTrue(port.passwordHadContentDuringCall)
        assertSame(password, port.passwordReference)
        assertTrue(password.all { it == '\u0000' })
        assertFalse(result.toString().contains("new-pass"))
    }

    @Test
    fun `password is cleared when stale binding rejects before dispatch`() = runTest {
        val port = FakeAdministrationPort()
        val captured = binding(generation = 1)
        val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val gateway = gateway(port, captured) { binding(generation = 2) }

        val result = gateway.changePassword("admin", password)

        assertRejected(result, NanoKvmAdministrationError.Kind.SESSION_CHANGED)
        assertEquals(0, port.passwordChangeCalls)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun `ambiguous password response is never treated as confirmed success`() = runTest {
        val port = FakeAdministrationPort().apply { failPasswordAfterApplying = true }
        val current = binding()
        val gateway = gateway(port, current) { current }
        val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')

        val result = gateway.changePassword("admin", password)

        assertTrue(result is NanoKvmAdministrationMutationResult.Indeterminate<*>)
        assertEquals(
            NanoKvmAdministrationGuidance.VERIFY_NEW_CREDENTIALS_AFTER_RECONNECT,
            result.guidance,
        )
        assertEquals(1, port.passwordChangeCalls)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun `reboot acknowledgement is an explicit session breaking result`() = runTest {
        val port = FakeAdministrationPort()
        val current = binding()
        val gateway = gateway(port, current) { current }

        val result = gateway.rebootSystem()

        assertTrue(result is NanoKvmAdministrationMutationResult.DisruptiveCommandAccepted)
        assertEquals(NanoKvmAdministrationImpact.APPLIANCE_REBOOT, result.impact)
        assertEquals(
            NanoKvmAdministrationGuidance.WAIT_FOR_REBOOT_AND_RECONNECT,
            result.guidance,
        )
        assertEquals(1, port.rebootCalls)
    }

    @Test
    fun `online update is one shot and returns reconnect guidance`() = runTest {
        val port = FakeAdministrationPort()
        val current = binding()
        val gateway = gateway(port, current) { current }

        val result = gateway.startOnlineUpdate()

        assertTrue(result is NanoKvmAdministrationMutationResult.DisruptiveCommandAccepted)
        assertEquals(NanoKvmAdministrationImpact.SERVICE_RESTART, result.impact)
        assertEquals(NanoKvmAdministrationGuidance.RECONNECT_AND_REFRESH, result.guidance)
        assertEquals(1, port.onlineUpdateCalls)
    }

    @Test
    fun `all explicit administration settings apply with authoritative readback`() = runTest {
        val port = FakeAdministrationPort()
        val current = binding()
        val gateway = gateway(port, current) { current }

        assertApplied(gateway.setPreviewUpdatesEnabled(true))
        assertApplied(gateway.setOledSleep(NanoKvmOledSleepPreset.MINUTES_5))
        assertApplied(gateway.setSshEnabled(true))
        val hostname = gateway.setHostname("rack-kvm")
        assertApplied(hostname)
        assertEquals(
            NanoKvmAdministrationImpact.NAME_OR_NETWORK_ACCESS_CHANGE,
            hostname.impact,
        )
        assertEquals(
            NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
            hostname.guidance,
        )
        assertApplied(gateway.setMdnsEnabled(false))
        assertApplied(gateway.setCustomWebTitle("Rack console"))
        assertApplied(gateway.resetWebTitle())
        assertApplied(gateway.setManualDns(listOf("1.1.1.1", "2001:4860:4860::8888")))
        assertApplied(gateway.setDhcpDns())

        assertEquals(1, port.setPreviewCalls)
        assertEquals(1, port.setOledCalls)
        assertEquals(1, port.setSshCalls)
        assertEquals(1, port.setHostnameCalls)
        assertEquals(1, port.setMdnsCalls)
        assertEquals(1, port.setTitleCalls)
        assertEquals(1, port.resetTitleCalls)
        assertEquals(1, port.setManualDnsCalls)
        assertEquals(1, port.setDhcpDnsCalls)
    }

    @Test
    fun `oversized response is rejected without retaining server text in error`() = runTest {
        val port = FakeAdministrationPort().apply { username = "x".repeat(257) }
        val current = binding()
        val gateway = gateway(port, current) { current }

        val result = gateway.refreshAccount()

        assertTrue(result is NanoKvmAdministrationReadResult.Failure)
        val failure = result as NanoKvmAdministrationReadResult.Failure
        assertEquals(NanoKvmAdministrationError.Kind.INVALID_RESPONSE, failure.error.kind)
        assertEquals(
            "NanoKvmAdministrationError(kind=INVALID_RESPONSE)",
            failure.error.toString(),
        )
        assertFalse(failure.toString().contains("xxx"))
    }

    @Test
    fun `account and network snapshot string forms redact identifying data`() = runTest {
        val port = FakeAdministrationPort()
        val current = binding()
        val gateway = gateway(port, current) { current }

        val account = success(gateway.refreshAccount())
        val dns = success(gateway.refreshDns())

        assertFalse(account.toString().contains("admin"))
        assertFalse(dns.toString().contains("1.1.1.1"))
        assertFalse(gateway.binding.toString().contains("192.0.2.250"))
    }

    private fun binding(generation: Long = 1L) = NanoKvmSessionBinding(
        profileId = "office",
        authority = "192.0.2.250",
        sessionGeneration = generation,
    )

    private fun gateway(
        port: NanoKvmAdministrationPort,
        binding: NanoKvmSessionBinding,
        currentBinding: () -> NanoKvmSessionBinding?,
    ) = NanoKvmAdministrationGateway(port, binding, currentBinding)

    private fun <State> success(result: NanoKvmAdministrationReadResult<State>): State =
        (result as NanoKvmAdministrationReadResult.Success<State>).state

    private fun assertApplied(result: NanoKvmAdministrationMutationResult<*>) {
        assertTrue(result is NanoKvmAdministrationMutationResult.Applied<*>)
    }

    private fun assertRejected(
        result: NanoKvmAdministrationMutationResult<*>,
        kind: NanoKvmAdministrationError.Kind,
    ) {
        assertTrue(result is NanoKvmAdministrationMutationResult.Rejected)
        assertEquals(
            kind,
            (result as NanoKvmAdministrationMutationResult.Rejected).error.kind,
        )
    }
}

private class FakeAdministrationPort : NanoKvmAdministrationPort {
    var username = "admin"
    var passwordUpdated = true
    var passwordChangeCalls = 0
    var passwordHadContentDuringCall = false
    var passwordReference: CharArray? = null
    var failPasswordAfterApplying = false

    var currentVersion = "2.4.3"
    var latestVersion: String? = "2.4.4"
    var previewEnabled = false
    var previewReads = 0
    var setPreviewCalls = 0
    var failPreviewAfterApplying = false
    var failPreviewReadAfterMutation = false
    var onlineUpdateCalls = 0

    var rebootCalls = 0

    var oledExists = true
    var oledSleep = NanoKvmOledSleepPreset.SECONDS_30.seconds
    var setOledCalls = 0

    var sshEnabled = false
    var sshReads = 0
    var setSshCalls = 0
    var failSshAfterApplying = false

    var hostnameValue = "nanokvm"
    var setHostnameCalls = 0

    var mdnsEnabled = true
    var setMdnsCalls = 0

    var titleValue = NanoKvmWebTitle.DEFAULT
    var titleIsDefault = true
    var setTitleCalls = 0
    var resetTitleCalls = 0

    var dnsMode: NanoKvmDnsMode = NanoKvmDnsMode.Dhcp
    var dnsServers: List<String> = emptyList()
    var dnsReadFailure: Throwable? = null
    var setManualDnsCalls = 0
    var setDhcpDnsCalls = 0

    var wifiSupported = true
    var wifiAccessPointMode = false
    var wifiConnected = false
    var wifiSsid: String? = null
    var wifiReads = 0
    var connectWifiCalls = 0
    var disconnectWifiCalls = 0

    var tailscaleState: NanoKvmTailscaleState = NanoKvmTailscaleState.NotInstalled
    var tailscaleReads = 0
    var restartTailscaleCalls = 0
    var loginTailscaleCalls = 0

    override suspend fun currentAccount(): NanoKvmAccount = NanoKvmAccount(username)

    override suspend fun passwordStatus(): NanoKvmPasswordStatus =
        NanoKvmPasswordStatus(passwordUpdated)

    override suspend fun changePassword(username: String, password: CharArray) {
        passwordChangeCalls += 1
        passwordHadContentDuringCall = password.any { it != '\u0000' }
        passwordReference = password
        this.username = username
        passwordUpdated = true
        if (failPasswordAfterApplying) throw IOException("response lost")
    }

    override suspend fun applicationVersions(): NanoKvmApplicationVersions =
        NanoKvmApplicationVersions(
            current = currentVersion,
            latest = latestVersion,
            currentVersion = null,
            latestVersion = null,
        )

    override suspend fun previewUpdates(): NanoKvmPreviewUpdates {
        previewReads += 1
        if (failPreviewReadAfterMutation && setPreviewCalls > 0) {
            throw IOException("refresh unavailable")
        }
        return NanoKvmPreviewUpdates(previewEnabled)
    }

    override suspend fun setPreviewUpdates(enabled: Boolean) {
        setPreviewCalls += 1
        previewEnabled = enabled
        if (failPreviewAfterApplying) throw IOException("response lost")
    }

    override suspend fun startOnlineUpdate() {
        onlineUpdateCalls += 1
    }

    override suspend fun rebootSystem() {
        rebootCalls += 1
    }

    override suspend fun oledConfiguration(): NanoKvmOledConfiguration =
        NanoKvmOledConfiguration(
            exists = oledExists,
            sleepSeconds = oledSleep,
            sleepPreset = NanoKvmOledSleepPreset.fromSeconds(oledSleep),
        )

    override suspend fun setOledSleep(preset: NanoKvmOledSleepPreset) {
        setOledCalls += 1
        oledSleep = preset.seconds
    }

    override suspend fun sshState(): NanoKvmSshState {
        sshReads += 1
        return NanoKvmSshState(sshEnabled)
    }

    override suspend fun setSshEnabled(enabled: Boolean) {
        setSshCalls += 1
        sshEnabled = enabled
        if (failSshAfterApplying) throw IOException("response lost")
    }

    override suspend fun hostname(): NanoKvmHostname = NanoKvmHostname(hostnameValue)

    override suspend fun setHostname(hostname: String) {
        setHostnameCalls += 1
        hostnameValue = hostname
    }

    override suspend fun mdnsState(): NanoKvmMdnsState = NanoKvmMdnsState(mdnsEnabled)

    override suspend fun setMdnsEnabled(enabled: Boolean) {
        setMdnsCalls += 1
        mdnsEnabled = enabled
    }

    override suspend fun webTitle(): NanoKvmWebTitle = NanoKvmWebTitle(titleValue, titleIsDefault)

    override suspend fun setWebTitle(title: String) {
        setTitleCalls += 1
        titleValue = title
        titleIsDefault = false
    }

    override suspend fun resetWebTitle() {
        resetTitleCalls += 1
        titleValue = NanoKvmWebTitle.DEFAULT
        titleIsDefault = true
    }

    override suspend fun dnsConfiguration(): NanoKvmDnsConfiguration {
        dnsReadFailure?.let { throw it }
        return NanoKvmDnsConfiguration(
            mode = dnsMode,
            servers = dnsServers.map(NanoKvmIpAddress::parse),
            effectiveServers = listOf(NanoKvmIpAddress.parse("1.1.1.1")),
            dhcpServers = listOf(NanoKvmIpAddress.parse("192.0.2.1")),
            info = NanoKvmDnsInfo(
                interfaceName = "eth0",
                type = "ethernet",
                address = "192.0.2.250",
                subnetMask = "255.255.255.0",
                gateway = "192.0.2.1",
                searchDomains = listOf("lan"),
            ),
        )
    }

    override suspend fun setManualDns(servers: List<String>) {
        setManualDnsCalls += 1
        dnsMode = NanoKvmDnsMode.Manual
        dnsServers = servers.toList()
    }

    override suspend fun setDhcpDns() {
        setDhcpDnsCalls += 1
        dnsMode = NanoKvmDnsMode.Dhcp
        dnsServers = emptyList()
    }

    override suspend fun wifiStatus(): NanoKvmWifiStatus {
        wifiReads++
        return reflectedWifiStatus(
            supported = wifiSupported,
            accessPointMode = wifiAccessPointMode,
            connected = wifiConnected,
            ssid = wifiSsid,
        )
    }

    override suspend fun connectWifi(credentials: NanoKvmWifiCredentials) {
        connectWifiCalls++
        wifiConnected = true
        wifiSsid = credentials.ssid.value
    }

    override suspend fun disconnectWifi() {
        disconnectWifiCalls++
        wifiConnected = false
        wifiSsid = null
    }

    override suspend fun tailscaleStatus(): NanoKvmTailscaleStatus {
        tailscaleReads++
        return reflectedTailscaleStatus(tailscaleState)
    }

    override suspend fun installTailscale(approval: NanoKvmTailscaleActionApproval) =
        error("Tailscale install not configured for this test")

    override suspend fun uninstallTailscale(approval: NanoKvmTailscaleActionApproval) =
        error("Tailscale uninstall not configured for this test")

    override suspend fun startTailscale(approval: NanoKvmTailscaleActionApproval) =
        error("Tailscale start not configured for this test")

    override suspend fun stopTailscale(approval: NanoKvmTailscaleActionApproval) =
        error("Tailscale stop not configured for this test")

    override suspend fun restartTailscale(approval: NanoKvmTailscaleActionApproval) {
        restartTailscaleCalls++
    }

    override suspend fun bringTailscaleUp(approval: NanoKvmTailscaleActionApproval) =
        error("Tailscale up not configured for this test")

    override suspend fun bringTailscaleDown(approval: NanoKvmTailscaleActionApproval) =
        error("Tailscale down not configured for this test")

    override suspend fun loginTailscale(
        approval: NanoKvmTailscaleActionApproval,
    ): NanoKvmTailscaleLoginResult {
        loginTailscaleCalls++
        tailscaleState = NanoKvmTailscaleState.Running
        return NanoKvmTailscaleLoginResult.AlreadyAuthenticated
    }

    override suspend fun logoutTailscale(approval: NanoKvmTailscaleActionApproval) =
        error("Tailscale logout not configured for this test")
}

private fun reflectedWifiStatus(
    supported: Boolean,
    accessPointMode: Boolean,
    connected: Boolean,
    ssid: String?,
): NanoKvmWifiStatus {
    val constructor = NanoKvmWifiStatus::class.java.declaredConstructors
        .single { it.parameterTypes.size == 4 }
        .apply { isAccessible = true }
    return constructor.newInstance(
        supported,
        accessPointMode,
        connected,
        ssid?.let(NanoKvmWifiSsid::parse),
    ) as NanoKvmWifiStatus
}

private fun reflectedTailscaleStatus(state: NanoKvmTailscaleState): NanoKvmTailscaleStatus {
    val constructor = NanoKvmTailscaleStatus::class.java.declaredConstructors
        .single { it.parameterTypes.size == 4 }
        .apply { isAccessible = true }
    return constructor.newInstance(state, null, null, null) as NanoKvmTailscaleStatus
}

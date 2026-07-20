package org.nanokvm.protocol

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NanoKvmApplicationVersionTest {
    @Test
    fun `parses decorated application versions and preserves semantic components`() {
        assertEquals(
            NanoKvmApplicationVersion(
                major = 2,
                minor = 4,
                patch = 3,
                preRelease = "rc.2",
                buildMetadata = "capture.7",
            ),
            NanoKvmApplicationVersion.parse("NanoKVM v2.4.3-rc.2+capture.7"),
        )
        assertEquals("2.3.4", NanoKvmApplicationVersion.parse("v2.3.4")?.toString())
    }

    @Test
    fun `orders prereleases according to semantic version precedence`() {
        val alpha2 = requireNotNull(NanoKvmApplicationVersion.parse("2.4.3-alpha.2"))
        val alpha10 = requireNotNull(NanoKvmApplicationVersion.parse("2.4.3-alpha.10"))
        val releaseCandidate = requireNotNull(NanoKvmApplicationVersion.parse("2.4.3-rc.1"))
        val release = requireNotNull(NanoKvmApplicationVersion.parse("2.4.3"))
        val build = requireNotNull(NanoKvmApplicationVersion.parse("2.4.3+build.9"))

        assertTrue(alpha2 < alpha10)
        assertTrue(alpha10 < releaseCandidate)
        assertTrue(releaseCandidate < release)
        assertEquals(0, release.compareTo(build))
        assertTrue(
            requireNotNull(NanoKvmApplicationVersion.parse("2.4.3-99999999999999999999")) >
                requireNotNull(NanoKvmApplicationVersion.parse("2.4.3-10")),
        )
    }

    @Test
    fun `rejects partial malformed and overflowing versions`() {
        assertNull(NanoKvmApplicationVersion.parse("development"))
        assertNull(NanoKvmApplicationVersion.parse("2.4"))
        assertNull(NanoKvmApplicationVersion.parse("2.04.3"))
        assertNull(NanoKvmApplicationVersion.parse("999999999999.4.3"))
        assertNull(NanoKvmApplicationVersion.parse("prefix2.4.3suffix"))
    }
}

class NanoKvmCapabilityProbeTest {
    private lateinit var server: MockWebServer
    private lateinit var tokenStore: InMemorySessionTokenStore
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = InMemorySessionTokenStore("probe-token")
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = tokenStore,
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `optional endpoint failures produce a partial three-state result`() = runBlocking {
        server.enqueue(envelope("""{"application":"2.4.3"}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.api.probeCapabilities()

        assertTrue(result.vmInfo is NanoKvmProbeResult.Supported)
        assertEquals(NanoKvmProbeResult.Unsupported(404), result.hardware)
        assertEquals(
            NanoKvmProbeResult.Unknown(
                NanoKvmProbeFailure(
                    kind = NanoKvmProbeFailureKind.HTTP,
                    statusCode = 503,
                    retryable = true,
                ),
            ),
            result.gpio,
        )
        assertTrue(
            result.capabilities[NanoKvmCapability.VM_INFORMATION] is
                NanoKvmCapabilitySupport.Supported,
        )
        assertTrue(
            result.capabilities[NanoKvmCapability.HARDWARE_INFORMATION] is
                NanoKvmCapabilitySupport.Unsupported,
        )
        assertEquals(
            NanoKvmCapabilitySupport.Unknown(
                NanoKvmCapabilityUnknownReason.OPTIONAL_ENDPOINT_FAILED,
            ),
            result.capabilities[NanoKvmCapability.GPIO_STATUS],
        )
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `documented version floors never imply runtime hardware support`() = runBlocking {
        server.enqueue(envelope("""{"application":"2.4.3"}"""))
        server.enqueue(envelope("""{"version":"NanoKVM Cube"}"""))
        server.enqueue(envelope("""{"pwr":true,"hdd":false}"""))

        val capabilities = client.api.probeCapabilities().capabilities

        assertEquals(
            NanoKvmApplicationVersion(2, 4, 3),
            capabilities.applicationVersion,
        )
        assertVersionSupported(capabilities, NanoKvmCapability.DIRECT_H264, "2.2.7")
        assertVersionSupported(capabilities, NanoKvmCapability.CAPTURE_STATUS_REPORTING, "2.4.2")
        assertVersionSupported(capabilities, NanoKvmCapability.SAVED_HID_SHORTCUTS, "2.3.2")
        assertVersionSupported(capabilities, NanoKvmCapability.HID_LEADER_KEY, "2.3.4")
        assertVersionSupported(capabilities, NanoKvmCapability.AUTOSTART_SCRIPTS, "2.3.1")
        assertVersionSupported(
            capabilities,
            NanoKvmCapability.MEMORY_LIMIT_CONFIGURATION,
            "2.1.4",
        )
        assertVersionSupported(capabilities, NanoKvmCapability.MOUSE_JIGGLER, "2.2.6")
        assertVersionSupported(capabilities, NanoKvmCapability.SWAP_CONFIGURATION, "2.2.6")
        assertVersionSupported(capabilities, NanoKvmCapability.TLS_ENABLE, "2.2.7")
        assertRuntimeProbeRequired(
            capabilities,
            NanoKvmCapability.PCIE_HDMI_RESET,
            "2.1.5",
        )
        assertEquals(
            NanoKvmCapabilitySupport.Unknown(
                reason = NanoKvmCapabilityUnknownReason.RUNTIME_PROBE_REQUIRED,
                minimumVersion = NanoKvmApplicationVersion(2, 2, 8),
            ),
            capabilities[NanoKvmCapability.PCIE_HDMI_CONTROL],
        )
        assertRuntimeProbeRequired(
            capabilities,
            NanoKvmCapability.VIRTUAL_MEDIA_MOUNT,
            "2.3.2",
        )
        assertRuntimeProbeRequired(
            capabilities,
            NanoKvmCapability.VIRTUAL_USB_DEVICE_CONFIGURATION,
            "2.3.2",
        )
        assertRuntimeProbeRequired(
            capabilities,
            NanoKvmCapability.WAKE_ON_LAN,
            "2.3.2",
        )
        assertVersionSupported(capabilities, NanoKvmCapability.TERMINAL, "2.3.2")
        assertVersionSupported(capabilities, NanoKvmCapability.OLED_CONFIGURATION, "2.1.4")
        assertRuntimeProbeRequired(
            capabilities,
            NanoKvmCapability.WIFI_CONFIGURATION,
            "2.1.2",
        )
        assertVersionSupported(capabilities, NanoKvmCapability.TAILSCALE_EXTENSION, "2.1.6")
    }

    @Test
    fun `versions below a documented floor are explicitly unsupported`() = runBlocking {
        server.enqueue(envelope("""{"application":"2.3.2"}"""))
        server.enqueue(envelope("""{"version":"1"}"""))
        server.enqueue(envelope("""{"pwr":false,"hdd":false}"""))

        val capabilities = client.api.probeCapabilities().capabilities

        assertVersionUnsupported(capabilities, NanoKvmCapability.RESOLUTION_640_X_480, "2.3.6")
        assertVersionUnsupported(capabilities, NanoKvmCapability.PICOCLAW, "2.4.0")
        assertVersionUnsupported(capabilities, NanoKvmCapability.FRENCH_KEYBOARD_MAPPING, "2.4.1")
        assertVersionSupported(capabilities, NanoKvmCapability.SAVED_HID_SHORTCUTS, "2.3.2")
        assertVersionSupported(capabilities, NanoKvmCapability.AUTOSTART_SCRIPTS, "2.3.1")
        assertVersionUnsupported(capabilities, NanoKvmCapability.HID_LEADER_KEY, "2.3.4")
    }

    @Test
    fun `device-control floors follow the pinned upstream changelog`() = runBlocking {
        server.enqueue(envelope("""{"application":"2.1.4"}"""))
        server.enqueue(envelope("""{"version":"NanoKVM PCIe"}"""))
        server.enqueue(envelope("""{"pwr":false,"hdd":false}"""))

        val capabilities = client.api.probeCapabilities().capabilities

        assertVersionSupported(
            capabilities,
            NanoKvmCapability.MEMORY_LIMIT_CONFIGURATION,
            "2.1.4",
        )
        assertVersionUnsupported(capabilities, NanoKvmCapability.PCIE_HDMI_RESET, "2.1.5")
        assertRuntimeProbeRequired(
            capabilities,
            NanoKvmCapability.WIFI_CONFIGURATION,
            "2.1.2",
        )
        assertVersionUnsupported(capabilities, NanoKvmCapability.TAILSCALE_EXTENSION, "2.1.6")
        assertVersionUnsupported(capabilities, NanoKvmCapability.MOUSE_JIGGLER, "2.2.6")
        assertVersionUnsupported(capabilities, NanoKvmCapability.SWAP_CONFIGURATION, "2.2.6")
        assertVersionUnsupported(capabilities, NanoKvmCapability.TLS_ENABLE, "2.2.7")
        assertVersionUnsupported(capabilities, NanoKvmCapability.PCIE_HDMI_CONTROL, "2.2.8")
        assertVersionUnsupported(
            capabilities,
            NanoKvmCapability.VIRTUAL_USB_DEVICE_CONFIGURATION,
            "2.3.2",
        )
    }

    @Test
    fun `missing application version leaves version-derived support unknown`() = runBlocking {
        server.enqueue(envelope("""{"application":"development"}"""))
        server.enqueue(envelope("""{"version":"1"}"""))
        server.enqueue(envelope("""{"pwr":false,"hdd":false}"""))

        val capabilities = client.api.probeCapabilities().capabilities

        assertNull(capabilities.applicationVersion)
        assertEquals(
            NanoKvmCapabilitySupport.Unknown(
                reason = NanoKvmCapabilityUnknownReason.VERSION_NOT_REPORTED,
                minimumVersion = NanoKvmApplicationVersion(2, 3, 2),
            ),
            capabilities[NanoKvmCapability.STANDARD_HID_WEBSOCKET],
        )
    }

    @Test
    fun `authentication expiry remains a terminal session result`() {
        server.enqueue(MockResponse().setResponseCode(401))

        assertThrows(AuthenticationExpiredException::class.java) {
            runBlocking { client.api.probeCapabilities() }
        }
        assertNull(tokenStore.read())
        assertEquals(1, server.requestCount)
    }

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("""{"code":0,"msg":"success","data":$data}""")

    private fun assertVersionSupported(
        capabilities: NanoKvmServerCapabilities,
        capability: NanoKvmCapability,
        minimum: String,
    ) {
        assertEquals(
            NanoKvmCapabilitySupport.Supported(
                NanoKvmCapabilityEvidence.VersionFloor(
                    actual = requireNotNull(capabilities.applicationVersion),
                    minimum = requireNotNull(NanoKvmApplicationVersion.parse(minimum)),
                ),
            ),
            capabilities[capability],
        )
    }

    private fun assertVersionUnsupported(
        capabilities: NanoKvmServerCapabilities,
        capability: NanoKvmCapability,
        minimum: String,
    ) {
        assertEquals(
            NanoKvmCapabilitySupport.Unsupported(
                NanoKvmCapabilityEvidence.VersionFloor(
                    actual = requireNotNull(capabilities.applicationVersion),
                    minimum = requireNotNull(NanoKvmApplicationVersion.parse(minimum)),
                ),
            ),
            capabilities[capability],
        )
    }

    private fun assertRuntimeProbeRequired(
        capabilities: NanoKvmServerCapabilities,
        capability: NanoKvmCapability,
        minimum: String,
    ) {
        assertEquals(
            NanoKvmCapabilitySupport.Unknown(
                reason = NanoKvmCapabilityUnknownReason.RUNTIME_PROBE_REQUIRED,
                minimumVersion = requireNotNull(NanoKvmApplicationVersion.parse(minimum)),
            ),
            capabilities[capability],
        )
    }
}

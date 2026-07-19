package org.nanokvm.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NanoKvmAdministrationApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("phase-four-token"),
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `account password status and password change use exact authenticated contract`() = runBlocking {
        server.enqueue(envelope("""{"username":"admin","future":true}"""))
        server.enqueue(envelope("""{"isUpdated":true}"""))
        server.enqueue(successWithoutData())

        assertEquals(NanoKvmAccount("admin"), client.api.currentAccount())
        assertEquals(NanoKvmPasswordStatus(isUpdated = true), client.api.passwordStatus())
        client.api.changePassword("operator", "correct horse battery staple".toCharArray())

        takeAuthenticated("GET", "/api/auth/account")
        takeAuthenticated("GET", "/api/auth/password")
        val request = takeAuthenticated("POST", "/api/auth/password")
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("operator", body.getValue("username").jsonPrimitive.content)
        val encrypted = body.getValue("password").jsonPrimitive.content
        assertFalse(encrypted.contains("correct horse"))
        assertEquals(
            "correct horse battery staple",
            NanoKvmPasswordCipher.decryptForCompatibilityTest(encrypted),
        )
    }

    @Test
    fun `application preview online update and reboot use 2_4_3 golden routes`() = runBlocking {
        server.enqueue(envelope("""{"current":"2.4.3","latest":"2.5.0","future":"kept-safe"}"""))
        server.enqueue(envelope("""{"enabled":false}"""))
        repeat(3) { server.enqueue(successWithoutData()) }

        val versions = client.api.applicationVersions()
        assertEquals("2.4.3", versions.current)
        assertEquals("2.5.0", versions.latest)
        assertEquals(NanoKvmApplicationVersion(2, 4, 3), versions.currentVersion)
        assertEquals(NanoKvmApplicationVersion(2, 5, 0), versions.latestVersion)
        assertEquals(NanoKvmPreviewUpdates(enabled = false), client.api.previewUpdates())
        client.api.setPreviewUpdates(enabled = true)
        client.api.startOnlineUpdate()
        client.api.rebootSystem()

        takeAuthenticated("GET", "/api/application/version")
        takeAuthenticated("GET", "/api/application/preview")
        assertJsonBody(
            takeAuthenticated("POST", "/api/application/preview"),
            """{"enable":true}""",
        )
        assertEmptyJsonPost(takeAuthenticated("POST", "/api/application/update"))
        assertEmptyJsonPost(takeAuthenticated("POST", "/api/vm/system/reboot"))
    }

    @Test
    fun `OLED SSH hostname mDNS and web title use explicit golden requests`() = runBlocking {
        server.enqueue(envelope("""{"exist":true,"sleep":42}"""))
        server.enqueue(successWithoutData())
        server.enqueue(envelope("""{"enabled":false}"""))
        repeat(2) { server.enqueue(successWithoutData()) }
        server.enqueue(envelope("""{"hostname":"nanokvm"}"""))
        server.enqueue(successWithoutData())
        server.enqueue(envelope("""{"enabled":true}"""))
        repeat(2) { server.enqueue(successWithoutData()) }
        server.enqueue(envelope("""{"title":"Rack console"}"""))
        repeat(2) { server.enqueue(successWithoutData()) }

        val oled = client.api.oledConfiguration()
        assertTrue(oled.exists)
        assertEquals(42, oled.sleepSeconds)
        assertNull(oled.sleepPreset)
        client.api.setOledSleep(NanoKvmOledSleepPreset.MINUTES_30)
        assertEquals(NanoKvmSshState(false), client.api.sshState())
        client.api.setSshEnabled(true)
        client.api.setSshEnabled(false)
        assertEquals(NanoKvmHostname("nanokvm"), client.api.hostname())
        client.api.setHostname("rack-kvm-3")
        assertEquals(NanoKvmMdnsState(true), client.api.mdnsState())
        client.api.setMdnsEnabled(false)
        client.api.setMdnsEnabled(true)
        assertEquals(NanoKvmWebTitle("Rack console", isDefault = false), client.api.webTitle())
        client.api.setWebTitle("Desk KVM")
        client.api.resetWebTitle()

        takeAuthenticated("GET", "/api/vm/oled")
        assertJsonBody(takeAuthenticated("POST", "/api/vm/oled"), """{"sleep":1800}""")
        takeAuthenticated("GET", "/api/vm/ssh")
        assertEmptyJsonPost(takeAuthenticated("POST", "/api/vm/ssh/enable"))
        assertEmptyJsonPost(takeAuthenticated("POST", "/api/vm/ssh/disable"))
        takeAuthenticated("GET", "/api/vm/hostname")
        assertJsonBody(
            takeAuthenticated("POST", "/api/vm/hostname"),
            """{"hostname":"rack-kvm-3"}""",
        )
        takeAuthenticated("GET", "/api/vm/mdns")
        assertEmptyJsonPost(takeAuthenticated("POST", "/api/vm/mdns/disable"))
        assertEmptyJsonPost(takeAuthenticated("POST", "/api/vm/mdns/enable"))
        takeAuthenticated("GET", "/api/vm/web-title")
        assertJsonBody(
            takeAuthenticated("POST", "/api/vm/web-title"),
            """{"title":"Desk KVM"}""",
        )
        assertJsonBody(takeAuthenticated("POST", "/api/vm/web-title"), """{"title":""}""")
    }

    @Test
    fun `DNS read and setters are typed canonical and exact`() = runBlocking {
        server.enqueue(
            envelope(
                """{
                    "mode":"manual",
                    "servers":["192.0.2.53","2001:0db8:0:0:0:0:0:53"],
                    "effective":["192.0.2.53","2001:db8::53"],
                    "dhcp":["198.51.100.53"],
                    "info":{
                        "interface":"eth0",
                        "type":"wired",
                        "address":"192.0.2.250",
                        "subnetMask":"255.255.255.0",
                        "gateway":"192.0.2.1",
                        "searchDomains":["lab.example"],
                        "future":"ignored"
                    },
                    "future":"ignored"
                }""".trimIndent(),
            ),
        )
        repeat(2) { server.enqueue(successWithoutData()) }

        val dns = client.api.dnsConfiguration()
        assertEquals(NanoKvmDnsMode.Manual, dns.mode)
        assertEquals(listOf("192.0.2.53", "2001:db8::53"), dns.servers.map { it.value })
        assertEquals(listOf("192.0.2.53", "2001:db8::53"), dns.effectiveServers.map { it.value })
        assertEquals(listOf("198.51.100.53"), dns.dhcpServers.map { it.value })
        assertEquals("eth0", dns.info.interfaceName)
        assertEquals(listOf("lab.example"), dns.info.searchDomains)

        client.api.setManualDns(listOf("192.0.2.53", "2001:0DB8:0:0:0:0:0:53"))
        client.api.setDhcpDns()

        takeAuthenticated("GET", "/api/network/dns")
        assertJsonBody(
            takeAuthenticated("POST", "/api/network/dns"),
            """{"mode":"manual","servers":["192.0.2.53","2001:db8::53"]}""",
        )
        assertJsonBody(
            takeAuthenticated("POST", "/api/network/dns"),
            """{"mode":"dhcp","servers":[]}""",
        )
    }

    @Test
    fun `nil DNS slices decode as empty and reported FQDN remains readable`() = runBlocking {
        server.enqueue(
            envelope(
                """{
                    "mode":"dhcp",
                    "servers":null,
                    "effective":null,
                    "dhcp":null,
                    "info":{"searchDomains":null}
                }""".trimIndent(),
            ),
        )
        server.enqueue(envelope("""{"hostname":"rack.nanokvm.local"}"""))

        val dns = client.api.dnsConfiguration()
        assertTrue(dns.servers.isEmpty())
        assertTrue(dns.effectiveServers.isEmpty())
        assertTrue(dns.dhcpServers.isEmpty())
        assertTrue(dns.info.searchDomains.isEmpty())
        assertEquals("rack.nanokvm.local", client.api.hostname().value)

        takeAuthenticated("GET", "/api/network/dns")
        takeAuthenticated("GET", "/api/vm/hostname")
        Unit
    }

    @Test
    fun `mutation validation rejects unsafe values before network IO`() {
        val invalidCalls =
            listOf<suspend () -> Unit>(
                { client.api.changePassword("bad/user", "valid".toCharArray()) },
                { client.api.changePassword("operator", charArrayOf()) },
                { client.api.changePassword("operator", "bad/password".toCharArray()) },
                { client.api.changePassword("operator", CharArray(257) { 'a' }) },
                { client.api.changePassword("operator", CharArray(86) { '\u20ac' }) },
                { client.api.changePassword("operator", charArrayOf('\uD800')) },
                { client.api.setHostname("nanokvm.local") },
                { client.api.setHostname("-nanokvm") },
                { client.api.setHostname("x".repeat(64)) },
                { client.api.setWebTitle("") },
                { client.api.setWebTitle(NanoKvmWebTitle.DEFAULT) },
                { client.api.setWebTitle("bad\ntitle") },
                { client.api.setWebTitle("x".repeat(257)) },
                { client.api.setManualDns(emptyList()) },
                { client.api.setManualDns(List(7) { "192.0.2.${it + 1}" }) },
                { client.api.setManualDns(listOf("2001:db8::1", "2001:0db8:0:0:0:0:0:1")) },
                { client.api.setManualDns(listOf("resolver.example")) },
                { client.api.setManualDns(listOf("192.0.002.1")) },
                { client.api.setManualDns(listOf("fe80::1%eth0")) },
            )

        invalidCalls.forEach { call ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { call() } }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `OLED allowlist IP parsing and reset semantics are explicit`() = runBlocking {
        assertEquals(
            listOf(0, 15, 30, 60, 180, 300, 600, 1_800, 3_600),
            NanoKvmOledSleepPreset.entries.map { it.seconds },
        )
        assertNull(NanoKvmOledSleepPreset.fromSeconds(42))
        assertEquals("0.0.0.0", NanoKvmIpAddress.parse("0.0.0.0").value)
        assertEquals("2001:db8::1", NanoKvmIpAddress.parse("2001:0DB8:0:0:0:0:0:1").value)
        assertEquals("::", NanoKvmIpAddress.parse("0:0:0:0:0:0:0:0").value)

        server.enqueue(envelope("""{"title":""}"""))
        assertEquals(
            NanoKvmWebTitle(NanoKvmWebTitle.DEFAULT, isDefault = true),
            client.api.webTitle(),
        )
    }

    @Test
    fun `only exact 2_4_3 missing web title error maps to default`() = runBlocking {
        server.enqueue(apiError(-1, "read web title failed"))
        server.enqueue(apiError(-1, "different failure"))
        server.enqueue(apiError(-2, "read web title failed"))

        assertEquals(
            NanoKvmWebTitle(NanoKvmWebTitle.DEFAULT, isDefault = true),
            client.api.webTitle(),
        )
        val differentMessage = assertThrows(ApiResponseException::class.java) {
            runBlocking { client.api.webTitle() }
        }
        assertEquals("", differentMessage.serverMessage)
        assertFalse(differentMessage.toString().contains("different failure"))
        val differentCode = assertThrows(ApiResponseException::class.java) {
            runBlocking { client.api.webTitle() }
        }
        assertEquals(-2, differentCode.code)
    }

    @Test
    fun `bounded unknown response values survive while malformed or excessive data fails`() = runBlocking {
        server.enqueue(
            envelope(
                """{
                    "mode":"future-managed",
                    "servers":[],
                    "effective":[],
                    "dhcp":[],
                    "info":{}
                }""".trimIndent(),
            ),
        )
        server.enqueue(envelope("""{"current":"vendor-build","latest":""}"""))

        assertEquals(NanoKvmDnsMode.Other("future-managed"), client.api.dnsConfiguration().mode)
        val versions = client.api.applicationVersions()
        assertEquals("vendor-build", versions.current)
        assertNull(versions.latest)
        assertNull(versions.currentVersion)

        val invalidResponses =
            listOf(
                "/api/vm/oled" to """{"exist":true,"sleep":-1}""",
                "/api/auth/account" to """{"username":"${"x".repeat(257)}"}""",
                "/api/application/version" to """{"current":"${"x".repeat(65)}","latest":""}""",
                "/api/vm/hostname" to """{"hostname":"${"x".repeat(254)}"}""",
                "/api/vm/web-title" to """{"title":"${"x".repeat(257)}"}""",
                "/api/network/dns" to
                    """{"mode":"manual","servers":["not-an-ip"],"info":{}}""",
                "/api/network/dns" to
                    """{"mode":"manual","servers":${List(7) { "\"192.0.2.${it + 1}\"" }},"info":{}}""",
            )
        invalidResponses.forEach { (path, data) ->
            server.enqueue(envelope(data))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking {
                    when (path) {
                        "/api/vm/oled" -> client.api.oledConfiguration()
                        "/api/auth/account" -> client.api.currentAccount()
                        "/api/application/version" -> client.api.applicationVersions()
                        "/api/vm/hostname" -> client.api.hostname()
                        "/api/vm/web-title" -> client.api.webTitle()
                        else -> client.api.dnsConfiguration()
                    }
                }
            }
        }
    }

    private fun takeAuthenticated(method: String, path: String): RecordedRequest {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals("nano-kvm-token=phase-four-token", request.getHeader("Cookie"))
        return request
    }

    private fun assertJsonBody(request: RecordedRequest, expected: String) {
        assertEquals(
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(request.body.readUtf8()),
        )
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
    }

    private fun assertEmptyJsonPost(request: RecordedRequest) {
        assertEquals(0L, request.bodySize)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
    }

    private fun envelope(data: String): MockResponse = jsonResponse(
        """{"code":0,"msg":"success","data":$data}""",
    )

    private fun successWithoutData(): MockResponse = jsonResponse(
        """{"code":0,"msg":"success","data":null}""",
    )

    private fun apiError(code: Int, message: String): MockResponse = jsonResponse(
        """{"code":$code,"msg":"$message","data":null}""",
    )

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody(body)
}

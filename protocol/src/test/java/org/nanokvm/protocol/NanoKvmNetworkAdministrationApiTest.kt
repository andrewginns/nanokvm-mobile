package org.nanokvm.protocol

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NanoKvmNetworkAdministrationApiTest {
    private lateinit var server: MockWebServer
    private lateinit var tokenStore: InMemorySessionTokenStore
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = InMemorySessionTokenStore("network-admin-cookie")
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
    fun `wifi status is bounded privacy-redacted and uses authenticated exact route`() = runBlocking {
        server.enqueue(
            success(
                """{"supported":true,"apMode":false,"connected":true,"ssid":"Lab Wi-Fi","future":"ignored"}""",
            ),
        )

        val status = client.api.wifiStatus()

        assertTrue(status.supported)
        assertFalse(status.accessPointMode)
        assertTrue(status.connected)
        assertEquals("Lab Wi-Fi", status.ssid?.value)
        assertFalse(status.toString().contains("Lab Wi-Fi"))
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/network/wifi", request.path)
        assertEquals("nano-kvm-token=network-admin-cookie", request.getHeader("Cookie"))
    }

    @Test
    fun `authenticated wifi connect consumes secret emits exact json and cannot replay`() =
        runBlocking {
            server.enqueue(successNoData())
            val password = "wireless secret".toCharArray()
            val credentials = NanoKvmWifiCredentials("Lab Wi-Fi", password)
            assertFalse(credentials.toString().contains("wireless secret"))

            client.api.connectWifi(credentials)

            assertTrue(password.all { it == '\u0000' })
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/network/wifi/connect", request.path)
            assertEquals("nano-kvm-token=network-admin-cookie", request.getHeader("Cookie"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("Lab Wi-Fi", body.getValue("ssid").jsonPrimitive.content)
            assertEquals("wireless secret", body.getValue("password").jsonPrimitive.content)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { client.api.connectWifi(credentials) }
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `authenticated wifi disconnect uses exact no-body route once`() = runBlocking {
        server.enqueue(successNoData())

        client.api.disconnectWifi()

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/network/wifi/disconnect", request.path)
        assertEquals(0L, request.bodySize)
        assertEquals("nano-kvm-token=network-admin-cookie", request.getHeader("Cookie"))
    }

    @Test
    fun `wifi credentials enforce bounded text without putting values in errors`() {
        val oversizedSsid = "x".repeat(33)
        val ssidError = assertThrows(IllegalArgumentException::class.java) {
            NanoKvmWifiCredentials(oversizedSsid, "secret".toCharArray())
        }
        assertFalse(ssidError.toString().contains(oversizedSsid))

        val secret = CharArray(129) { 's' }
        val secretText = secret.concatToString()
        val passwordError = assertThrows(IllegalArgumentException::class.java) {
            NanoKvmWifiCredentials("valid", secret)
        }
        assertFalse(passwordError.toString().contains(secretText))
        assertTrue(secret.all { it == '\u0000' })

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tailscale status preserves bounded unknown state as read only`() = runBlocking {
        server.enqueue(
            success(
                """{"state":"futurePaused","name":"device","ip":"100.64.0.2","account":"private.example"}""",
            ),
        )

        val status = client.api.tailscaleStatus()

        assertTrue(status.state is NanoKvmTailscaleState.Other)
        assertEquals("futurePaused", status.state.wireValue)
        assertEquals("100.64.0.2", status.ipv4?.value)
        assertFalse(status.toString().contains("private.example"))
        val approval = NanoKvmTailscaleActionApproval.afterUserConfirmed(
            status,
            NanoKvmTailscaleCommand.START,
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.startTailscale(approval) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `all exact tailscale routes require a fresh known status and one-shot approval`() =
        runBlocking {
            val cases = listOf(
                NanoKvmTailscaleCommand.INSTALL to "notInstall",
                NanoKvmTailscaleCommand.UNINSTALL to "notRunning",
                NanoKvmTailscaleCommand.START to "notRunning",
                NanoKvmTailscaleCommand.STOP to "running",
                NanoKvmTailscaleCommand.RESTART to "running",
                NanoKvmTailscaleCommand.UP to "stopped",
                NanoKvmTailscaleCommand.DOWN to "running",
                NanoKvmTailscaleCommand.LOGIN to "notLogin",
                NanoKvmTailscaleCommand.LOGOUT to "running",
            )
            cases.forEach { (command, state) ->
                server.enqueue(success(tailscaleStatusJson(state)))
                if (command == NanoKvmTailscaleCommand.LOGIN) {
                    server.enqueue(
                        success("""{"url":"https://login.tailscale.com/a/auth_token-1"}"""),
                    )
                } else {
                    server.enqueue(successNoData())
                }

                val status = client.api.tailscaleStatus()
                val approval = NanoKvmTailscaleActionApproval.afterUserConfirmed(status, command)
                val result = invokeTailscale(command, approval)
                if (result is NanoKvmTailscaleLoginResult.AuthorizationRequired) {
                    assertEquals(
                        "https://login.tailscale.com/a/auth_token-1",
                        result.url.value,
                    )
                    assertFalse(result.toString().contains("auth_token-1"))
                }

                val statusRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("GET", statusRequest.method)
                assertEquals("/api/extensions/tailscale/status", statusRequest.path)
                assertEquals(
                    "nano-kvm-token=network-admin-cookie",
                    statusRequest.getHeader("Cookie"),
                )
                val mutation = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("POST", mutation.method)
                assertEquals(command.path, mutation.path)
                assertEquals(0L, mutation.bodySize)
                assertEquals(
                    "nano-kvm-token=network-admin-cookie",
                    mutation.getHeader("Cookie"),
                )

                assertThrows(IllegalStateException::class.java) {
                    runBlocking { invokeTailscale(command, approval) }
                }
            }
            assertEquals(cases.size * 2, server.requestCount)
        }

    @Test
    fun `tailscale state mismatch and stale snapshot are rejected before a mutation`() =
        runBlocking {
            server.enqueue(success(tailscaleStatusJson("notInstall")))
            val status = client.api.tailscaleStatus()
            val wrongState = NanoKvmTailscaleActionApproval.afterUserConfirmed(
                status,
                NanoKvmTailscaleCommand.DOWN,
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.api.bringTailscaleDown(wrongState) }
            }

            server.enqueue(success(tailscaleStatusJson("notRunning")))
            val newest = client.api.tailscaleStatus()
            val staleApproval = NanoKvmTailscaleActionApproval.afterUserConfirmed(
                status,
                NanoKvmTailscaleCommand.INSTALL,
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.api.installTailscale(staleApproval) }
            }
            assertEquals(NanoKvmTailscaleState.NotRunning, newest.state)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `tailscale errors are redacted and ambiguous commands cannot replay`() = runBlocking {
        server.enqueue(success(tailscaleStatusJson("running")))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"code":-1,"msg":"failed with auth_token-1"}"""),
        )
        val status = client.api.tailscaleStatus()
        val approval = NanoKvmTailscaleActionApproval.afterUserConfirmed(
            status,
            NanoKvmTailscaleCommand.DOWN,
        )

        val error = assertThrows(NanoKvmTailscaleOperationException::class.java) {
            runBlocking { client.api.bringTailscaleDown(approval) }
        }

        assertEquals(-1, error.apiCode)
        assertFalse(error.toString().contains("auth_token-1"))
        val replay = NanoKvmTailscaleActionApproval.afterUserConfirmed(
            status,
            NanoKvmTailscaleCommand.DOWN,
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.api.bringTailscaleDown(replay) }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `tailscale login allows only official https authorization urls`() = runBlocking {
        val rejected = listOf(
            "http://login.tailscale.com/a/token",
            "https://login.tailscale.com.evil.test/a/token",
            "https://login.tailscale.com/not-auth/token",
            "https://user@login.tailscale.com/a/token",
            "https://login.tailscale.com/a/token?redirect=evil",
        )
        rejected.forEach { url ->
            server.enqueue(success(tailscaleStatusJson("notLogin")))
            server.enqueue(success("""{"url":"$url"}"""))
            val status = client.api.tailscaleStatus()
            val approval = NanoKvmTailscaleActionApproval.afterUserConfirmed(
                status,
                NanoKvmTailscaleCommand.LOGIN,
            )
            val error = assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { client.api.loginTailscale(approval) }
            }
            assertFalse(error.toString().contains(url))
        }
        assertEquals(rejected.size * 2, server.requestCount)
    }

    private suspend fun invokeTailscale(
        command: NanoKvmTailscaleCommand,
        approval: NanoKvmTailscaleActionApproval,
    ): NanoKvmTailscaleLoginResult? = when (command) {
        NanoKvmTailscaleCommand.INSTALL -> client.api.installTailscale(approval).let { null }
        NanoKvmTailscaleCommand.UNINSTALL -> client.api.uninstallTailscale(approval).let { null }
        NanoKvmTailscaleCommand.START -> client.api.startTailscale(approval).let { null }
        NanoKvmTailscaleCommand.STOP -> client.api.stopTailscale(approval).let { null }
        NanoKvmTailscaleCommand.RESTART -> client.api.restartTailscale(approval).let { null }
        NanoKvmTailscaleCommand.UP -> client.api.bringTailscaleUp(approval).let { null }
        NanoKvmTailscaleCommand.DOWN -> client.api.bringTailscaleDown(approval).let { null }
        NanoKvmTailscaleCommand.LOGIN -> client.api.loginTailscale(approval)
        NanoKvmTailscaleCommand.LOGOUT -> client.api.logoutTailscale(approval).let { null }
    }

    private fun tailscaleStatusJson(state: String): String = when (state) {
        "running", "stopped" ->
            """{"state":"$state","name":"nano","ip":"100.64.0.2","account":"tailnet"}"""
        else -> """{"state":"$state","name":"","ip":"","account":""}"""
    }

    private fun success(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("""{"code":0,"msg":"success","data":$data}""")

    private fun successNoData(): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("""{"code":0,"msg":"success"}""")
}

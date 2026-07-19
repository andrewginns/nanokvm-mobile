package org.nanokvm.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class NanoKvmPicoClawApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NanoKvmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NanoKvmClient.create(
            endpoint = NanoKvmEndpoint.parse(server.url("/").toString()),
            tokenStore = InMemorySessionTokenStore("pico-cookie"),
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `entry is version-gated and performs no implicit runtime probe`() {
        NanoKvmPicoClaw.enter(client, NanoKvmApplicationVersion(2, 4, 0))
        NanoKvmPicoClaw.enter(client, NanoKvmApplicationVersion(2, 4, 3))
        assertEquals(0, server.requestCount)

        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPicoClaw.enter(client, NanoKvmApplicationVersion(2, 4, 0, "rc.1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPicoClaw.enter(client, NanoKvmApplicationVersion(2, 3, 9))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `explicit status uses cookie and returns bounded lock-aware model`() = runBlocking {
        server.enqueue(success(runtimeStatus(currentSession = SESSION_ID)))
        val api = pico()

        val status = api.runtimeStatus()

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/picoclaw/runtime/status", request.path)
        assertEquals("nano-kvm-token=pico-cookie", request.getHeader("Cookie"))
        assertTrue(status.ready)
        assertEquals(NanoKvmPicoClawAgentProfile.KVM, status.agentProfile)
        assertEquals(NanoKvmPicoClawRuntimePhase.Ready, status.phase)
        assertTrue(status.manualHidLocked)
        assertEquals(SESSION_ID, status.currentSession?.value)
    }

    @Test
    fun `runtime and profile mutations use exact official routes and empty or typed bodies`() =
        runBlocking {
            listOf(true, false, true, false).forEach {
                server.enqueue(success(runtimeMutation(started = it)))
            }
            server.enqueue(success(agentProfileResponse("default")))
            server.enqueue(success(installation(installed = true)))
            server.enqueue(success(installation(installed = false)))
            val api = pico()

            api.startRuntime()
            api.stopRuntime()
            // Starting/stopping again demonstrates that each public invocation dispatches once.
            api.startRuntime()
            api.stopRuntime()
            val profile = api.setAgentProfile(NanoKvmPicoClawAgentProfile.DEFAULT)
            api.installRuntime()
            api.uninstallRuntime(
                NanoKvmPicoClawUninstallApproval
                    .afterUserConfirmedRuntimeAndConfigurationErasure(),
            )

            val expected = listOf(
                "POST" to "/api/picoclaw/runtime/start",
                "POST" to "/api/picoclaw/runtime/stop",
                "POST" to "/api/picoclaw/runtime/start",
                "POST" to "/api/picoclaw/runtime/stop",
                "POST" to "/api/picoclaw/agent/profile",
                "POST" to "/api/picoclaw/runtime/install",
                "POST" to "/api/picoclaw/runtime/uninstall",
            )
            expected.forEachIndexed { index, (method, path) ->
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals(method, request.method)
                assertEquals(path, request.path)
                assertEquals("nano-kvm-token=pico-cookie", request.getHeader("Cookie"))
                if (index != 4) assertEquals(0L, request.bodySize)
                if (index == 4) assertEquals("{\"profile\":\"default\"}", request.body.readUtf8())
            }
            assertEquals(NanoKvmPicoClawAgentProfile.DEFAULT, profile.profile)
        }

    @Test
    fun `model key is redacted single-use cleared after serialization and body is exact`() =
        runBlocking {
            server.enqueue(success(modelConfigResponse()))
            val key = "provider-secret".toCharArray()
            val configuration = NanoKvmPicoClawModelConfiguration(
                model = "openai/gpt-5-mini",
                apiBase = NanoKvmPicoClawApiBase.parse("https://api.example.test/v1"),
                apiKey = key,
            )
            assertFalse(configuration.toString().contains("provider-secret"))

            val result = pico().updateModel(configuration)

            assertTrue(key.all { it == '\u0000' })
            assertEquals("gpt-5-mini", result.modelName)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/picoclaw/model/config", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("openai/gpt-5-mini", body.getValue("model").jsonPrimitive.content)
            assertEquals("https://api.example.test/v1", body.getValue("api_base").jsonPrimitive.content)
            assertEquals("provider-secret", body.getValue("api_key").jsonPrimitive.content)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { pico().updateModel(configuration) }
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `history list detail and delete require latest opaque identity and bound approval`() =
        runBlocking {
            server.enqueue(success(historyList()))
            server.enqueue(success(historyDetail()))
            server.enqueue(success("{\"id\":\"$SESSION_ID\",\"deleted\":true}"))
            val api = pico()

            val catalog = api.histories(offset = 4, limit = 20)
            val summary = catalog.entries.single()
            val detail = api.history(catalog, summary.session)
            val approval = NanoKvmPicoClawHistoryDeletionApproval
                .afterUserConfirmedPermanentDeletion(catalog, summary.session)
            api.deleteHistory(catalog, summary.session, approval)

            assertEquals("first task", summary.title)
            assertEquals(NanoKvmPicoClawHistoryRole.USER, detail.messages.first().role)
            assertEquals(NanoKvmPicoClawHistoryRole.ASSISTANT, detail.messages.last().role)
            assertFalse(summary.session.toString().contains(SESSION_ID))

            val listRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/api/picoclaw/sessions?offset=4&limit=20", listRequest.path)
            val detailRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", detailRequest.method)
            assertEquals("/api/picoclaw/sessions/$SESSION_ID", detailRequest.path)
            val deleteRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("DELETE", deleteRequest.method)
            assertEquals("/api/picoclaw/sessions/$SESSION_ID", deleteRequest.path)
            assertEquals(0L, deleteRequest.bodySize)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { api.history(catalog, summary.session) }
            }
            Unit
        }

    @Test
    fun `opaque sk history IDs are accepted but arbitrary path IDs and duplicate pages fail`() {
        runBlocking {
            server.enqueue(
                success(
                    historyList(id = "sk_v1_AZaz09_-safe", title = "opaque"),
                ),
            )
            val opaque = pico().histories()
            assertEquals(1, opaque.entries.size)

            server.enqueue(success(historyList(id = "../internal/actions")))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { pico().histories() }
            }

            val item = historyList().removePrefix("[").removeSuffix("]")
            server.enqueue(success("[$item,$item]"))
            assertThrows(InvalidApiResponseException::class.java) {
                runBlocking { pico().histories() }
            }
        }
    }

    @Test
    fun `runtime-session release uses UUID header and reports another lock owner`() = runBlocking {
        val other = "123e4567-e89b-42d3-a456-426614174001"
        server.enqueue(
            success("{\"released\":true,\"current_session\":\"$other\"}"),
        )
        val session = NanoKvmPicoClawRuntimeSessionId.parse(SESSION_ID)

        val result = pico().releaseRuntimeSession(session)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("DELETE", request.method)
        assertEquals("/api/picoclaw/runtime/session", request.path)
        assertEquals(SESSION_ID, request.getHeader("X-PicoClaw-Session-ID"))
        assertEquals(other, result.currentSession?.value)
    }

    @Test
    fun `PicoClaw string errors are bounded and mutation disconnect is never retried`() {
        server.enqueue(
            MockResponse().setBody(
                "{\"code\":\"RUNTIME_UNAVAILABLE\",\"message\":\"not installed\"}",
            ),
        )
        val error = assertThrows(NanoKvmPicoClawApiException::class.java) {
            runBlocking { pico().runtimeStatus() }
        }
        assertEquals("RUNTIME_UNAVAILABLE", error.errorCode)
        assertEquals("not installed", error.serverMessage)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(IOException::class.java) {
            runBlocking { pico().startRuntime() }
        }
        Thread.sleep(150)
        assertEquals(2, server.requestCount)
        assertFalse(client.transport.retryOnConnectionFailure)
    }

    @Test
    fun `input and response bounds reject locally or as invalid server data`() {
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPicoClawRuntimeSessionId.parse("not-a-uuid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPicoClawApiBase.parse("https://user:pass@example.test/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPicoClawApiBase.parse("file:///tmp/provider")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { pico().histories(limit = 101) }
        }
        assertEquals(0, server.requestCount)

        server.enqueue(
            success(runtimeStatus(status = "x".repeat(129), currentSession = null)),
        )
        assertThrows(InvalidApiResponseException::class.java) {
            runBlocking { pico().runtimeStatus() }
        }
    }

    private fun pico(): NanoKvmPicoClaw =
        NanoKvmPicoClaw.enter(client, NanoKvmApplicationVersion(2, 4, 3))

    private fun success(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"code\":0,\"msg\":\"success\",\"data\":$data}")

    private fun runtimeStatus(
        status: String = "ready",
        currentSession: String? = null,
    ): String {
        val currentSessionJson = currentSession
            ?.let { ",\"current_session\":\"$it\"" }
            .orEmpty()
        return """
            {
              "ready":true,
              "installed":true,
              "installing":false,
              "install_progress":100,
              "install_stage":"installed",
              "install_path":"/usr/bin/picoclaw",
              "agent_profile":"kvm",
              "model_configured":true,
              "model_name":"gpt-5-mini",
              "status":"$status",
              "checked_at":"2026-07-18T12:34:56Z"$currentSessionJson
            }
        """.trimIndent()
    }

    private fun runtimeMutation(started: Boolean): String = """
        {
          "started":$started,
          "command":"/etc/init.d/S96picoclaw ${if (started) "start" else "stop"}",
          "output":"ok",
          "status":${runtimeStatus(currentSession = null)}
        }
    """.trimIndent()

    private fun installation(installed: Boolean): String = """
        {
          "installed":$installed,
          "binary":"/usr/bin/picoclaw",
          "download":"https://cdn.sipeed.com/picoclaw.tar.gz",
          "output":"ok",
          "status":${runtimeStatus(currentSession = null)}
        }
    """.trimIndent()

    private fun agentProfileResponse(profile: String): String = """
        {"profile":"$profile","status":${runtimeStatus(currentSession = null)}}
    """.trimIndent()

    private fun modelConfigResponse(): String = """
        {"model_name":"gpt-5-mini","status":${runtimeStatus(currentSession = null)}}
    """.trimIndent()

    private fun historyList(
        id: String = SESSION_ID,
        title: String = "first task",
    ): String = """
        [{
          "id":"$id",
          "title":"$title",
          "preview":"inspect the host",
          "message_count":2,
          "created":"2026-07-18T10:00:00Z",
          "updated":"2026-07-18T10:01:00Z"
        }]
    """.trimIndent()

    private fun historyDetail(): String = """
        {
          "id":"$SESSION_ID",
          "messages":[
            {"role":"user","content":"inspect the host"},
            {"role":"assistant","content":"done"}
          ],
          "summary":"first task",
          "created":"2026-07-18T10:00:00Z",
          "updated":"2026-07-18T10:01:00Z"
        }
    """.trimIndent()

    private companion object {
        const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}

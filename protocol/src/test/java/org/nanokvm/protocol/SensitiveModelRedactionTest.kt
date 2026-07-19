package org.nanokvm.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SensitiveModelRedactionTest {
    @Test
    fun `session token clipboard and provider key strings are redacted`() {
        assertRedacted(SessionToken("bearer-secret"), "bearer-secret")

        val paste = PasteRequest(content = "clipboard-secret", langue = "en")
        assertRedacted(paste, "clipboard-secret")
        assertTrue(paste.toString().contains("langue=en"))

        val requestClass = Class.forName("org.nanokvm.protocol.PicoClawModelConfigRequest")
        val constructor = requestClass.declaredConstructors.single { it.parameterCount == 3 }
        constructor.isAccessible = true
        val request = constructor.newInstance(
            "provider/model",
            "https://provider.example/v1",
            "provider-key-secret",
        )
        assertRedacted(request, "provider-key-secret")
    }

    @Test
    fun `script result and failure strings retain classification without output`() {
        val result = NanoKvmScriptRunResult(
            mode = NanoKvmScriptRunMode.FOREGROUND,
            output = "script-output-secret",
        )
        assertRedacted(result, "script-output-secret")
        assertTrue(result.toString().contains("mode=FOREGROUND"))

        listOf(
            NanoKvmScriptFailure.Rejected(-1, "rejected-secret"),
            NanoKvmScriptFailure.OperationFailed(-2, "operation-secret"),
            NanoKvmScriptFailure.Other(73, "future-secret"),
        ).forEach { failure ->
            assertRedacted(failure, failure.serverMessage)
            assertTrue(failure.toString().contains("code=${failure.code}"))
        }
    }

    @Test
    fun `PicoClaw inbound messages and events redact content and transport identity`() {
        val scope = NanoKvmPicoClawGatewayScope(
            authority = "private-kvm.example",
            generation = 7,
            session = NanoKvmPicoClawRuntimeSessionId.parse(
                "123e4567-e89b-12d3-a456-426614174000",
            ),
        )
        val assistant = NanoKvmPicoClawInboundMessage.AssistantMessage(
            kind = NanoKvmPicoClawAssistantMessageKind.CREATED,
            id = "assistant-id-secret",
            text = "assistant-text-secret",
        )
        assertRedacted(assistant, "assistant-id-secret", "assistant-text-secret")

        val observation = NanoKvmPicoClawInboundMessage.Observation(
            id = "observation-id-secret",
            text = "observation-text-secret",
            imageBase64 = "image-base64-secret",
        )
        assertRedacted(
            observation,
            "observation-id-secret",
            "observation-text-secret",
            "image-base64-secret",
        )

        val toolAction = NanoKvmPicoClawInboundMessage.ToolAction(
            id = "tool-id-secret",
            action = "type-password-secret",
            x = 0.2,
            y = 0.8,
        )
        assertRedacted(toolAction, "tool-id-secret", "type-password-secret", "0.2", "0.8")

        val error = NanoKvmPicoClawInboundMessage.Error(
            code = "upstream_error",
            message = "upstream-error-secret",
        )
        assertRedacted(error, "upstream-error-secret")
        assertTrue(error.toString().contains("code=upstream_error"))

        val messageEvent = NanoKvmPicoClawGatewayEvent.Message(scope, assistant)
        assertRedacted(
            messageEvent,
            "private-kvm.example",
            "123e4567-e89b-12d3-a456-426614174000",
            "assistant-text-secret",
        )

        val violation = NanoKvmPicoClawGatewayEvent.ProtocolViolation(
            scope,
            "protocol-payload-secret",
        )
        assertRedacted(violation, "private-kvm.example", "protocol-payload-secret")

        val close = NanoKvmPicoClawClose(
            code = 1008,
            reason = "close-reason-secret",
            cause = NanoKvmPicoClawCloseCause.AuthenticationFailed,
        )
        assertRedacted(close, "close-reason-secret")
        assertRedacted(NanoKvmPicoClawGatewayEvent.Closing(scope, close), "close-reason-secret")
        assertRedacted(NanoKvmPicoClawGatewayEvent.Closed(scope, close), "close-reason-secret")

        val failure = NanoKvmPicoClawGatewayEvent.Failure(
            scope = scope,
            cause = IllegalStateException("throwable-secret"),
            httpStatus = 503,
        )
        assertRedacted(failure, "private-kvm.example", "throwable-secret")
        assertTrue(failure.toString().contains("httpStatus=503"))
    }

    @Test
    fun `PicoClaw history strings redact titles previews messages and summaries`() {
        val session = NanoKvmPicoClawHistorySession("history-session-secret")
        val createdAt = Instant.parse("2026-01-02T03:04:05Z")
        val updatedAt = Instant.parse("2026-01-02T04:05:06Z")
        val summary = NanoKvmPicoClawHistorySummary(
            session = session,
            title = "history-title-secret",
            preview = "history-preview-secret",
            messageCount = 1,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        assertRedacted(
            summary,
            "history-session-secret",
            "history-title-secret",
            "history-preview-secret",
        )
        assertTrue(summary.toString().contains("messageCount=1"))

        val message = NanoKvmPicoClawHistoryMessage(
            role = NanoKvmPicoClawHistoryRole.USER,
            content = "history-message-secret",
        )
        assertRedacted(message, "history-message-secret")
        assertTrue(message.toString().contains("role=USER"))

        val detail = NanoKvmPicoClawHistoryDetail(
            session = session,
            messages = listOf(message),
            summary = "history-detail-summary-secret",
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        assertRedacted(
            detail,
            "history-session-secret",
            "history-message-secret",
            "history-detail-summary-secret",
        )
        assertTrue(detail.toString().contains("messageCount=1"))
    }

    private fun assertRedacted(value: Any, vararg sensitiveValues: String) {
        val rendered = value.toString()
        assertTrue("Expected a redaction marker in: $rendered", rendered.contains("<redacted>"))
        sensitiveValues.forEach { sensitive ->
            assertFalse("Sensitive value was present in: $rendered", rendered.contains(sensitive))
        }
    }
}

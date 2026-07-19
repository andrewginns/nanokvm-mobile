package org.nanokvm.protocol

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.util.UUID

data class NanoKvmPicoClawGatewayScope(
    val authority: String,
    val generation: Long,
    val session: NanoKvmPicoClawRuntimeSessionId,
)

sealed interface NanoKvmPicoClawGatewayState {
    val scope: NanoKvmPicoClawGatewayScope

    data class New(override val scope: NanoKvmPicoClawGatewayScope) :
        NanoKvmPicoClawGatewayState
    data class Connecting(override val scope: NanoKvmPicoClawGatewayScope) :
        NanoKvmPicoClawGatewayState
    data class Open(override val scope: NanoKvmPicoClawGatewayScope) :
        NanoKvmPicoClawGatewayState
    data class Closing(override val scope: NanoKvmPicoClawGatewayScope) :
        NanoKvmPicoClawGatewayState
    data class Closed(
        override val scope: NanoKvmPicoClawGatewayScope,
        val close: NanoKvmPicoClawClose,
    ) : NanoKvmPicoClawGatewayState
    data class Failed(
        override val scope: NanoKvmPicoClawGatewayScope,
        val cause: Throwable,
        val httpStatus: Int?,
    ) : NanoKvmPicoClawGatewayState
}

/** Manual HID is blocked for the whole server-side gateway lock, not just while a run is busy. */
sealed interface NanoKvmPicoClawManualHidLockState {
    val scope: NanoKvmPicoClawGatewayScope

    data class Released(override val scope: NanoKvmPicoClawGatewayScope) :
        NanoKvmPicoClawManualHidLockState
    data class Acquiring(override val scope: NanoKvmPicoClawGatewayScope) :
        NanoKvmPicoClawManualHidLockState
    data class Held(override val scope: NanoKvmPicoClawGatewayScope) :
        NanoKvmPicoClawManualHidLockState
    data class HeldByOther(
        override val scope: NanoKvmPicoClawGatewayScope,
        val owner: NanoKvmPicoClawRuntimeSessionId,
    ) : NanoKvmPicoClawManualHidLockState
    data class Releasing(override val scope: NanoKvmPicoClawGatewayScope) :
        NanoKvmPicoClawManualHidLockState

    /** A failed/ambiguous DELETE must not be represented as proof that manual input is restored. */
    data class ReleaseUncertain(override val scope: NanoKvmPicoClawGatewayScope) :
        NanoKvmPicoClawManualHidLockState
}

enum class NanoKvmPicoClawAssistantMessageKind {
    CREATED,
    UPDATED,
}

sealed interface NanoKvmPicoClawInboundMessage {
    data object TypingStarted : NanoKvmPicoClawInboundMessage
    data object TypingStopped : NanoKvmPicoClawInboundMessage
    data object Pong : NanoKvmPicoClawInboundMessage

    data class AssistantMessage(
        val kind: NanoKvmPicoClawAssistantMessageKind,
        val id: String,
        val text: String,
    ) : NanoKvmPicoClawInboundMessage {
        override fun toString(): String =
            "NanoKvmPicoClawInboundMessage.AssistantMessage(" +
                "kind=$kind, id=<redacted>, text=<redacted>)"
    }

    data class Observation(
        val id: String,
        val text: String?,
        val imageBase64: String,
    ) : NanoKvmPicoClawInboundMessage {
        override fun toString(): String =
            "NanoKvmPicoClawInboundMessage.Observation(" +
                "id=<redacted>, text=<redacted>, imageBase64=<redacted>)"
    }

    data class ToolAction(
        val id: String,
        val action: String,
        val x: Double?,
        val y: Double?,
    ) : NanoKvmPicoClawInboundMessage {
        override fun toString(): String =
            "NanoKvmPicoClawInboundMessage.ToolAction(" +
                "id=<redacted>, action=<redacted>, coordinates=<redacted>)"
    }

    data class Error(
        val code: String,
        val message: String,
    ) : NanoKvmPicoClawInboundMessage {
        override fun toString(): String =
            "NanoKvmPicoClawInboundMessage.Error(code=$code, message=<redacted>)"
    }

    /** A bounded future message type; raw JSON is deliberately not retained. */
    data class Other(val type: String) : NanoKvmPicoClawInboundMessage
}

sealed interface NanoKvmPicoClawCloseCause {
    data object Normal : NanoKvmPicoClawCloseCause
    data object GoingAway : NanoKvmPicoClawCloseCause
    data object ManualHidLockHeld : NanoKvmPicoClawCloseCause
    data object RuntimeUnavailable : NanoKvmPicoClawCloseCause
    data object AuthenticationFailed : NanoKvmPicoClawCloseCause
    data object SessionTakenOver : NanoKvmPicoClawCloseCause
    data object UpstreamClosed : NanoKvmPicoClawCloseCause
    data object UnsupportedData : NanoKvmPicoClawCloseCause
    data object InvalidPayload : NanoKvmPicoClawCloseCause
    data object MessageTooLarge : NanoKvmPicoClawCloseCause
    data class Other(val code: Int) : NanoKvmPicoClawCloseCause
}

data class NanoKvmPicoClawClose(
    val code: Int,
    val reason: String,
    val cause: NanoKvmPicoClawCloseCause,
) {
    override fun toString(): String =
        "NanoKvmPicoClawClose(code=$code, reason=<redacted>, cause=$cause)"
}

sealed interface NanoKvmPicoClawGatewayEvent {
    val scope: NanoKvmPicoClawGatewayScope

    data class Message(
        override val scope: NanoKvmPicoClawGatewayScope,
        val message: NanoKvmPicoClawInboundMessage,
    ) : NanoKvmPicoClawGatewayEvent {
        override fun toString(): String =
            "NanoKvmPicoClawGatewayEvent.Message(scope=<redacted>, message=$message)"
    }

    data class ProtocolViolation(
        override val scope: NanoKvmPicoClawGatewayScope,
        val reason: String,
    ) : NanoKvmPicoClawGatewayEvent {
        override fun toString(): String =
            "NanoKvmPicoClawGatewayEvent.ProtocolViolation(" +
                "scope=<redacted>, reason=<redacted>)"
    }

    data class Closing(
        override val scope: NanoKvmPicoClawGatewayScope,
        val close: NanoKvmPicoClawClose,
    ) : NanoKvmPicoClawGatewayEvent {
        override fun toString(): String =
            "NanoKvmPicoClawGatewayEvent.Closing(scope=<redacted>, close=$close)"
    }

    data class Closed(
        override val scope: NanoKvmPicoClawGatewayScope,
        val close: NanoKvmPicoClawClose,
    ) : NanoKvmPicoClawGatewayEvent {
        override fun toString(): String =
            "NanoKvmPicoClawGatewayEvent.Closed(scope=<redacted>, close=$close)"
    }

    data class Failure(
        override val scope: NanoKvmPicoClawGatewayScope,
        val cause: Throwable,
        val httpStatus: Int?,
    ) : NanoKvmPicoClawGatewayEvent {
        override fun toString(): String =
            "NanoKvmPicoClawGatewayEvent.Failure(" +
                "scope=<redacted>, cause=<redacted>, httpStatus=$httpStatus)"
    }
}

data class NanoKvmPicoClawMessageOptions(
    val maxSteps: Int = 20,
    val maxRuntimeMillis: Int = 120_000,
) {
    init {
        require(maxSteps in 1..50) { "PicoClaw max steps must be in 1..50" }
        require(maxRuntimeMillis in 1_000..MAX_PICOCLAW_RUNTIME_MILLIS) {
            "PicoClaw runtime must be between 1 second and 30 minutes"
        }
    }
}

data class NanoKvmPicoClawMessageReceipt(
    val id: String,
    val session: NanoKvmPicoClawRuntimeSessionId,
)

/**
 * One generation-scoped PicoClaw gateway.
 *
 * It can be connected once. Messages sent before open or after close are rejected, never queued;
 * failures never reconnect and never replay a message or cancel frame. Closing the WebSocket asks
 * NanoKVM to release its global manual-HID lock. [closeAndRelease] additionally dispatches the
 * official one-shot release DELETE and surfaces an ambiguous release instead of retrying it.
 */
class NanoKvmPicoClawGateway internal constructor(
    endpoint: NanoKvmEndpoint,
    private val transport: OkHttpClient,
    private val requestFactory: (String) -> Request,
    session: NanoKvmPicoClawRuntimeSessionId,
    generation: Long,
    private val releaseSession: suspend (NanoKvmPicoClawRuntimeSessionId) ->
        NanoKvmPicoClawSessionRelease,
) : Closeable {
    private val lock = Any()
    val scope = NanoKvmPicoClawGatewayScope(endpoint.authorityKey, generation, session)

    private val mutableState = MutableStateFlow<NanoKvmPicoClawGatewayState>(
        NanoKvmPicoClawGatewayState.New(scope),
    )
    private val mutableManualHidLock = MutableStateFlow<NanoKvmPicoClawManualHidLockState>(
        NanoKvmPicoClawManualHidLockState.Released(scope),
    )
    private val mutableEvents = MutableSharedFlow<NanoKvmPicoClawGatewayEvent>(
        replay = 0,
        extraBufferCapacity = MAX_RETAINED_PICOCLAW_EVENTS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val gatewayUrl = endpoint.apiUrl(PICOCLAW_GATEWAY_PATH).newBuilder()
        .addQueryParameter("session_id", session.value)
        .build()

    private var socket: WebSocket? = null
    private var connectionAttempted = false
    private var disposed = false
    private var releaseDispatched = false

    val state: StateFlow<NanoKvmPicoClawGatewayState> = mutableState.asStateFlow()
    val manualHidLock: StateFlow<NanoKvmPicoClawManualHidLockState> =
        mutableManualHidLock.asStateFlow()
    val events: SharedFlow<NanoKvmPicoClawGatewayEvent> = mutableEvents.asSharedFlow()

    /** Starts the sole handshake for this instance. Create a new generation to try again. */
    fun connect(): Boolean = synchronized(lock) {
        check(!disposed) { "This PicoClaw gateway has been disposed" }
        if (connectionAttempted) return@synchronized false
        connectionAttempted = true
        mutableState.value = NanoKvmPicoClawGatewayState.Connecting(scope)
        mutableManualHidLock.value = NanoKvmPicoClawManualHidLockState.Acquiring(scope)

        val request = requestFactory(PICOCLAW_GATEWAY_PATH).newBuilder()
            .url(gatewayUrl)
            .build()
        socket = transport.newWebSocket(request, Listener())
        true
    }

    /** Sends exactly one typed `message.send` frame while open. Nothing is queued. */
    fun sendMessage(
        content: String,
        options: NanoKvmPicoClawMessageOptions = NanoKvmPicoClawMessageOptions(),
    ): NanoKvmPicoClawMessageReceipt? {
        val normalized = content.trim()
        require(normalized.isNotEmpty()) { "PicoClaw message must not be blank" }
        require(normalized.picoGatewayHasUtf8AtMost(MAX_PICOCLAW_CONTENT_BYTES)) {
            "PicoClaw message exceeds $MAX_PICOCLAW_CONTENT_BYTES UTF-8 bytes"
        }
        require(normalized.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            "PicoClaw message contains unsupported control characters"
        }
        val id = UUID.randomUUID().toString()
        val body = JSON.encodeToString(
            PicoClawSendMessage(
                id = id,
                sessionId = scope.session.value,
                type = "message.send",
                payload = PicoClawSendPayload(
                    content = normalized,
                    maxSteps = options.maxSteps,
                    maxRuntimeMillis = options.maxRuntimeMillis,
                ),
            ),
        )
        check(body.picoGatewayHasUtf8AtMost(MAX_PICOCLAW_GATEWAY_MESSAGE_BYTES)) {
            "Serialized PicoClaw message exceeds the 1 MiB gateway limit"
        }
        val sent = synchronized(lock) {
            val current = socket ?: return@synchronized false
            if (mutableState.value !is NanoKvmPicoClawGatewayState.Open) {
                return@synchronized false
            }
            current.send(body)
        }
        return if (sent) NanoKvmPicoClawMessageReceipt(id, scope.session) else null
    }

    /** Sends exactly one `message.cancel` frame while open. It does not release manual HID. */
    fun cancelRun(): Boolean {
        val body = JSON.encodeToString(
            PicoClawCancelMessage(
                type = "message.cancel",
                sessionId = scope.session.value,
                payload = JsonObject(emptyMap()),
            ),
        )
        return synchronized(lock) {
            val current = socket ?: return@synchronized false
            if (mutableState.value !is NanoKvmPicoClawGatewayState.Open) {
                return@synchronized false
            }
            current.send(body)
        }
    }

    /** Graceful socket close; NanoKVM releases the lock when its relay observes disconnect. */
    fun disconnect(reason: String = "PicoClaw gateway closed") {
        require(reason.picoGatewayHasUtf8AtMost(MAX_CLOSE_REASON_BYTES)) {
            "PicoClaw close reason is too long"
        }
        synchronized(lock) {
            val current = socket ?: return
            mutableState.value = NanoKvmPicoClawGatewayState.Closing(scope)
            mutableManualHidLock.value = NanoKvmPicoClawManualHidLockState.Releasing(scope)
            if (!current.close(NORMAL_CLOSE_CODE, reason)) {
                current.cancel()
                socket = null
                mutableManualHidLock.value =
                    NanoKvmPicoClawManualHidLockState.ReleaseUncertain(scope)
            }
        }
    }

    /**
     * Closes locally and dispatches the public release route once with its required session header.
     * An ambiguous failure is retained as [NanoKvmPicoClawManualHidLockState.ReleaseUncertain].
     */
    suspend fun closeAndRelease(): NanoKvmPicoClawSessionRelease {
        synchronized(lock) {
            check(!releaseDispatched) { "PicoClaw runtime-session release was already dispatched" }
            releaseDispatched = true
        }
        disconnect("PicoClaw runtime session release")
        return try {
            releaseSession(scope.session).also { release ->
                synchronized(lock) {
                    if (release.released && release.currentSession == null) {
                        mutableManualHidLock.value =
                            NanoKvmPicoClawManualHidLockState.Released(scope)
                    } else if (release.currentSession != null &&
                        release.currentSession != scope.session
                    ) {
                        mutableManualHidLock.value =
                            NanoKvmPicoClawManualHidLockState.HeldByOther(
                                scope,
                                release.currentSession,
                            )
                    } else {
                        mutableManualHidLock.value =
                            NanoKvmPicoClawManualHidLockState.ReleaseUncertain(scope)
                    }
                }
            }
        } catch (error: Exception) {
            synchronized(lock) {
                mutableManualHidLock.value =
                    NanoKvmPicoClawManualHidLockState.ReleaseUncertain(scope)
            }
            throw error
        }
    }

    override fun close() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
        }
        disconnect("PicoClaw gateway disposed")
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (socket !== webSocket || disposed) {
                    webSocket.close(NORMAL_CLOSE_CODE, "stale PicoClaw gateway")
                    return
                }
                mutableState.value = NanoKvmPicoClawGatewayState.Open(scope)
                mutableManualHidLock.value = NanoKvmPicoClawManualHidLockState.Held(scope)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            if (!text.picoGatewayHasUtf8AtMost(MAX_PICOCLAW_GATEWAY_MESSAGE_BYTES)) {
                protocolViolation(webSocket, MESSAGE_TOO_LARGE_CLOSE_CODE, "gateway message too large")
                return
            }
            val parsed = try {
                parsePicoClawInbound(text)
            } catch (_: SerializationException) {
                protocolViolation(webSocket, INVALID_PAYLOAD_CLOSE_CODE, "invalid gateway JSON")
                return
            } catch (_: IllegalArgumentException) {
                protocolViolation(webSocket, INVALID_PAYLOAD_CLOSE_CODE, "invalid gateway payload")
                return
            }
            mutableEvents.tryEmit(NanoKvmPicoClawGatewayEvent.Message(scope, parsed))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(webSocket)) return
            protocolViolation(webSocket, UNSUPPORTED_DATA_CLOSE_CODE, "binary gateway message")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (socket !== webSocket) return
                val close = picoClose(code, reason)
                mutableState.value = NanoKvmPicoClawGatewayState.Closing(scope)
                mutableManualHidLock.value = NanoKvmPicoClawManualHidLockState.Releasing(scope)
                mutableEvents.tryEmit(NanoKvmPicoClawGatewayEvent.Closing(scope, close))
                webSocket.close(code, reason.take(MAX_CLOSE_REASON_CHARS))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (socket !== webSocket) return
                val close = picoClose(code, reason)
                socket = null
                mutableState.value = NanoKvmPicoClawGatewayState.Closed(scope, close)
                mutableManualHidLock.value = NanoKvmPicoClawManualHidLockState.Released(scope)
                mutableEvents.tryEmit(NanoKvmPicoClawGatewayEvent.Closed(scope, close))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                if (socket !== webSocket) {
                    response?.close()
                    return
                }
                val lockWasPossiblyHeld =
                    mutableManualHidLock.value !is NanoKvmPicoClawManualHidLockState.Acquiring &&
                        mutableManualHidLock.value !is NanoKvmPicoClawManualHidLockState.Released
                socket = null
                mutableState.value = NanoKvmPicoClawGatewayState.Failed(scope, t, response?.code)
                mutableManualHidLock.value = if (lockWasPossiblyHeld) {
                    NanoKvmPicoClawManualHidLockState.ReleaseUncertain(scope)
                } else {
                    NanoKvmPicoClawManualHidLockState.Released(scope)
                }
                mutableEvents.tryEmit(
                    NanoKvmPicoClawGatewayEvent.Failure(scope, t, response?.code),
                )
                response?.close()
            }
        }
    }

    private fun protocolViolation(webSocket: WebSocket, code: Int, reason: String) {
        synchronized(lock) {
            if (socket !== webSocket) return
            mutableEvents.tryEmit(NanoKvmPicoClawGatewayEvent.ProtocolViolation(scope, reason))
            mutableState.value = NanoKvmPicoClawGatewayState.Closing(scope)
            mutableManualHidLock.value = NanoKvmPicoClawManualHidLockState.Releasing(scope)
            if (!webSocket.close(code, reason)) {
                webSocket.cancel()
                socket = null
            }
        }
    }

    private fun isCurrent(webSocket: WebSocket): Boolean = synchronized(lock) {
        socket === webSocket
    }
}

@Serializable
private data class PicoClawSendMessage(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val type: String,
    val payload: PicoClawSendPayload,
)

@Serializable
private data class PicoClawSendPayload(
    val content: String,
    @SerialName("max_steps") val maxSteps: Int,
    @SerialName("max_runtime_ms") val maxRuntimeMillis: Int,
)

@Serializable
private data class PicoClawCancelMessage(
    val type: String,
    @SerialName("session_id") val sessionId: String,
    val payload: JsonObject,
)

private fun parsePicoClawInbound(value: String): NanoKvmPicoClawInboundMessage {
    val root = JSON.parseToJsonElement(value) as? JsonObject
        ?: throw IllegalArgumentException("Gateway message must be an object")
    val type = root.string("type").picoInboundBounded("message type", MAX_INBOUND_TYPE_BYTES, false)
    return when (type) {
        "typing.start" -> NanoKvmPicoClawInboundMessage.TypingStarted
        "typing.stop" -> NanoKvmPicoClawInboundMessage.TypingStopped
        "pong" -> NanoKvmPicoClawInboundMessage.Pong
        "error" -> NanoKvmPicoClawInboundMessage.Error(
            code = root.stringOrNull("code").orEmpty()
                .picoInboundBounded("error code", MAX_INBOUND_ERROR_CODE_BYTES),
            message = root.stringOrNull("message").orEmpty()
                .picoInboundBounded("error message", MAX_INBOUND_TEXT_BYTES),
        )
        "message.create", "message.update" -> NanoKvmPicoClawInboundMessage.AssistantMessage(
            kind = if (type == "message.create") {
                NanoKvmPicoClawAssistantMessageKind.CREATED
            } else {
                NanoKvmPicoClawAssistantMessageKind.UPDATED
            },
            id = root.safeId(),
            text = root.extractText().picoInboundBounded(
                "assistant message",
                MAX_INBOUND_TEXT_BYTES,
            ),
        )
        else -> {
            val image = root.extractImageBase64()
            if (image != null) {
                NanoKvmPicoClawInboundMessage.Observation(
                    id = root.safeId(),
                    text = root.extractText().takeIf(String::isNotEmpty)
                        ?.picoInboundBounded("observation text", MAX_INBOUND_TEXT_BYTES),
                    imageBase64 = image.picoInboundBounded(
                        "observation image",
                        MAX_PICOCLAW_GATEWAY_MESSAGE_BYTES,
                        allowEmpty = false,
                    ),
                )
            } else {
                val action = root.extractAction()
                if (action != null) {
                    NanoKvmPicoClawInboundMessage.ToolAction(
                        id = root.safeId(),
                        action = action.first.picoInboundBounded(
                            "tool action",
                            MAX_INBOUND_ACTION_BYTES,
                            allowEmpty = false,
                        ),
                        x = action.second,
                        y = action.third,
                    )
                } else {
                    NanoKvmPicoClawInboundMessage.Other(type)
                }
            }
        }
    }
}

private fun JsonObject.extractText(): String {
    val payload = this["payload"] as? JsonObject
    val content = payload?.get("content") ?: this["content"] ?: payload?.get("text") ?: this["text"]
    return when (content) {
        is JsonPrimitive -> content.contentOrNull.normalizedInboundText()
        is JsonArray -> content.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull.normalizedInboundText().takeIf(String::isNotEmpty)
                is JsonObject -> item.stringOrNull("text").normalizedInboundText()
                    .takeIf(String::isNotEmpty)
                else -> null
            }
        }.joinToString("\n").trim()
        else -> ""
    }
}

private fun JsonObject.extractImageBase64(): String? {
    val payload = this["payload"] as? JsonObject
    val data = (payload?.get("data") ?: this["data"]) as? JsonObject
    return data?.stringOrNull("image_base64")
        ?: payload?.stringOrNull("image_base64")
        ?: stringOrNull("image_base64")
}

private fun JsonObject.extractAction(): Triple<String, Double?, Double?>? {
    val payload = this["payload"] as? JsonObject ?: JsonObject(emptyMap())
    val action = payload.stringOrNull("action")
        ?: stringOrNull("action")
        ?: payload.stringOrNull("tool_name")
        ?: stringOrNull("tool_name")
        ?: return null
    val x = (payload["x"] as? JsonPrimitive)?.doubleOrNull
    val y = (payload["y"] as? JsonPrimitive)?.doubleOrNull
    require(x == null || x.isFinite()) { "Tool action x is not finite" }
    require(y == null || y.isFinite()) { "Tool action y is not finite" }
    return Triple(action.normalizedInboundText(), x, y)
}

private fun JsonObject.safeId(): String = stringOrNull("id")
    ?.picoInboundBounded("message ID", MAX_INBOUND_ID_BYTES, allowEmpty = false)
    ?: UUID.randomUUID().toString()

private fun JsonObject.string(name: String): String =
    stringOrNull(name) ?: throw IllegalArgumentException("Missing $name")

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun String?.normalizedInboundText(): String {
    val trimmed = this?.trim().orEmpty()
    return when (trimmed.lowercase()) {
        "", "null", "undefined" -> ""
        else -> trimmed
    }
}

private fun String.picoInboundBounded(
    label: String,
    maxBytes: Int,
    allowEmpty: Boolean = true,
): String {
    require((allowEmpty || isNotEmpty()) && picoGatewayHasUtf8AtMost(maxBytes)) {
        "$label is blank or too long"
    }
    require(none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        "$label contains unsupported control characters"
    }
    return this
}

private fun picoClose(code: Int, reason: String): NanoKvmPicoClawClose = NanoKvmPicoClawClose(
    code = code,
    reason = reason.take(MAX_CLOSE_REASON_CHARS),
    cause = when (code) {
        1000 -> NanoKvmPicoClawCloseCause.Normal
        1001 -> NanoKvmPicoClawCloseCause.GoingAway
        1003 -> NanoKvmPicoClawCloseCause.UnsupportedData
        1007 -> NanoKvmPicoClawCloseCause.InvalidPayload
        1009 -> NanoKvmPicoClawCloseCause.MessageTooLarge
        4001 -> NanoKvmPicoClawCloseCause.ManualHidLockHeld
        4002 -> NanoKvmPicoClawCloseCause.RuntimeUnavailable
        4003 -> NanoKvmPicoClawCloseCause.AuthenticationFailed
        4004 -> NanoKvmPicoClawCloseCause.SessionTakenOver
        4005 -> NanoKvmPicoClawCloseCause.UpstreamClosed
        else -> NanoKvmPicoClawCloseCause.Other(code)
    },
)

private fun String.picoGatewayHasUtf8AtMost(limit: Int): Boolean {
    var bytes = 0
    var index = 0
    while (index < length) {
        val value = this[index]
        bytes += when {
            value.code < 0x80 -> 1
            value.code < 0x800 -> 2
            value.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            else -> 3
        }
        if (bytes > limit) return false
        index++
    }
    return true
}

private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
private const val MAX_RETAINED_PICOCLAW_EVENTS = 16
internal const val MAX_PICOCLAW_GATEWAY_MESSAGE_BYTES = 1 * 1_024 * 1_024
internal const val MAX_PICOCLAW_RUNTIME_MILLIS = 30 * 60 * 1_000
private const val MAX_PICOCLAW_CONTENT_BYTES = 64 * 1_024
private const val MAX_INBOUND_TYPE_BYTES = 128
private const val MAX_INBOUND_ID_BYTES = 256
private const val MAX_INBOUND_TEXT_BYTES = 256 * 1_024
private const val MAX_INBOUND_ACTION_BYTES = 512
private const val MAX_INBOUND_ERROR_CODE_BYTES = 128
private const val MAX_CLOSE_REASON_BYTES = 123
private const val MAX_CLOSE_REASON_CHARS = 123
private const val NORMAL_CLOSE_CODE = 1000
private const val UNSUPPORTED_DATA_CLOSE_CODE = 1003
private const val INVALID_PAYLOAD_CLOSE_CODE = 1007
private const val MESSAGE_TOO_LARGE_CLOSE_CODE = 1009

package org.nanokvm.video

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class NanoKvmWebRtcProtocolException(message: String) : IllegalArgumentException(message)

internal class NanoKvmWebRtcIceServer(
    val urls: List<String>,
    val username: String,
    val credential: String,
) {
    init {
        require(urls.isNotEmpty())
    }

    override fun toString(): String =
        "NanoKvmWebRtcIceServer(urls=${urls.size}, username=<redacted>, credential=<redacted>)"
}

internal data class NanoKvmWebRtcSessionDescription(
    val type: String,
    val sdp: String,
) {
    override fun toString(): String =
        "NanoKvmWebRtcSessionDescription(type=$type, sdp=<redacted:${sdp.length} chars>)"
}

internal data class NanoKvmWebRtcIceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
) {
    override fun toString(): String =
        "NanoKvmWebRtcIceCandidate(candidate=<redacted:${candidate.length} chars>, " +
            "sdpMid=<redacted>, sdpMLineIndex=$sdpMLineIndex)"
}

internal sealed interface NanoKvmWebRtcServerSignal {
    data class IceServers(val servers: List<NanoKvmWebRtcIceServer>) : NanoKvmWebRtcServerSignal
    data class VideoAnswer(
        val description: NanoKvmWebRtcSessionDescription,
    ) : NanoKvmWebRtcServerSignal
    data class VideoCandidate(val candidate: NanoKvmWebRtcIceCandidate) : NanoKvmWebRtcServerSignal
    data object Heartbeat : NanoKvmWebRtcServerSignal
    data class Unknown(val event: String) : NanoKvmWebRtcServerSignal
}

/** Strict, bounded codec for NanoKVM 2.4.3's JSON-string-inside-JSON signaling envelope. */
internal object NanoKvmWebRtcSignaling {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
    }

    fun parseServerMessage(text: String): NanoKvmWebRtcServerSignal {
        requireUtf8Bound(text, 1, MAX_SIGNAL_BYTES, "WebRTC signaling message")
        val envelope = parseObject(text, "WebRTC signaling envelope")
        val event = envelope.requiredString("event", MAX_EVENT_CHARS)
        val data = envelope.optionalString("data") ?: ""
        return when (event) {
            EVENT_ICE_SERVERS -> NanoKvmWebRtcServerSignal.IceServers(parseIceServers(data))
            EVENT_VIDEO_ANSWER -> NanoKvmWebRtcServerSignal.VideoAnswer(parseAnswer(data))
            EVENT_VIDEO_CANDIDATE -> NanoKvmWebRtcServerSignal.VideoCandidate(parseCandidate(data))
            EVENT_HEARTBEAT -> NanoKvmWebRtcServerSignal.Heartbeat
            else -> NanoKvmWebRtcServerSignal.Unknown(event)
        }
    }

    fun encodeOffer(description: NanoKvmWebRtcSessionDescription): String {
        if (description.type != "offer") protocolError("WebRTC offer type is invalid")
        requireUtf8Bound(description.sdp, 1, MAX_SDP_BYTES, "WebRTC offer SDP")
        return encode(
            EVENT_VIDEO_OFFER,
            buildJsonObject {
                put("type", description.type)
                put("sdp", description.sdp)
            }.toString(),
        )
    }

    fun encodeCandidate(candidate: NanoKvmWebRtcIceCandidate): String {
        validateCandidate(candidate)
        return encode(
            EVENT_VIDEO_CANDIDATE,
            buildJsonObject {
                put("candidate", candidate.candidate)
                candidate.sdpMid?.let { put("sdpMid", it) }
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }.toString(),
        )
    }

    fun encodeHeartbeat(): String = encode(EVENT_HEARTBEAT, "")

    private fun parseIceServers(data: String): List<NanoKvmWebRtcIceServer> {
        requireUtf8Bound(data, 2, MAX_ICE_SERVERS_BYTES, "WebRTC ICE server payload")
        val values = parseElement(data, "WebRTC ICE server payload") as? JsonArray
            ?: protocolError("WebRTC ICE servers must be an array")
        if (values.size > MAX_ICE_SERVERS) protocolError("Too many WebRTC ICE servers")
        return values.map { element ->
            val server = element as? JsonObject
                ?: protocolError("WebRTC ICE server entry must be an object")
            val urls = parseIceUrls(server["urls"])
            val username = server.optionalString("username") ?: ""
            val credential = server.optionalString("credential") ?: ""
            requireUtf8Bound(username, 0, MAX_ICE_USERNAME_BYTES, "WebRTC ICE username")
            requireUtf8Bound(credential, 0, MAX_ICE_CREDENTIAL_BYTES, "WebRTC ICE credential")
            NanoKvmWebRtcIceServer(urls, username, credential)
        }
    }

    private fun parseIceUrls(element: JsonElement?): List<String> {
        val urls = when (element) {
            is JsonPrimitive -> listOf(element.requireString("WebRTC ICE URL"))
            is JsonArray -> element.map { value ->
                (value as? JsonPrimitive)?.requireString("WebRTC ICE URL")
                    ?: protocolError("WebRTC ICE URL must be text")
            }
            else -> protocolError("WebRTC ICE URLs are missing")
        }
        if (urls.isEmpty() || urls.size > MAX_URLS_PER_ICE_SERVER) {
            protocolError("WebRTC ICE URL count is invalid")
        }
        return urls.map { url ->
            requireUtf8Bound(url, 1, MAX_ICE_URL_BYTES, "WebRTC ICE URL")
            if (url.any { it.isWhitespace() || it.isISOControl() } || !ICE_URL.matches(url)) {
                protocolError("WebRTC ICE URL scheme is invalid")
            }
            url
        }
    }

    private fun parseAnswer(data: String): NanoKvmWebRtcSessionDescription {
        requireUtf8Bound(data, 2, MAX_SDP_BYTES + 128, "WebRTC answer payload")
        val value = parseObject(data, "WebRTC answer payload")
        val type = value.requiredString("type", 16)
        if (type != "answer") protocolError("WebRTC answer type is invalid")
        val sdp = value.requiredString("sdp", MAX_SDP_BYTES)
        requireUtf8Bound(sdp, 1, MAX_SDP_BYTES, "WebRTC answer SDP")
        return NanoKvmWebRtcSessionDescription(type, sdp)
    }

    private fun parseCandidate(data: String): NanoKvmWebRtcIceCandidate {
        requireUtf8Bound(data, 2, MAX_CANDIDATE_BYTES + 256, "WebRTC candidate payload")
        val value = parseObject(data, "WebRTC candidate payload")
        val candidate = NanoKvmWebRtcIceCandidate(
            candidate = value.requiredString("candidate", MAX_CANDIDATE_BYTES),
            sdpMid = value.optionalString("sdpMid"),
            sdpMLineIndex = value["sdpMLineIndex"]
                ?.let { (it as? JsonPrimitive)?.intOrNull }
                ?: protocolError("WebRTC candidate media index is missing"),
        )
        validateCandidate(candidate)
        return candidate
    }

    private fun validateCandidate(candidate: NanoKvmWebRtcIceCandidate) {
        requireUtf8Bound(candidate.candidate, 1, MAX_CANDIDATE_BYTES, "WebRTC ICE candidate")
        candidate.sdpMid?.let {
            requireUtf8Bound(it, 0, MAX_SDP_MID_BYTES, "WebRTC candidate media ID")
            if (it.any(Char::isISOControl)) protocolError("WebRTC candidate media ID is invalid")
        }
        if (candidate.sdpMLineIndex !in 0..MAX_SDP_MLINE_INDEX) {
            protocolError("WebRTC candidate media index is invalid")
        }
    }

    private fun encode(event: String, data: String): String {
        val encoded = buildJsonObject {
            put("event", event)
            put("data", data)
        }.toString()
        requireUtf8Bound(encoded, 1, MAX_SIGNAL_BYTES, "WebRTC signaling message")
        return encoded
    }

    private fun parseObject(text: String, label: String): JsonObject =
        parseElement(text, label) as? JsonObject ?: protocolError("$label must be an object")

    private fun parseElement(text: String, label: String): JsonElement = try {
        json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        protocolError("$label is malformed")
    }

    private fun JsonObject.requiredString(key: String, maxBytes: Int): String {
        val value = optionalString(key) ?: protocolError("WebRTC signaling field is missing: $key")
        requireUtf8Bound(value, 1, maxBytes, "WebRTC signaling field: $key")
        return value
    }

    private fun JsonObject.optionalString(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.requireString("WebRTC signaling field: $key")
            ?: protocolError("WebRTC signaling field must be text: $key")
    }

    private fun JsonPrimitive.requireString(label: String): String {
        if (!isString) protocolError("$label must be text")
        return content
    }

    private fun requireUtf8Bound(value: String, minimum: Int, maximum: Int, label: String) {
        if (value.length > maximum) protocolError("$label is too large")
        val bytes = value.toByteArray(StandardCharsets.UTF_8).size
        if (bytes !in minimum..maximum) protocolError("$label size is invalid")
    }

    private fun protocolError(message: String): Nothing =
        throw NanoKvmWebRtcProtocolException(message)

    private const val EVENT_ICE_SERVERS = "ice-servers"
    private const val EVENT_VIDEO_OFFER = "video-offer"
    private const val EVENT_VIDEO_ANSWER = "video-answer"
    private const val EVENT_VIDEO_CANDIDATE = "video-candidate"
    private const val EVENT_HEARTBEAT = "heartbeat"
    private const val MAX_EVENT_CHARS = 64
    private const val MAX_SIGNAL_BYTES = 1_100_000
    private const val MAX_SDP_BYTES = 1_048_576
    private const val MAX_ICE_SERVERS_BYTES = 65_536
    private const val MAX_ICE_SERVERS = 8
    private const val MAX_URLS_PER_ICE_SERVER = 4
    private const val MAX_ICE_URL_BYTES = 2_048
    private const val MAX_ICE_USERNAME_BYTES = 256
    private const val MAX_ICE_CREDENTIAL_BYTES = 1_024
    private const val MAX_CANDIDATE_BYTES = 16_384
    private const val MAX_SDP_MID_BYTES = 64
    private const val MAX_SDP_MLINE_INDEX = 64
    private val ICE_URL = Regex("^(?:stun|stuns|turn|turns):.+$", RegexOption.IGNORE_CASE)
}

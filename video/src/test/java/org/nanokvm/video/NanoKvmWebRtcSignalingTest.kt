package org.nanokvm.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoKvmWebRtcSignalingTest {
    @Test
    fun `2_4_3 ice servers use a bounded JSON string inside the outer envelope`() {
        val data =
            """[{"urls":["stun:stun.example:3478","turn:turn.example:3478"],"username":"alice","credential":"secret"}]"""
        val message = outer("ice-servers", data)

        val signal = NanoKvmWebRtcSignaling.parseServerMessage(message)
            as NanoKvmWebRtcServerSignal.IceServers

        assertEquals(1, signal.servers.size)
        assertEquals(
            listOf("stun:stun.example:3478", "turn:turn.example:3478"),
            signal.servers.single().urls,
        )
        assertEquals("alice", signal.servers.single().username)
        assertEquals("secret", signal.servers.single().credential)
        assertFalse(signal.servers.single().toString().contains("alice"))
        assertFalse(signal.servers.single().toString().contains("secret"))
    }

    @Test
    fun `empty ice array is accepted for direct LAN host candidates`() {
        val signal = NanoKvmWebRtcSignaling.parseServerMessage(outer("ice-servers", "[]"))
            as NanoKvmWebRtcServerSignal.IceServers

        assertTrue(signal.servers.isEmpty())
    }

    @Test
    fun `offer and candidate encoders preserve the double encoded wire contract`() {
        val offer = NanoKvmWebRtcSignaling.encodeOffer(
            NanoKvmWebRtcSessionDescription("offer", "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"),
        )
        val offerOuter = Json.parseToJsonElement(offer).jsonObject
        assertEquals("video-offer", offerOuter.getValue("event").jsonPrimitive.content)
        val offerInner = Json.parseToJsonElement(
            offerOuter.getValue("data").jsonPrimitive.content,
        ).jsonObject
        assertEquals("offer", offerInner.getValue("type").jsonPrimitive.content)
        assertTrue(offerInner.getValue("sdp").jsonPrimitive.content.startsWith("v=0"))

        val candidate = NanoKvmWebRtcSignaling.encodeCandidate(
            NanoKvmWebRtcIceCandidate("candidate:1 1 UDP 1 192.0.2.1 5000 typ host", "0", 0),
        )
        val candidateOuter = Json.parseToJsonElement(candidate).jsonObject
        assertEquals("video-candidate", candidateOuter.getValue("event").jsonPrimitive.content)
        val candidateInner = Json.parseToJsonElement(
            candidateOuter.getValue("data").jsonPrimitive.content,
        ).jsonObject
        assertEquals("0", candidateInner.getValue("sdpMid").jsonPrimitive.content)
        assertEquals("0", candidateInner.getValue("sdpMLineIndex").jsonPrimitive.content)

        assertEquals(
            """{"event":"heartbeat","data":""}""",
            NanoKvmWebRtcSignaling.encodeHeartbeat(),
        )
    }

    @Test
    fun `answer and candidate are strictly typed and bounded`() {
        val answer = NanoKvmWebRtcSignaling.parseServerMessage(
            outer("video-answer", """{"type":"answer","sdp":"v=0\\r\\n"}"""),
        ) as NanoKvmWebRtcServerSignal.VideoAnswer
        assertEquals("answer", answer.description.type)

        val candidate = NanoKvmWebRtcSignaling.parseServerMessage(
            outer(
                "video-candidate",
                """{"candidate":"candidate:2 1 UDP 1 192.0.2.2 5001 typ host","sdpMid":"video","sdpMLineIndex":0,"usernameFragment":"ignored"}""",
            ),
        ) as NanoKvmWebRtcServerSignal.VideoCandidate
        assertEquals("video", candidate.candidate.sdpMid)
        assertFalse(candidate.candidate.toString().contains("192.0.2.2"))
        assertFalse(candidate.candidate.toString().contains("video"))

        listOf(
            outer("video-answer", """{"type":"offer","sdp":"v=0"}"""),
            outer("video-candidate", """{"candidate":"x","sdpMLineIndex":65}"""),
            outer("ice-servers", """[{"urls":["https://not-ice.example"]}]"""),
            outer("ice-servers", """[{"urls":["stun:ok"],"credential":42}]"""),
            """{"event":"ice-servers","data":[]}""",
        ).forEach { invalid ->
            assertThrows(NanoKvmWebRtcProtocolException::class.java) {
                NanoKvmWebRtcSignaling.parseServerMessage(invalid)
            }
        }
    }

    @Test
    fun `oversized signaling values fail without reflecting their content`() {
        val secret = "private-turn-credential-" + "x".repeat(1_100)
        val error = assertThrows(NanoKvmWebRtcProtocolException::class.java) {
            NanoKvmWebRtcSignaling.parseServerMessage(
                outer(
                    "ice-servers",
                    """[{"urls":["turn:turn.example"],"credential":"$secret"}]""",
                ),
            )
        }

        assertFalse(error.message.orEmpty().contains("private-turn-credential"))
    }

    private fun outer(event: String, data: String): String =
        """{"event":${JsonPrimitive(event)},"data":${JsonPrimitive(data)}}"""
}

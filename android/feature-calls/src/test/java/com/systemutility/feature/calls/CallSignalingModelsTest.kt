package com.systemutility.feature.calls

import com.systemutility.core.model.CallType
import com.systemutility.core.network.dto.TurnCredentialsResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalingModelsTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `webrtc offer answer and ice signaling payloads serialize with all fields`() {
        val offer = CallSignalingMessage(
            signalKind = "offer",
            callId = "call-uuid-1",
            callType = CallType.VIDEO.apiValue,
            sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"
        )
        val ice = CallSignalingMessage(
            signalKind = "ice",
            callId = "call-uuid-1",
            callType = CallType.VIDEO.apiValue,
            candidate = "candidate:1 1 UDP 2122260223 192.0.2.1 54321 typ host",
            sdpMid = "0",
            sdpMLineIndex = 0
        )

        val decodedOffer = json.decodeFromString<CallSignalingMessage>(json.encodeToString(offer))
        val decodedIce = json.decodeFromString<CallSignalingMessage>(json.encodeToString(ice))

        assertEquals(offer, decodedOffer)
        assertEquals("offer", decodedOffer.signalKind)
        assertEquals("video", decodedOffer.callType)
        assertNull(decodedOffer.candidate)

        assertEquals(ice, decodedIce)
        assertEquals("ice", decodedIce.signalKind)
        assertEquals(0, decodedIce.sdpMLineIndex)
    }

    @Test
    fun `active call and recording default states enforce idle and manual recording semantics`() {
        val defaultCall = ActiveCallState()
        val defaultRec = CallRecordingState()

        assertEquals(CallPhase.IDLE, defaultCall.phase)
        assertNull(defaultCall.callId)
        assertEquals(CallRecordingStatus.IDLE, defaultRec.status)
        assertNull(defaultRec.callId)
    }

    @Test
    fun `cloudflare realtime turn response decodes stun and turn udp tcp tls entries cleanly`() {
        val raw = """
            {
              "provider": "cloudflare_realtime_turn",
              "ttlSeconds": 3600,
              "expiresAt": 1794200000000,
              "iceServers": [
                {
                  "urls": ["stun:stun.cloudflare.com:3478"]
                },
                {
                  "urls": [
                    "turn:turn.cloudflare.com:3478?transport=udp",
                    "turn:turn.cloudflare.com:3478?transport=tcp",
                    "turns:turn.cloudflare.com:5349?transport=tcp"
                  ],
                  "username": "ephemeral-turn-user",
                  "credential": "ephemeral-turn-secret"
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<TurnCredentialsResponse>(raw)
        assertEquals("cloudflare_realtime_turn", parsed.provider)
        assertEquals(3600, parsed.ttlSeconds)
        assertEquals(1794200000000L, parsed.expiresAt)
        assertEquals(2, parsed.iceServers.size)
        assertNull(parsed.iceServers[0].username)
        assertEquals("ephemeral-turn-user", parsed.iceServers[1].username)
        assertEquals("ephemeral-turn-secret", parsed.iceServers[1].credential)
        assertTrue(parsed.iceServers[1].urls.any { it.startsWith("turns:") })
    }
}

package com.bridge.debate.notifications

import com.bridge.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.util.concurrent.TimeUnit

/** Presence: signed-in anywhere in the app (personal queue) counts as online in all meetings. */
class PresenceIT : IntegrationTestBase() {

    @LocalServerPort
    var port: Int = 0

    private fun connect(token: String): StompSession {
        val client = WebSocketStompClient(StandardWebSocketClient()).apply {
            messageConverter = MappingJackson2MessageConverter()
        }
        val headers = StompHeaders().apply { add("Authorization", "Bearer $token") }
        return client.connectAsync("ws://localhost:$port/ws", WebSocketHttpHeaders(), headers, object : StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS)
    }

    private fun onlineFor(viewerToken: String, roomId: String, userId: String): Boolean =
        json(get("/api/v1/rooms/$roomId/presence", viewerToken))
            .firstOrNull { it["userId"].asText() == userId }?.get("online")?.asBoolean() ?: false

    private fun await(deadlineSeconds: Long = 10, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(200)
        }
        assertTrue(check(), "condition not met in time")
    }

    @Test
    fun `a member connected anywhere in the app shows online in the meeting`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val roomId = json(post("/api/v1/rooms", mapOf("title" to "Presence room"), alice.accessToken))["id"].asText()
        val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), alice.accessToken))
        post("/api/v1/invitations/${invite["token"].asText()}/accept", null, bob.accessToken)

        // Bob is signed in on some page (home) — only the personal queue, NOT the room topic.
        val bobSession = connect(bob.accessToken)
        bobSession.subscribe("/user/queue/room-events", object : org.springframework.messaging.simp.stomp.StompFrameHandler {
            override fun getPayloadType(headers: StompHeaders) = Map::class.java
            override fun handleFrame(headers: StompHeaders, payload: Any?) = Unit
        })

        await { onlineFor(alice.accessToken, roomId, bob.userId) }

        // He leaves the app entirely → offline (with a last-seen stamp).
        bobSession.disconnect()
        await { !onlineFor(alice.accessToken, roomId, bob.userId) }
        val entry = json(get("/api/v1/rooms/$roomId/presence", alice.accessToken))
            .first { it["userId"].asText() == bob.userId }
        assertTrue(!entry["lastSeenAt"].isNull, "last seen recorded when the final session closes")
    }
}

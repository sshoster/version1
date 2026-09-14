package com.tufin.debate.notifications

import com.fasterxml.jackson.databind.JsonNode
import com.tufin.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Per-room unread activity badges: room-wide events notify members, never the actor. */
class UnreadBadgeIT : IntegrationTestBase() {

    @Test
    fun `room activity raises unread counts for other members and read clears them`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val roomId = json(post("/api/v1/rooms", mapOf("title" to "Badge room"), alice.accessToken))["id"].asText()
        val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), alice.accessToken))
        post("/api/v1/invitations/${invite["token"].asText()}/accept", null, bob.accessToken)

        // Bob joining is activity for Alice (PARTICIPANT_JOINED, actor = Bob).
        val aliceUnread = awaitUnread(alice.accessToken, roomId)
        assertEquals(roomId, aliceUnread["roomId"].asText())

        // Reading the room clears the badges. The join fans out several events, so keep
        // marking read until the outbox has quiesced and the count stays at zero.
        drainUnread(alice.accessToken, roomId)
        awaitUnread(bob.accessToken, roomId)
        drainUnread(bob.accessToken, roomId)

        // Alice renaming the room is activity for Bob — and must NOT re-badge Alice herself.
        patch("/api/v1/rooms/$roomId", mapOf("title" to "Badge room v2"), alice.accessToken)
        awaitUnread(bob.accessToken, roomId)
        assertEquals(0, unreadFor(alice.accessToken, roomId), "the actor never badges themselves")
    }

    @LocalServerPort
    var port: Int = 0

    @Test
    fun `every stored notification pings the user's live queue - repeated events included`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val roomId = json(post("/api/v1/rooms", mapOf("title" to "Ping room"), alice.accessToken))["id"].asText()
        val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), alice.accessToken))
        post("/api/v1/invitations/${invite["token"].asText()}/accept", null, bob.accessToken)

        // Bob sits on the "home page": connected, subscribed to his personal notification queue.
        val client = WebSocketStompClient(StandardWebSocketClient()).apply {
            messageConverter = MappingJackson2MessageConverter()
        }
        val connectHeaders = StompHeaders().apply { add("Authorization", "Bearer ${bob.accessToken}") }
        val session = client
            .connectAsync("ws://localhost:$port/ws", WebSocketHttpHeaders(), connectHeaders, object : StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS)
        val pings = LinkedBlockingQueue<Map<*, *>>()
        session.subscribe(
            "/user/queue/notifications",
            object : StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = Map::class.java
                override fun handleFrame(headers: StompHeaders, payload: Any?) {
                    pings.offer(payload as Map<*, *>)
                }
            },
        )
        // Drain pings from the join itself and clear the counters before the actual scenario.
        while (pings.poll(2, TimeUnit.SECONDS) != null) Unit
        post("/api/v1/notifications/rooms/$roomId/read", null, bob.accessToken)

        // Two consecutive events of the same type — each must ping separately.
        patch("/api/v1/rooms/$roomId", mapOf("title" to "Ping room v2"), alice.accessToken)
        val first = pings.poll(15, TimeUnit.SECONDS)
        assertNotNull(first, "first event pings")
        assertEquals(roomId, first!!["roomId"])

        patch("/api/v1/rooms/$roomId", mapOf("title" to "Ping room v3"), alice.accessToken)
        val second = pings.poll(15, TimeUnit.SECONDS)
        assertNotNull(second, "second event pings too — the badge must keep counting")
        assertEquals(roomId, second!!["roomId"])

        // And the stored count matches what the pings implied.
        assertEquals(2, unreadFor(bob.accessToken, roomId))
        session.disconnect()
    }

    /** Notifications are written by the async outbox processor, so poll briefly. */
    private fun awaitUnread(token: String, roomId: String, timeoutSeconds: Int = 15): JsonNode {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val entry = json(get("/api/v1/notifications/unread-by-room", token))
                .firstOrNull { it["roomId"].asText() == roomId && it["unread"].asInt() > 0 }
            if (entry != null) return entry
            Thread.sleep(200)
        }
        error("no unread notifications for room $roomId in time")
    }

    /** Marks the room read repeatedly until the count stays at zero (in-flight outbox events included). */
    private fun drainUnread(token: String, roomId: String, timeoutSeconds: Int = 15) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            post("/api/v1/notifications/rooms/$roomId/read", null, token)
            Thread.sleep(400)
            if (unreadFor(token, roomId) == 0) {
                Thread.sleep(400)
                if (unreadFor(token, roomId) == 0) return
            }
        }
        error("unread notifications for room $roomId kept arriving past the deadline")
    }

    private fun unreadFor(token: String, roomId: String): Int =
        json(get("/api/v1/notifications/unread-by-room", token))
            .firstOrNull { it["roomId"].asText() == roomId }?.get("unread")?.asInt() ?: 0
}

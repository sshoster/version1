package com.bridge.debate.notifications

import com.bridge.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Acceptance test 15: WebSocket must not deliver private events to unauthorized users, and room
 * topics are subscribable only by room members.
 */
class WebSocketAuthIT : IntegrationTestBase() {

    @LocalServerPort
    var port: Int = 0

    private fun stompClient(): WebSocketStompClient =
        WebSocketStompClient(StandardWebSocketClient()).apply {
            messageConverter = MappingJackson2MessageConverter()
        }

    private class QueueingHandler : StompSessionHandlerAdapter() {
        val errors: BlockingQueue<String> = LinkedBlockingQueue()
        override fun handleException(
            session: StompSession, command: StompCommand?, headers: StompHeaders, payload: ByteArray, exception: Throwable,
        ) {
            errors.offer(exception.message ?: "error")
        }

        override fun handleTransportError(session: StompSession, exception: Throwable) {
            errors.offer(exception.message ?: "transport error")
        }
    }

    private fun frameHandler(queue: BlockingQueue<Map<*, *>>) = object : StompFrameHandler {
        override fun getPayloadType(headers: StompHeaders): Type = Map::class.java
        override fun handleFrame(headers: StompHeaders, payload: Any?) {
            queue.offer(payload as Map<*, *>)
        }
    }

    private fun pollForType(queue: BlockingQueue<Map<*, *>>, type: String, timeoutSeconds: Long = 15): Map<*, *>? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val event = queue.poll(1, TimeUnit.SECONDS) ?: continue
            if (event["type"] == type) return event
        }
        return null
    }

    private fun connect(token: String): StompSession {
        val connectHeaders = StompHeaders().apply { add("Authorization", "Bearer $token") }
        return stompClient()
            .connectAsync("ws://localhost:$port/ws", WebSocketHttpHeaders(), connectHeaders, QueueingHandler())
            .get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `members receive room events, non-members cannot subscribe, scoped events stay private`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val adam = registerUser("Adam")
        val mallory = registerUser("Mallory")

        val roomId = json(post("/api/v1/rooms", mapOf("title" to "WS room"), alice.accessToken))["id"].asText()
        val partyInvite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), alice.accessToken))
        post("/api/v1/invitations/${partyInvite["token"].asText()}/accept", null, bob.accessToken)
        val advisorInvite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "ADVISOR"), alice.accessToken))
        post("/api/v1/invitations/${advisorInvite["token"].asText()}/accept", null, adam.accessToken)

        // --- Bob (member) subscribes to the room topic and his own event queue.
        val bobSession = connect(bob.accessToken)
        val bobTopic = LinkedBlockingQueue<Map<*, *>>()
        val bobQueue = LinkedBlockingQueue<Map<*, *>>()
        bobSession.subscribe("/topic/rooms/$roomId", frameHandler(bobTopic))
        bobSession.subscribe("/user/queue/room-events", frameHandler(bobQueue))

        // --- Adam (advisor) subscribes to his own event queue.
        val adamSession = connect(adam.accessToken)
        val adamQueue = LinkedBlockingQueue<Map<*, *>>()
        adamSession.subscribe("/user/queue/room-events", frameHandler(adamQueue))

        // --- Mallory (non-member) tries the room topic: subscription is rejected.
        val malloryHandler = QueueingHandler()
        val malloryConnect = StompHeaders().apply { add("Authorization", "Bearer ${mallory.accessToken}") }
        val mallorySession = stompClient()
            .connectAsync("ws://localhost:$port/ws", WebSocketHttpHeaders(), malloryConnect, malloryHandler)
            .get(10, TimeUnit.SECONDS)
        val malloryTopic = LinkedBlockingQueue<Map<*, *>>()
        mallorySession.subscribe("/topic/rooms/$roomId", frameHandler(malloryTopic))

        // --- Trigger a room-wide event. Earlier events (e.g. PARTICIPANT_JOINED from the invite
        // acceptances above) may still be draining through the outbox — poll until the type matches.
        patch("/api/v1/rooms/$roomId", mapOf("title" to "WS room v2"), alice.accessToken)
        val roomEvent = pollForType(bobTopic, "ROOM_UPDATED")
        assertTrue(roomEvent != null, "member receives ROOM_UPDATED")
        while (malloryTopic.poll(1, TimeUnit.SECONDS) != null) {
            error("non-member must receive nothing")
        }

        // --- Publish content scoped to advisors only.
        val body = mapOf("text" to "ליועץ בלבד", "scope" to "MY_ADVISORS", "origin" to "USER_AUTHORED")
        val previewResult = json(post("/api/v1/rooms/$roomId/share-previews", body, alice.accessToken))
        val publishResponse = post(
            "/api/v1/rooms/$roomId/shared-items",
            body + mapOf("previewId" to previewResult["previewId"].asText()),
            alice.accessToken,
        )
        assertEquals(201, publishResponse.statusCode.value(), publishResponse.body)

        // The advisor (in the audience) receives the scoped event on his user queue...
        val adamEvent = pollForType(adamQueue, "SHARED_ITEM_PUBLISHED")
        assertTrue(adamEvent != null, "advisor receives the scoped event")
        // ...its payload carries IDs only, never content...
        assertTrue(adamEvent!!.values.none { it?.toString()?.contains("ליועץ") == true }, "payload must not carry content")
        // ...and Bob (a party OUTSIDE the audience) receives nothing about it.
        assertNull(bobQueue.poll(2, TimeUnit.SECONDS), "non-audience member must not learn the item exists")

        bobSession.disconnect()
        adamSession.disconnect()
        if (mallorySession.isConnected) mallorySession.disconnect()
    }
}

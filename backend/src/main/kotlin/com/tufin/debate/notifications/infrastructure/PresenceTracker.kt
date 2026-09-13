package com.tufin.debate.notifications.infrastructure

import com.tufin.debate.shared.Ids
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import org.springframework.web.socket.messaging.SessionSubscribeEvent
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class PresenceEntry(val userId: String, val online: Boolean, val lastSeenAt: Instant?)

/**
 * In-memory per-room presence: a user is ONLINE while at least one of their WebSocket sessions is
 * subscribed to the room topic; on disconnect we keep a lastSeen timestamp ("recently active").
 * Presence is ephemeral UI state — deliberately not persisted and not audited. Single-instance
 * MVP; a shared store would replace the maps when scaling out.
 */
@Component
class PresenceTracker(private val messagingTemplate: SimpMessagingTemplate) {

    private data class SessionKey(val sessionId: String, val roomId: String)

    /** sessionId+roomId → userId (one WS session can watch several rooms). */
    private val sessions = ConcurrentHashMap<SessionKey, String>()

    /** roomId → userId → open session count. */
    private val online = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    /** roomId → userId → last seen (set when the last session for the room closes). */
    private val lastSeen = ConcurrentHashMap<String, ConcurrentHashMap<String, Instant>>()

    companion object {
        private val ROOM_TOPIC = Regex("^/topic/rooms/([^/]+)$")
    }

    @EventListener
    fun onSubscribe(event: SessionSubscribeEvent) {
        val accessor = StompHeaderAccessor.wrap(event.message)
        val destination = accessor.destination ?: return
        val roomId = ROOM_TOPIC.find(destination)?.groupValues?.get(1) ?: return
        val userId = event.user?.name ?: return
        val sessionId = accessor.sessionId ?: return

        sessions[SessionKey(sessionId, roomId)] = userId
        val roomOnline = online.computeIfAbsent(roomId) { ConcurrentHashMap() }
        val count = roomOnline.merge(userId, 1, Int::plus)
        if (count == 1) broadcast(roomId)
    }

    @EventListener
    fun onDisconnect(event: SessionDisconnectEvent) {
        val sessionId = event.sessionId
        val affected = sessions.keys.filter { it.sessionId == sessionId }
        affected.forEach { key ->
            val userId = sessions.remove(key) ?: return@forEach
            val roomOnline = online[key.roomId] ?: return@forEach
            val remaining = roomOnline.computeIfPresent(userId) { _, count -> (count - 1).takeIf { it > 0 } }
            if (remaining == null) {
                lastSeen.computeIfAbsent(key.roomId) { ConcurrentHashMap() }[userId] = Instant.now()
                broadcast(key.roomId)
            }
        }
    }

    fun presenceFor(roomId: String, userIds: Collection<String>): List<PresenceEntry> {
        val roomOnline = online[roomId] ?: emptyMap<String, Int>()
        val roomSeen = lastSeen[roomId] ?: emptyMap<String, Instant>()
        return userIds.map { userId ->
            PresenceEntry(
                userId = userId,
                online = (roomOnline[userId] ?: 0) > 0,
                lastSeenAt = roomSeen[userId],
            )
        }
    }

    /** IDs only; clients refetch the authorized presence endpoint. */
    private fun broadcast(roomId: String) {
        messagingTemplate.convertAndSend(
            "/topic/rooms/$roomId",
            mapOf(
                "eventId" to Ids.newId(),
                "roomId" to roomId,
                "type" to "PRESENCE_CHANGED",
                "occurredAt" to Instant.now().toString(),
                "resourceId" to null,
                "resourceVersion" to null,
            ),
        )
    }
}

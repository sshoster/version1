package com.bridge.debate.notifications.infrastructure

import com.bridge.debate.shared.Ids
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
 * In-memory presence: a user is ONLINE while ANY of their WebSocket sessions is connected —
 * inside a specific meeting (room-topic subscription) or anywhere in the app (the personal
 * user-queue subscription every signed-in page holds). On the last disconnect we keep a lastSeen
 * timestamp ("recently active"). Presence is ephemeral UI state — deliberately not persisted and
 * not audited. Single-instance MVP; a shared store would replace the maps when scaling out.
 */
@Component
class PresenceTracker(
    private val messagingTemplate: SimpMessagingTemplate,
    private val participantDirectory: com.bridge.debate.participants.application.ParticipantDirectory,
) {

    private data class SessionKey(val sessionId: String, val roomId: String)

    /** sessionId+roomId → userId (one WS session can watch several rooms). */
    private val sessions = ConcurrentHashMap<SessionKey, String>()

    /** roomId → userId → open session count. */
    private val online = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    /** roomId → userId → last seen (set when the last session for the room closes). */
    private val lastSeen = ConcurrentHashMap<String, ConcurrentHashMap<String, Instant>>()

    /** sessionId → userId for app-wide sessions (the personal queue every signed-in page holds). */
    private val appSessions = ConcurrentHashMap<String, String>()

    /** userId → count of app-wide sessions; > 0 means online everywhere they participate. */
    private val appOnline = ConcurrentHashMap<String, Int>()

    /** userId → last time their final app session closed. */
    private val appLastSeen = ConcurrentHashMap<String, Instant>()

    companion object {
        private val ROOM_TOPIC = Regex("^/topic/rooms/([^/]+)$")
        /** One per connection — used to count "signed in somewhere in the app". */
        private const val APP_QUEUE = "/user/queue/room-events"
    }

    /** True while the user has any live connection — used to skip push for users already looking. */
    fun isOnlineAnywhere(userId: String): Boolean = (appOnline[userId] ?: 0) > 0

    @EventListener
    fun onSubscribe(event: SessionSubscribeEvent) {
        val accessor = StompHeaderAccessor.wrap(event.message)
        val destination = accessor.destination ?: return
        val userId = event.user?.name ?: return
        val sessionId = accessor.sessionId ?: return

        if (destination == APP_QUEUE) {
            if (appSessions.putIfAbsent(sessionId, userId) == null) {
                val count = appOnline.merge(userId, 1, Int::plus)
                if (count == 1) broadcastForUser(userId)
            }
            return
        }

        val roomId = ROOM_TOPIC.find(destination)?.groupValues?.get(1) ?: return
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
        appSessions.remove(sessionId)?.let { userId ->
            val remaining = appOnline.computeIfPresent(userId) { _, count -> (count - 1).takeIf { it > 0 } }
            if (remaining == null) {
                appLastSeen[userId] = Instant.now()
                broadcastForUser(userId)
            }
        }
    }

    fun presenceFor(roomId: String, userIds: Collection<String>): List<PresenceEntry> {
        val roomOnline = online[roomId] ?: emptyMap<String, Int>()
        val roomSeen = lastSeen[roomId] ?: emptyMap<String, Instant>()
        return userIds.map { userId ->
            val seenInRoom = roomSeen[userId]
            val seenInApp = appLastSeen[userId]
            PresenceEntry(
                userId = userId,
                online = (roomOnline[userId] ?: 0) > 0 || (appOnline[userId] ?: 0) > 0,
                lastSeenAt = listOfNotNull(seenInRoom, seenInApp).maxOrNull(),
            )
        }
    }

    /** App-wide presence changed: tell every room the user participates in. */
    private fun broadcastForUser(userId: String) {
        participantDirectory.roomIdsForUser(userId).forEach { broadcast(it) }
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

package com.bridge.debate.notifications.infrastructure

import com.bridge.debate.notifications.domain.Notification
import com.bridge.debate.notifications.domain.NotificationRepository
import com.bridge.debate.participants.application.ParticipantDirectory
import com.bridge.debate.shared.Ids
import com.bridge.debate.shared.outbox.OutboxEvent
import com.bridge.debate.shared.outbox.OutboxPublisher
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Delivers outbox events over STOMP. Payloads carry IDs and versions only — content is always
 * fetched through the authorized REST API (design doc §10). Events with an audience list go to
 * each audience member's user queue (so non-audience members never learn the item exists);
 * room-wide events go to the room topic, which only members can subscribe to.
 */
@Component
class WebSocketOutboxPublisher(private val messagingTemplate: SimpMessagingTemplate) : OutboxPublisher {

    companion object {
        private val AUDIENCE_SCOPED_TYPES = setOf(
            "SHARED_ITEM_PUBLISHED", "SHARED_ITEM_WITHDRAWN", "QUESTION_CREATED",
            "APPROVAL_REQUESTED", "APPROVAL_RECORDED", "FILE_SHARED", "FILE_WITHDRAWN",
            "JOIN_REQUESTED", "JOIN_REQUEST_DECIDED",
        )
    }

    override fun publish(event: OutboxEvent) {
        val dto = mapOf(
            "eventId" to event.id,
            "roomId" to event.roomId,
            "type" to event.type,
            "occurredAt" to event.createdAt.toString(),
            "resourceId" to event.payload["resourceId"],
            "resourceVersion" to event.payload["resourceVersion"],
        )
        if (event.type in AUDIENCE_SCOPED_TYPES) {
            audienceUserIds(event).forEach { userId ->
                messagingTemplate.convertAndSendToUser(userId, "/queue/room-events", dto)
            }
        } else {
            messagingTemplate.convertAndSend("/topic/rooms/${event.roomId}", dto)
        }
    }
}

/**
 * Creates in-app notifications from outbox events; idempotent per (eventId, userId).
 * Each stored notification is also pinged to the user's personal queue, so open pages
 * (e.g. the home page badges) update live without polling.
 */
@Component
class NotificationOutboxPublisher(
    private val notifications: NotificationRepository,
    private val participantDirectory: ParticipantDirectory,
    private val messagingTemplate: SimpMessagingTemplate,
) : OutboxPublisher {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Audience-scoped events notify exactly the audience carried in the payload. */
        private val NOTIFYING_TYPES = setOf(
            "SHARED_ITEM_PUBLISHED", "SHARED_ITEM_WITHDRAWN", "QUESTION_CREATED",
            "APPROVAL_REQUESTED", "APPROVAL_RECORDED", "FILE_SHARED",
            "JOIN_REQUESTED", "JOIN_REQUEST_DECIDED",
        )

        /**
         * Room-wide activity that should light the room's unread badge for every active member.
         * Deliberately excludes per-turn negotiation progress ticks to avoid flooding.
         */
        private val MEMBER_NOTIFYING_TYPES = setOf(
            "ROOM_UPDATED", "PARTICIPANT_JOINED", "PROPOSAL_CREATED", "PROPOSAL_REVISED",
            "NEGOTIATION_STARTED", "NEGOTIATION_STOPPED", "NEGOTIATION_WAITING_FOR_USER",
            "OUTCOME_CREATED",
        )
    }

    override fun publish(event: OutboxEvent) {
        val recipients = when (event.type) {
            in NOTIFYING_TYPES -> audienceUserIds(event)
            in MEMBER_NOTIFYING_TYPES -> participantDirectory.activeParticipants(event.roomId).map { it.userId }
            else -> return
        }
        val actor = event.payload["actorUserId"] as? String
        recipients
            .filter { it != actor }
            .forEach { userId ->
                try {
                    notifications.insert(
                        Notification(
                            id = Ids.newId(),
                            userId = userId,
                            roomId = event.roomId,
                            type = event.type,
                            resourceId = event.payload["resourceId"] as? String,
                            eventId = event.id,
                            createdAt = Instant.now(),
                        ),
                    )
                    // Ping AFTER the insert so a client reacting to it always sees the new count.
                    messagingTemplate.convertAndSendToUser(
                        userId, "/queue/notifications",
                        mapOf("roomId" to event.roomId, "type" to event.type, "eventId" to event.id),
                    )
                } catch (e: DuplicateKeyException) {
                    log.debug("Notification for event {} user {} already exists (retry)", event.id, userId)
                }
            }
    }
}

private fun audienceUserIds(event: OutboxEvent): List<String> =
    (event.payload["audienceUserIds"] as? Collection<*>)?.filterIsInstance<String>() ?: emptyList()

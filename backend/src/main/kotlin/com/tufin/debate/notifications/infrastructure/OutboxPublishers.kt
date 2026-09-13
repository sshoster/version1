package com.tufin.debate.notifications.infrastructure

import com.tufin.debate.notifications.domain.Notification
import com.tufin.debate.notifications.domain.NotificationRepository
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.outbox.OutboxEvent
import com.tufin.debate.shared.outbox.OutboxPublisher
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
        private val AUDIENCE_SCOPED_TYPES = setOf("SHARED_ITEM_PUBLISHED", "SHARED_ITEM_WITHDRAWN", "QUESTION_CREATED")
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

/** Creates in-app notifications from outbox events; idempotent per (eventId, userId). */
@Component
class NotificationOutboxPublisher(private val notifications: NotificationRepository) : OutboxPublisher {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val NOTIFYING_TYPES = setOf("SHARED_ITEM_PUBLISHED", "SHARED_ITEM_WITHDRAWN", "QUESTION_CREATED")
    }

    override fun publish(event: OutboxEvent) {
        if (event.type !in NOTIFYING_TYPES) return
        val actor = event.payload["actorUserId"] as? String
        audienceUserIds(event)
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
                } catch (e: DuplicateKeyException) {
                    log.debug("Notification for event {} user {} already exists (retry)", event.id, userId)
                }
            }
    }
}

private fun audienceUserIds(event: OutboxEvent): List<String> =
    (event.payload["audienceUserIds"] as? Collection<*>)?.filterIsInstance<String>() ?: emptyList()

package com.tufin.debate.notifications.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

/**
 * In-app notification. Carries only type + resource IDs — never content, so a notification can
 * always be shown without leaking anything (design doc §13).
 */
@Document("notifications")
class Notification(
    @Id val id: String,
    val userId: String,
    val roomId: String,
    val type: String,
    val resourceId: String?,
    /** Source outbox event id — unique per (eventId, userId) so retries never duplicate. */
    val eventId: String,
    var readAt: Instant? = null,
    val createdAt: Instant,
)

interface NotificationRepository : MongoRepository<Notification, String> {
    fun findTop50ByUserIdOrderByCreatedAtDesc(userId: String): List<Notification>
    fun findByIdAndUserId(id: String, userId: String): Notification?
    fun countByUserIdAndReadAtIsNull(userId: String): Long
    fun findByUserIdAndReadAtIsNull(userId: String): List<Notification>
    fun findByUserIdAndRoomIdAndReadAtIsNull(userId: String, roomId: String): List<Notification>
}

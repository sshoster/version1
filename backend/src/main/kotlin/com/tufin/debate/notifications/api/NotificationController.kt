package com.tufin.debate.notifications.api

import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.notifications.domain.NotificationRepository
import com.tufin.debate.shared.errors.NotFoundException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class NotificationResponse(
    val id: String,
    val roomId: String,
    val type: String,
    val resourceId: String?,
    val read: Boolean,
    val createdAt: Instant,
)

data class UnreadCountResponse(val unread: Long)

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val notifications: NotificationRepository) {

    @GetMapping
    fun list(@AuthenticationPrincipal user: AuthenticatedUser): List<NotificationResponse> =
        notifications.findTop50ByUserIdOrderByCreatedAtDesc(user.userId).map {
            NotificationResponse(it.id, it.roomId, it.type, it.resourceId, it.readAt != null, it.createdAt)
        }

    @GetMapping("/unread-count")
    fun unreadCount(@AuthenticationPrincipal user: AuthenticatedUser): UnreadCountResponse =
        UnreadCountResponse(notifications.countByUserIdAndReadAtIsNull(user.userId))

    @PostMapping("/{notificationId}/read")
    fun markRead(
        @PathVariable notificationId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) {
        val notification = notifications.findByIdAndUserId(notificationId, user.userId)
            ?: throw NotFoundException("This notification was not found")
        if (notification.readAt == null) {
            notification.readAt = Instant.now()
            notifications.save(notification)
        }
    }
}

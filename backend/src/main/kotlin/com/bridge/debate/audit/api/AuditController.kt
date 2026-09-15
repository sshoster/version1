package com.bridge.debate.audit.api

import com.bridge.debate.audit.application.AuditService
import com.bridge.debate.audit.domain.ActorType
import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.participants.domain.ParticipantRole
import com.bridge.debate.permissions.application.PermissionsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AuditEventResponse(
    val id: String,
    val seq: Long,
    val actorType: ActorType,
    val actorId: String?,
    val action: String,
    val targetType: String?,
    val targetId: String?,
    val occurredAt: Instant,
    val metadata: Map<String, String>,
    val prevHash: String?,
    val eventHash: String,
)

@RestController
class TimelineController(private val timelineService: com.bridge.debate.audit.application.TimelineService) {

    /** Plain-language timeline, filtered to what the caller may know about (all members). */
    @GetMapping("/api/v1/rooms/{roomId}/timeline")
    fun timeline(
        @PathVariable roomId: String,
        @RequestParam(defaultValue = "200") limit: Int,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<com.bridge.debate.audit.application.TimelineEntry> =
        timelineService.timeline(roomId, user.userId, limit)
}

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/audit")
class AuditController(
    private val auditService: AuditService,
    private val permissions: PermissionsService,
) {
    @GetMapping
    fun roomAudit(
        @PathVariable roomId: String,
        @RequestParam(defaultValue = "200") limit: Int,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<AuditEventResponse> {
        permissions.requireRole(roomId, user.userId, ParticipantRole.OWNER, ParticipantRole.PARTY)
        return auditService.roomEvents(roomId, limit).map {
            AuditEventResponse(
                it.id, it.seq, it.actorType, it.actorId, it.action, it.targetType, it.targetId,
                it.occurredAt, it.metadata, it.prevHash, it.eventHash,
            )
        }
    }
}

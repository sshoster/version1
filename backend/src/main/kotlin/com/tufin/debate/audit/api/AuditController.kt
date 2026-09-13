package com.tufin.debate.audit.api

import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.permissions.application.PermissionsService
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

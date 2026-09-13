package com.tufin.debate.participants.application

import com.tufin.debate.audit.application.AuditActions
import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.participants.domain.Participant
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.participants.infrastructure.ParticipantRepository
import com.tufin.debate.permissions.application.PermissionsService
import com.tufin.debate.shared.errors.BadRequestException
import com.tufin.debate.shared.errors.ForbiddenException
import com.tufin.debate.shared.errors.NotFoundException
import com.tufin.debate.shared.outbox.OutboxService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ParticipantService(
    private val participants: ParticipantRepository,
    private val permissions: PermissionsService,
    private val directory: ParticipantDirectory,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
) {
    fun list(roomId: String, actor: AuthenticatedUser): List<Participant> {
        permissions.requireParticipant(roomId, actor.userId)
        return directory.activeParticipants(roomId)
    }

    @Transactional
    fun updateRoles(
        roomId: String,
        participantId: String,
        actor: AuthenticatedUser,
        roles: Set<ParticipantRole>,
    ): Participant {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        val participant = participants.findByIdAndRoomId(participantId, roomId)
            ?: throw NotFoundException("This participant was not found")

        if (roles.isEmpty()) throw BadRequestException("A participant needs at least one role")
        if (ParticipantRole.OWNER in participant.roles) {
            throw ForbiddenException("The discussion owner's roles cannot be changed here")
        }
        if (ParticipantRole.OWNER in roles) {
            throw ForbiddenException("Ownership cannot be granted through this action")
        }

        val before = participant.roles.toSortedSet().joinToString(",")
        participant.roles = roles.toMutableSet()
        participants.save(participant)

        auditService.append(
            roomId = roomId,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = AuditActions.PARTICIPANT_ROLES_CHANGED,
            targetType = "Participant",
            targetId = participantId,
            metadata = mapOf("from" to before, "to" to roles.toSortedSet().joinToString(",")),
        )
        outboxService.enqueue(roomId, "ROOM_UPDATED", mapOf("resourceId" to participantId))
        return participant
    }
}

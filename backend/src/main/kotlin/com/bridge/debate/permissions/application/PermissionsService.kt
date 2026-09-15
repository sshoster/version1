package com.bridge.debate.permissions.application

import com.bridge.debate.participants.application.ParticipantDirectory
import com.bridge.debate.participants.domain.Participant
import com.bridge.debate.participants.domain.ParticipantRole
import com.bridge.debate.shared.errors.ForbiddenException
import com.bridge.debate.shared.errors.NotFoundException
import org.springframework.stereotype.Service

/**
 * The single server-side authorization choke point (trust-model invariant 11).
 * Every controller and room-scoped read path goes through here — hiding a UI element is never a
 * security control. Non-members receive 404 (not 403) for room-scoped resources so that room
 * existence is not leaked (docs/security.md T1).
 */
@Service
class PermissionsService(private val directory: ParticipantDirectory) {

    fun findActiveParticipant(roomId: String, userId: String): Participant? =
        directory.activeParticipant(roomId, userId)

    fun requireParticipant(roomId: String, userId: String): Participant =
        findActiveParticipant(roomId, userId) ?: throw NotFoundException("This discussion was not found")

    fun requireRole(roomId: String, userId: String, vararg anyOf: ParticipantRole): Participant {
        val participant = requireParticipant(roomId, userId)
        if (anyOf.none { it in participant.roles }) {
            throw ForbiddenException("You are not allowed to do that in this discussion")
        }
        return participant
    }
}

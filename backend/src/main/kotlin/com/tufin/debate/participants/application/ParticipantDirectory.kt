package com.tufin.debate.participants.application

import com.tufin.debate.participants.domain.Participant
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.participants.domain.ParticipantStatus
import com.tufin.debate.participants.infrastructure.ParticipantRepository
import org.springframework.stereotype.Service

/**
 * Read-side lookup used by other modules (notably permissions), so no module has to reach into
 * this module's infrastructure layer directly.
 */
@Service
class ParticipantDirectory(private val participants: ParticipantRepository) {

    fun activeParticipant(roomId: String, userId: String): Participant? =
        participants.findByRoomIdAndUserIdAndStatus(roomId, userId, ParticipantStatus.ACTIVE)

    fun activeParticipants(roomId: String): List<Participant> =
        participants.findByRoomIdAndStatus(roomId, ParticipantStatus.ACTIVE)

    fun activePartyCount(roomId: String): Int =
        activeParticipants(roomId).count { ParticipantRole.PARTY in it.roles }

    fun roomIdsForUser(userId: String): List<String> =
        participants.findByUserIdAndStatus(userId, ParticipantStatus.ACTIVE).map { it.roomId }
}

package com.bridge.debate.participants.infrastructure

import com.bridge.debate.participants.domain.Invitation
import com.bridge.debate.participants.domain.Participant
import com.bridge.debate.participants.domain.ParticipantStatus
import org.springframework.data.mongodb.repository.MongoRepository

interface ParticipantRepository : MongoRepository<Participant, String> {
    fun findByRoomIdAndUserIdAndStatus(roomId: String, userId: String, status: ParticipantStatus): Participant?
    fun findByRoomIdAndStatus(roomId: String, status: ParticipantStatus): List<Participant>
    fun findByUserIdAndStatus(userId: String, status: ParticipantStatus): List<Participant>
    fun findByIdAndRoomId(id: String, roomId: String): Participant?
}

interface InvitationRepository : MongoRepository<Invitation, String> {
    fun findByTokenHash(tokenHash: String): Invitation?
    fun findByIdAndRoomId(id: String, roomId: String): Invitation?
    fun findByRoomIdOrderByCreatedAtDesc(roomId: String): List<Invitation>
}

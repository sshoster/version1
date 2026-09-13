package com.tufin.debate.participants.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

enum class JoinRequestStatus { PENDING, APPROVED, REJECTED }

/**
 * A request to join a discussion via its join code: anyone with the code may ASK, but only an
 * admin's explicit approval creates a participant — the code alone grants no access to anything.
 */
@Document("join_requests")
class JoinRequest(
    @Id val id: String,
    val roomId: String,
    val userId: String,
    /** Requester's registered display name, snapshotted for the admin's decision UI. */
    val displayName: String,
    var status: JoinRequestStatus = JoinRequestStatus.PENDING,
    var decidedByUserId: String? = null,
    var roleGranted: ParticipantRole? = null,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
    var decidedAt: Instant? = null,
)

interface JoinRequestRepository : MongoRepository<JoinRequest, String> {
    fun findByIdAndRoomId(id: String, roomId: String): JoinRequest?
    fun findByRoomIdAndUserIdAndStatus(roomId: String, userId: String, status: JoinRequestStatus): JoinRequest?
    fun findByRoomIdAndStatusOrderByCreatedAtAsc(roomId: String, status: JoinRequestStatus): List<JoinRequest>
    fun findByUserIdAndStatusOrderByCreatedAtDesc(userId: String, status: JoinRequestStatus): List<JoinRequest>
}

package com.tufin.debate.participants.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class ParticipantRole { OWNER, PARTY, ADVISOR, OBSERVER, FACILITATOR }

enum class ParticipantStatus { ACTIVE, REMOVED }

@Document("participants")
class Participant(
    @Id val id: String,
    val roomId: String,
    val userId: String,
    var roles: MutableSet<ParticipantRole>,
    /** Display-name snapshot at join time (audit-friendly; profile renames don't rewrite history). */
    var displayName: String,
    var status: ParticipantStatus = ParticipantStatus.ACTIVE,
    val schemaVersion: Int = 1,
    val joinedAt: Instant,
)

enum class InvitationStatus { PENDING, ACCEPTED, REVOKED, EXPIRED }

/**
 * Single-use invitation. Only the SHA-256 hash of the token is stored; the raw token is returned
 * exactly once at creation time. Expired invitations are marked lazily (not TTL-deleted) because
 * invitation records are part of the room's audit history.
 */
@Document("invitations")
class Invitation(
    @Id val id: String,
    val roomId: String,
    val email: String?,
    /** Name given by the inviter; becomes the participant's display name in this room (owner decision). */
    val invitedFirstName: String? = null,
    val invitedLastName: String? = null,
    val role: ParticipantRole,
    val tokenHash: String,
    var status: InvitationStatus = InvitationStatus.PENDING,
    val expiresAt: Instant,
    val createdByUserId: String,
    var acceptedByUserId: String? = null,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
    var updatedAt: Instant,
)

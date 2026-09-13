package com.tufin.debate.participants.application

import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.discussion.application.RoomDirectory
import com.tufin.debate.discussion.application.RoomLifecycleService
import com.tufin.debate.discussion.domain.JoinCodes
import com.tufin.debate.discussion.domain.RoomStatus
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.participants.domain.JoinRequest
import com.tufin.debate.participants.domain.JoinRequestRepository
import com.tufin.debate.participants.domain.JoinRequestStatus
import com.tufin.debate.participants.domain.Participant
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.participants.infrastructure.ParticipantRepository
import com.tufin.debate.permissions.application.PermissionsService
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.errors.ApiException
import com.tufin.debate.shared.errors.BadRequestException
import com.tufin.debate.shared.errors.ConflictException
import com.tufin.debate.shared.errors.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.tufin.debate.shared.outbox.OutboxService
import java.time.Instant

data class JoinRequestView(
    val id: String,
    val roomId: String,
    val roomTitle: String,
    val displayName: String,
    val status: JoinRequestStatus,
    val createdAt: Instant,
)

/**
 * Join-by-code with admin approval: the code lets a registered user ASK to join; an admin
 * (OWNER role) approves with a chosen role or rejects. No room content is readable before approval.
 */
@Service
class JoinRequestService(
    private val requests: JoinRequestRepository,
    private val participants: ParticipantRepository,
    private val directory: ParticipantDirectory,
    private val rooms: RoomDirectory,
    private val permissions: PermissionsService,
    private val lifecycle: RoomLifecycleService,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
) {
    companion object {
        private val GRANTABLE = setOf(ParticipantRole.PARTY, ParticipantRole.ADVISOR, ParticipantRole.OBSERVER)
    }

    @Transactional
    fun request(actor: AuthenticatedUser, code: String): JoinRequestView {
        val normalized = JoinCodes.normalize(code)
        if (normalized.length !in 4..12) throw NotFoundException("This code was not found")
        val room = rooms.findByJoinCode(normalized) ?: throw NotFoundException("This code was not found")

        if (directory.activeParticipant(room.id, actor.userId) != null) {
            throw ApiException(409, "ALREADY_MEMBER", "You are already part of this discussion")
        }
        // Idempotent: a pending request is simply returned.
        requests.findByRoomIdAndUserIdAndStatus(room.id, actor.userId, JoinRequestStatus.PENDING)?.let {
            return it.toView(room.title)
        }

        val request = requests.insert(
            JoinRequest(
                id = Ids.newId(),
                roomId = room.id,
                userId = actor.userId,
                displayName = actor.displayName,
                createdAt = Instant.now(),
            ),
        )
        auditService.append(room.id, ActorType.USER, actor.userId, "JOIN_REQUESTED", "JoinRequest", request.id)
        outboxService.enqueue(
            room.id, "JOIN_REQUESTED",
            mapOf("resourceId" to request.id, "audienceUserIds" to adminUserIds(room.id), "actorUserId" to actor.userId),
        )
        return request.toView(room.title)
    }

    fun myPending(actor: AuthenticatedUser): List<JoinRequestView> =
        requests.findByUserIdAndStatusOrderByCreatedAtDesc(actor.userId, JoinRequestStatus.PENDING)
            .map { it.toView(rooms.find(it.roomId)?.title ?: "") }

    fun pendingForRoom(roomId: String, actor: AuthenticatedUser): List<JoinRequestView> {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        val title = rooms.find(roomId)?.title ?: ""
        return requests.findByRoomIdAndStatusOrderByCreatedAtAsc(roomId, JoinRequestStatus.PENDING)
            .map { it.toView(title) }
    }

    @Transactional
    fun approve(roomId: String, requestId: String, actor: AuthenticatedUser, role: ParticipantRole): JoinRequestView {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        if (role !in GRANTABLE) throw BadRequestException("People can join as a participant, advisor, or viewer")
        val request = pending(roomId, requestId)

        if (directory.activeParticipant(roomId, request.userId) == null) {
            participants.insert(
                Participant(
                    id = Ids.newId(),
                    roomId = roomId,
                    userId = request.userId,
                    roles = mutableSetOf(role),
                    displayName = request.displayName,
                    joinedAt = Instant.now(),
                ),
            )
        }
        decide(request, actor, JoinRequestStatus.APPROVED, role)

        auditService.append(
            roomId, ActorType.USER, actor.userId, "JOIN_REQUEST_APPROVED", "JoinRequest", requestId,
            metadata = mapOf("role" to role.name),
        )
        auditService.append(
            roomId, ActorType.USER, request.userId, "PARTICIPANT_JOINED", "Participant", request.userId,
            metadata = mapOf("role" to role.name),
        )
        outboxService.enqueue(roomId, "PARTICIPANT_JOINED", mapOf("resourceId" to request.userId))
        outboxService.enqueue(
            roomId, "JOIN_REQUEST_DECIDED",
            mapOf("resourceId" to requestId, "audienceUserIds" to listOf(request.userId), "actorUserId" to actor.userId),
        )

        maybeAdvanceToIntake(roomId)
        return request.toView(rooms.find(roomId)?.title ?: "")
    }

    @Transactional
    fun reject(roomId: String, requestId: String, actor: AuthenticatedUser): JoinRequestView {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        val request = pending(roomId, requestId)
        decide(request, actor, JoinRequestStatus.REJECTED, null)
        auditService.append(roomId, ActorType.USER, actor.userId, "JOIN_REQUEST_REJECTED", "JoinRequest", requestId)
        outboxService.enqueue(
            roomId, "JOIN_REQUEST_DECIDED",
            mapOf("resourceId" to requestId, "audienceUserIds" to listOf(request.userId), "actorUserId" to actor.userId),
        )
        return request.toView(rooms.find(roomId)?.title ?: "")
    }

    // ---------------- helpers ----------------

    private fun pending(roomId: String, requestId: String): JoinRequest {
        val request = requests.findByIdAndRoomId(requestId, roomId)
            ?: throw NotFoundException("This join request was not found")
        if (request.status != JoinRequestStatus.PENDING) {
            throw ConflictException("This join request was already decided")
        }
        return request
    }

    private fun decide(request: JoinRequest, actor: AuthenticatedUser, status: JoinRequestStatus, role: ParticipantRole?) {
        request.status = status
        request.decidedByUserId = actor.userId
        request.roleGranted = role
        request.decidedAt = Instant.now()
        requests.save(request)
    }

    private fun adminUserIds(roomId: String): List<String> =
        directory.activeParticipants(roomId)
            .filter { ParticipantRole.OWNER in it.roles }
            .map { it.userId }

    private fun maybeAdvanceToIntake(roomId: String) {
        val room = rooms.find(roomId) ?: return
        if (room.status in setOf(RoomStatus.DRAFT, RoomStatus.INVITING) && directory.activePartyCount(roomId) >= 2) {
            if (room.status == RoomStatus.DRAFT) {
                lifecycle.transition(roomId, RoomStatus.INVITING, ActorType.SYSTEM, null, "join approved")
            }
            lifecycle.transition(roomId, RoomStatus.INTAKE, ActorType.SYSTEM, null, "two parties joined")
        }
    }

    private fun JoinRequest.toView(roomTitle: String) =
        JoinRequestView(id, roomId, roomTitle, displayName, status, createdAt)
}

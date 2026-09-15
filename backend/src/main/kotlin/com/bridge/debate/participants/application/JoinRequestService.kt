package com.bridge.debate.participants.application

import com.bridge.debate.audit.application.AuditService
import com.bridge.debate.audit.domain.ActorType
import com.bridge.debate.discussion.application.RoomDirectory
import com.bridge.debate.discussion.application.RoomLifecycleService
import com.bridge.debate.discussion.domain.JoinCodes
import com.bridge.debate.discussion.domain.RoomStatus
import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.identity.application.UserDirectory
import com.bridge.debate.participants.domain.InvitationStatus
import com.bridge.debate.participants.domain.JoinRequest
import com.bridge.debate.participants.domain.JoinRequestRepository
import com.bridge.debate.participants.domain.JoinRequestStatus
import com.bridge.debate.participants.domain.Participant
import com.bridge.debate.participants.domain.ParticipantRole
import com.bridge.debate.participants.infrastructure.ParticipantRepository
import com.bridge.debate.permissions.application.PermissionsService
import com.bridge.debate.shared.Ids
import com.bridge.debate.shared.errors.ApiException
import com.bridge.debate.shared.errors.BadRequestException
import com.bridge.debate.shared.errors.ConflictException
import com.bridge.debate.shared.errors.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.bridge.debate.shared.outbox.OutboxService
import java.time.Instant

data class JoinRequestView(
    val id: String,
    val roomId: String,
    val roomTitle: String,
    val displayName: String,
    val status: JoinRequestStatus,
    /** The discussion admin (room creator) who decides — shown to the requester for follow-up. */
    val ownerName: String,
    val ownerEmail: String,
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
    private val invitations: com.bridge.debate.participants.infrastructure.InvitationRepository,
    private val userDirectory: UserDirectory,
    private val directory: ParticipantDirectory,
    private val rooms: RoomDirectory,
    private val permissions: PermissionsService,
    private val lifecycle: RoomLifecycleService,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
    private val emailDispatcher: com.bridge.debate.notifications.application.EmailDispatcher,
    @param:org.springframework.beans.factory.annotation.Value("\${app.invitations.base-url}") private val baseUrl: String,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
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

        // The person is in — revoke any pending EMAIL invitation addressed to them, so the
        // participants panel never shows the same identity twice (member + waiting invitation).
        userDirectory.emailsByIds(listOf(request.userId))[request.userId]?.trim()?.lowercase()?.let { email ->
            invitations.findByRoomIdOrderByCreatedAtDesc(roomId)
                .filter { it.status == InvitationStatus.PENDING && it.email == email }
                .forEach { stale ->
                    stale.status = InvitationStatus.REVOKED
                    stale.updatedAt = Instant.now()
                    invitations.save(stale)
                }
        }

        auditService.append(
            roomId, ActorType.USER, actor.userId, "JOIN_REQUEST_APPROVED", "JoinRequest", requestId,
            metadata = mapOf("role" to role.name),
        )
        auditService.append(
            roomId, ActorType.USER, request.userId, "PARTICIPANT_JOINED", "Participant", request.userId,
            metadata = mapOf("role" to role.name),
        )
        // actorUserId is the person who joined — members get the badge, the joiner doesn't badge themselves.
        outboxService.enqueue(roomId, "PARTICIPANT_JOINED", mapOf("resourceId" to request.userId, "actorUserId" to request.userId))
        outboxService.enqueue(
            roomId, "JOIN_REQUEST_DECIDED",
            mapOf("resourceId" to requestId, "audienceUserIds" to listOf(request.userId), "actorUserId" to actor.userId),
        )

        maybeAdvanceToIntake(roomId)
        notifyDecision(request, approved = true)
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
        notifyDecision(request, approved = false)
        return request.toView(rooms.find(roomId)?.title ?: "")
    }

    // ---------------- helpers ----------------

    /** Emails the requester about the decision — best-effort, a mail failure never blocks it. */
    private fun notifyDecision(request: JoinRequest, approved: Boolean) {
        try {
            val email = userDirectory.emailsByIds(listOf(request.userId))[request.userId] ?: return
            val room = rooms.find(request.roomId)
            val title = room?.title ?: ""
            val roomUrl = "${baseUrl.trimEnd('/')}/rooms/${request.roomId}"
            val ownerName = room?.let { directory.activeParticipant(request.roomId, it.ownerUserId)?.displayName }.orEmpty()
            val (subject, body) = if (approved) {
                "Bridge AI - Your join request was approved" to """
                    |שלום ${request.displayName},
                    |הבקשה שלך להצטרף לדיון "$title" אושרה 🎉
                    |כניסה לדיון: $roomUrl
                    |
                    |Hello ${request.displayName},
                    |Your request to join the discussion "$title" was approved 🎉
                    |Open the discussion: $roomUrl
                """.trimMargin()
            } else {
                "Bridge AI - Update on your join request" to """
                    |שלום ${request.displayName},
                    |הבקשה שלך להצטרף לדיון "$title" לא אושרה הפעם.
                    |לשאלות אפשר לפנות אל מנהל/ת הדיון${if (ownerName.isNotBlank()) " $ownerName" else ""}.
                    |
                    |Hello ${request.displayName},
                    |Your request to join the discussion "$title" was not approved this time.
                    |For questions, contact the discussion admin${if (ownerName.isNotBlank()) " $ownerName" else ""}.
                """.trimMargin()
            }
            emailDispatcher.dispatch(email, subject, body.replace("\n", "<br/>"), body)
        } catch (e: Exception) {
            log.warn("Could not compose the join-request decision email (decision already recorded)", e)
        }
    }

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

    private fun JoinRequest.toView(roomTitle: String): JoinRequestView {
        // The requester is told WHO decides (the room's creator) and how to reach them.
        val room = rooms.find(roomId)
        val ownerName = room?.let { directory.activeParticipant(roomId, it.ownerUserId)?.displayName }.orEmpty()
        val ownerEmail = room?.let { userDirectory.emailsByIds(listOf(it.ownerUserId))[it.ownerUserId] }.orEmpty()
        return JoinRequestView(id, roomId, roomTitle, displayName, status, ownerName, ownerEmail, createdAt)
    }
}

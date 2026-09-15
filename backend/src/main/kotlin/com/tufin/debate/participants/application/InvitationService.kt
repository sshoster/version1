package com.tufin.debate.participants.application

import com.tufin.debate.audit.application.AuditActions
import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.discussion.application.RoomDirectory
import com.tufin.debate.discussion.application.RoomLifecycleService
import com.tufin.debate.discussion.domain.RoomStatus
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.notifications.application.InvitationNotifier
import com.tufin.debate.participants.domain.Invitation
import com.tufin.debate.participants.domain.InvitationStatus
import com.tufin.debate.participants.domain.Participant
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.participants.infrastructure.InvitationRepository
import com.tufin.debate.participants.infrastructure.ParticipantRepository
import com.tufin.debate.permissions.application.PermissionsService
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.errors.BadRequestException
import com.tufin.debate.shared.errors.ConflictException
import com.tufin.debate.shared.errors.NotFoundException
import com.tufin.debate.shared.idempotency.IdempotencyService
import com.tufin.debate.shared.outbox.OutboxService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

data class InvitationCreated(
    val invitationId: String,
    val roomId: String,
    val role: ParticipantRole,
    val email: String?,
    val invitedName: String? = null,
    val expiresAt: Instant,
    /** Returned exactly once; only its hash is stored. */
    val token: String,
    val acceptUrl: String,
    /** Whether an invitation email was handed to the mail server (best-effort). */
    val emailSent: Boolean = false,
)

data class InvitationPublicInfo(
    val roomTitle: String,
    val role: ParticipantRole,
    val status: InvitationStatus,
    val expiresAt: Instant,
    val invitedBy: String,
    val invitedName: String? = null,
)

@Service
class InvitationService(
    private val invitations: InvitationRepository,
    private val participants: ParticipantRepository,
    private val rooms: RoomDirectory,
    private val permissions: PermissionsService,
    private val directory: ParticipantDirectory,
    private val lifecycle: RoomLifecycleService,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
    private val idempotencyService: IdempotencyService,
    private val notifier: InvitationNotifier,
    private val userDirectory: com.tufin.debate.identity.application.UserDirectory,
    private val joinRequests: com.tufin.debate.participants.domain.JoinRequestRepository,
    @param:Value("\${app.invitations.base-url}") private val baseUrl: String,
    @param:Value("\${app.invitations.expiry-days:7}") private val expiryDays: Long,
    private val transactionTemplate: org.springframework.transaction.support.TransactionTemplate,
) {
    companion object {
        private val random = SecureRandom()
        private val hex = HexFormat.of()
        private val INVITABLE_ROLES = setOf(ParticipantRole.PARTY, ParticipantRole.ADVISOR, ParticipantRole.OBSERVER)
    }

    fun create(
        roomId: String,
        actor: AuthenticatedUser,
        role: ParticipantRole,
        email: String?,
        firstName: String?,
        lastName: String?,
        idempotencyKey: String?,
    ): InvitationCreated =
        idempotencyService.execute(roomId, "INVITATION_CREATE", idempotencyKey, InvitationCreated::class.java) {
            // TransactionTemplate (not @Transactional) because this is a same-class call: a
            // self-invocation would bypass the Spring proxy and run without a transaction.
            val created = transactionTemplate.execute { createInternal(roomId, actor, role, email, firstName, lastName) }!!
            // Email goes out only after the invitation committed; failure keeps the link usable.
            val sent = notifier.invitationCreated(
                email = created.email,
                recipientName = created.invitedName,
                inviterName = actor.displayName,
                roomTitle = rooms.find(roomId)?.title ?: "",
                role = created.role.name,
                acceptUrl = created.acceptUrl,
                expiresAt = created.expiresAt,
            )
            created.copy(emailSent = sent)
        }

    private fun createInternal(
        roomId: String,
        actor: AuthenticatedUser,
        role: ParticipantRole,
        email: String?,
        firstName: String?,
        lastName: String?,
    ): InvitationCreated {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        if (role !in INVITABLE_ROLES) {
            throw BadRequestException("People can be invited as a participant, advisor, or viewer")
        }
        val room = rooms.find(roomId) ?: throw NotFoundException("This discussion was not found")

        // One identity, one entry: an email that already belongs to an active member cannot be
        // re-invited, and re-inviting the same address replaces the older pending invitation.
        val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalizedEmail != null) {
            if (normalizedEmail in activeMemberEmails(roomId)) {
                throw ConflictException("This person is already part of the discussion")
            }
            supersedePendingInvitations(roomId, normalizedEmail, excludeInvitationId = null)
        }

        val rawToken = newToken()
        val now = Instant.now()
        val invitation = Invitation(
            id = Ids.newId(),
            roomId = roomId,
            email = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            invitedFirstName = firstName?.trim()?.takeIf { it.isNotEmpty() },
            invitedLastName = lastName?.trim()?.takeIf { it.isNotEmpty() },
            role = role,
            tokenHash = hash(rawToken),
            expiresAt = now.plus(Duration.ofDays(expiryDays)),
            createdByUserId = actor.userId,
            createdAt = now,
            updatedAt = now,
        )
        invitations.insert(invitation)

        auditService.append(
            roomId = roomId,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = AuditActions.INVITATION_CREATED,
            targetType = "Invitation",
            targetId = invitation.id,
            metadata = mapOf("role" to role.name),
        )
        outboxService.enqueue(roomId, "ROOM_UPDATED", mapOf("resourceId" to roomId, "actorUserId" to actor.userId))

        if (room.status == RoomStatus.DRAFT) {
            lifecycle.transition(roomId, RoomStatus.INVITING, ActorType.SYSTEM, null, reason = "first invitation")
        }

        return InvitationCreated(
            invitationId = invitation.id,
            roomId = roomId,
            role = role,
            email = invitation.email,
            invitedName = invitedName(invitation),
            expiresAt = invitation.expiresAt,
            token = rawToken,
            acceptUrl = "${baseUrl.trimEnd('/')}/invite/$rawToken",
        )
    }

    /** Minimal information needed to decide whether to accept — no room content is exposed. */
    fun publicInfo(rawToken: String): InvitationPublicInfo {
        val invitation = findByRawToken(rawToken)
        val room = rooms.find(invitation.roomId) ?: throw NotFoundException("This invitation was not found")
        val inviter = participants.findByRoomIdAndUserIdAndStatus(
            invitation.roomId, invitation.createdByUserId, com.tufin.debate.participants.domain.ParticipantStatus.ACTIVE,
        )
        return InvitationPublicInfo(
            roomTitle = room.title,
            role = invitation.role,
            status = invitation.status,
            expiresAt = invitation.expiresAt,
            invitedBy = inviter?.displayName ?: "A participant",
            invitedName = invitedName(invitation),
        )
    }

    @Transactional
    fun accept(rawToken: String, actor: AuthenticatedUser): String {
        val invitation = findByRawToken(rawToken)
        if (invitation.status != InvitationStatus.PENDING) {
            throw ConflictException("This invitation can no longer be used")
        }

        // Accepting your own invitation must NOT consume it — the single-use link stays valid
        // for the person it was actually meant for.
        if (directory.activeParticipant(invitation.roomId, actor.userId) != null) {
            throw com.tufin.debate.shared.errors.ApiException(
                409, "ALREADY_MEMBER",
                "You are already part of this discussion — this invitation is meant for someone else",
            )
        }
        participants.insert(
            Participant(
                id = Ids.newId(),
                roomId = invitation.roomId,
                userId = actor.userId,
                roles = mutableSetOf(invitation.role),
                // Owner decision: the name given by the inviter is this room's display name;
                // the registered name is the fallback when no name was provided.
                displayName = invitedName(invitation) ?: actor.displayName,
                joinedAt = Instant.now(),
            ),
        )

        invitation.status = InvitationStatus.ACCEPTED
        invitation.acceptedByUserId = actor.userId
        invitation.updatedAt = Instant.now()
        invitations.save(invitation)

        // The person is in — clear their other pending entries so they never appear twice:
        // duplicate email invitations and any pending join-by-code request.
        supersedePendingInvitations(invitation.roomId, actor.email.trim().lowercase(), excludeInvitationId = invitation.id)
        joinRequests.findByRoomIdAndUserIdAndStatus(
            invitation.roomId, actor.userId, com.tufin.debate.participants.domain.JoinRequestStatus.PENDING,
        )?.let { joinRequests.delete(it) }

        auditService.append(
            roomId = invitation.roomId,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = AuditActions.INVITATION_ACCEPTED,
            targetType = "Invitation",
            targetId = invitation.id,
            metadata = mapOf("role" to invitation.role.name),
        )
        auditService.append(
            roomId = invitation.roomId,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = AuditActions.PARTICIPANT_JOINED,
            targetType = "Participant",
            targetId = actor.userId,
            metadata = mapOf("role" to invitation.role.name),
        )
        outboxService.enqueue(
            invitation.roomId, "PARTICIPANT_JOINED",
            mapOf("resourceId" to actor.userId, "actorUserId" to actor.userId),
        )

        maybeAdvanceToIntake(invitation.roomId)
        return invitation.roomId
    }

    @Transactional
    fun revoke(roomId: String, invitationId: String, actor: AuthenticatedUser) {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        val invitation = invitations.findByIdAndRoomId(invitationId, roomId)
            ?: throw NotFoundException("This invitation was not found")
        if (invitation.status != InvitationStatus.PENDING) {
            throw ConflictException("Only an invitation that has not been used can be cancelled")
        }
        invitation.status = InvitationStatus.REVOKED
        invitation.updatedAt = Instant.now()
        invitations.save(invitation)

        auditService.append(
            roomId = roomId,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = AuditActions.INVITATION_REVOKED,
            targetType = "Invitation",
            targetId = invitationId,
        )
    }

    fun listForRoom(roomId: String, actor: AuthenticatedUser): List<Invitation> {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        // Hide stale pendings for people who are already in (covers pre-fix historical data too).
        val memberEmails = activeMemberEmails(roomId)
        return invitations.findByRoomIdOrderByCreatedAtDesc(roomId)
            .filterNot { it.status == InvitationStatus.PENDING && it.email != null && it.email in memberEmails }
    }

    /** Emails (lowercased) of the room's active participants. */
    private fun activeMemberEmails(roomId: String): Set<String> =
        userDirectory.emailsByIds(directory.activeParticipants(roomId).map { it.userId })
            .values.map { it.trim().lowercase() }.toSet()

    /** Revokes every other PENDING invitation in the room addressed to the same email. */
    private fun supersedePendingInvitations(roomId: String, email: String, excludeInvitationId: String?) {
        invitations.findByRoomIdOrderByCreatedAtDesc(roomId)
            .filter { it.id != excludeInvitationId && it.status == InvitationStatus.PENDING && it.email == email }
            .forEach { stale ->
                stale.status = InvitationStatus.REVOKED
                stale.updatedAt = Instant.now()
                invitations.save(stale)
            }
    }

    private fun maybeAdvanceToIntake(roomId: String) {
        val room = rooms.find(roomId) ?: return
        if (room.status == RoomStatus.INVITING && directory.activePartyCount(roomId) >= 2) {
            lifecycle.transition(roomId, RoomStatus.INTAKE, ActorType.SYSTEM, null, reason = "two parties joined")
        }
    }

    private fun findByRawToken(rawToken: String): Invitation {
        val invitation = invitations.findByTokenHash(hash(rawToken))
            ?: throw NotFoundException("This invitation was not found")
        if (invitation.status == InvitationStatus.PENDING && invitation.expiresAt.isBefore(Instant.now())) {
            // Lazy expiry: records are kept for audit history instead of TTL deletion.
            invitation.status = InvitationStatus.EXPIRED
            invitation.updatedAt = Instant.now()
            invitations.save(invitation)
        }
        return invitation
    }

    private fun invitedName(invitation: Invitation): String? =
        listOfNotNull(invitation.invitedFirstName, invitation.invitedLastName)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return hex.formatHex(digest.digest(raw.toByteArray(StandardCharsets.UTF_8)))
    }
}

package com.bridge.debate.discussion.application

import com.bridge.debate.audit.application.AuditActions
import com.bridge.debate.audit.application.AuditService
import com.bridge.debate.audit.domain.ActorType
import com.bridge.debate.discussion.domain.DiscussionRoom
import com.bridge.debate.discussion.domain.RoomStatus
import com.bridge.debate.discussion.infrastructure.DiscussionRoomRepository
import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.participants.application.ParticipantDirectory
import com.bridge.debate.participants.domain.Participant
import com.bridge.debate.participants.domain.ParticipantRole
import com.bridge.debate.permissions.application.PermissionsService
import com.bridge.debate.shared.Ids
import com.bridge.debate.shared.errors.NotFoundException
import com.bridge.debate.shared.outbox.OutboxService
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class RoomView(val room: DiscussionRoom, val myRoles: Set<ParticipantRole>)

@Service
class RoomService(
    private val rooms: DiscussionRoomRepository,
    private val directory: ParticipantDirectory,
    private val permissions: PermissionsService,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
    private val mongoTemplate: MongoTemplate,
    private val lifecycleService: RoomLifecycleService,
    private val roomPurge: com.bridge.debate.admin.application.RoomPurgeService,
) {
    @Transactional
    fun create(actor: AuthenticatedUser, title: String, objective: String?): RoomView {
        val now = Instant.now()
        val room = DiscussionRoom(
            id = Ids.newId(),
            title = title.trim(),
            objective = objective?.trim()?.takeIf { it.isNotEmpty() },
            status = RoomStatus.DRAFT,
            ownerUserId = actor.userId,
            joinCode = com.bridge.debate.discussion.domain.JoinCodes.generate(),
            createdAt = now,
            updatedAt = now,
        )
        // Join codes are short: retry on the (rare) unique-index collision.
        var attempts = 0
        while (true) {
            try {
                rooms.insert(room)
                break
            } catch (e: org.springframework.dao.DuplicateKeyException) {
                if (++attempts >= 5) throw e
                room.joinCode = com.bridge.debate.discussion.domain.JoinCodes.generate()
            }
        }

        // The creator is both the room OWNER and a primary PARTY.
        val ownerRoles = mutableSetOf(ParticipantRole.OWNER, ParticipantRole.PARTY)
        mongoTemplate.insert(
            Participant(
                id = Ids.newId(),
                roomId = room.id,
                userId = actor.userId,
                roles = ownerRoles,
                displayName = actor.displayName,
                joinedAt = now,
            ),
        )

        auditService.append(
            roomId = room.id,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = AuditActions.ROOM_CREATED,
            targetType = "DiscussionRoom",
            targetId = room.id,
        )
        outboxService.enqueue(
            room.id, "ROOM_UPDATED",
            mapOf("resourceId" to room.id, "status" to room.status.name, "actorUserId" to actor.userId),
        )

        return RoomView(room, ownerRoles)
    }

    fun listForUser(actor: AuthenticatedUser): List<RoomView> {
        val participants = directory.roomIdsForUser(actor.userId)
        if (participants.isEmpty()) return emptyList()
        val roomsById = rooms.findByIdIn(participants).associateBy { it.id }
        return participants.mapNotNull { roomId ->
            val room = roomsById[roomId] ?: return@mapNotNull null
            val roles = directory.activeParticipant(roomId, actor.userId)?.roles ?: return@mapNotNull null
            RoomView(room, roles)
        }.sortedByDescending { it.room.updatedAt }
    }

    fun get(roomId: String, actor: AuthenticatedUser): RoomView {
        val participant = permissions.requireParticipant(roomId, actor.userId)
        val room = rooms.findById(roomId).orElseThrow { NotFoundException("This discussion was not found") }
        return RoomView(room, participant.roles)
    }

    /** Party-initiated lifecycle moves (pause/resume/close); the transition table does the rest. */
    fun lifecycle(roomId: String, actor: AuthenticatedUser, target: RoomStatus): RoomView {
        val participant = permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        val room = lifecycleService.transition(roomId, target, com.bridge.debate.audit.domain.ActorType.USER, actor.userId)
        return RoomView(room, participant.roles)
    }

    /**
     * Permanent deletion by the room's admin — allowed ONLY for a CLOSED discussion (close first,
     * delete second: no live conversation can vanish under the participants). Cascades over every
     * room-scoped record and the stored files, like the platform-admin purge.
     */
    fun deleteClosed(roomId: String, actor: AuthenticatedUser) {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        val room = rooms.findById(roomId).orElseThrow { NotFoundException("This discussion was not found") }
        if (room.status != RoomStatus.CLOSED) {
            throw com.bridge.debate.shared.errors.ConflictException("Only a closed discussion can be deleted")
        }
        roomPurge.purgeRoom(roomId)
    }

    /** Reopening a CLOSED discussion is restricted to the owner. */
    fun reopen(roomId: String, actor: AuthenticatedUser): RoomView {
        val participant = permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        val room = lifecycleService.transition(roomId, RoomStatus.ACTIVE, com.bridge.debate.audit.domain.ActorType.USER, actor.userId, "reopened")
        return RoomView(room, participant.roles)
    }

    @Transactional
    fun update(roomId: String, actor: AuthenticatedUser, title: String?, objective: String?): RoomView {
        val participant = permissions.requireRole(roomId, actor.userId, ParticipantRole.OWNER)
        val room = rooms.findById(roomId).orElseThrow { NotFoundException("This discussion was not found") }

        val changes = mutableMapOf<String, String>()
        title?.trim()?.takeIf { it.isNotEmpty() && it != room.title }?.let {
            room.title = it
            changes["title"] = "changed"
        }
        if (objective != null && objective.trim() != (room.objective ?: "")) {
            room.objective = objective.trim().takeIf { it.isNotEmpty() }
            changes["objective"] = "changed"
        }
        if (changes.isEmpty()) return RoomView(room, participant.roles)

        room.updatedAt = Instant.now()
        rooms.save(room) // optimistic @Version check

        auditService.append(
            roomId = roomId,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = AuditActions.ROOM_UPDATED,
            targetType = "DiscussionRoom",
            targetId = roomId,
            metadata = changes,
        )
        outboxService.enqueue(
            roomId, "ROOM_UPDATED",
            mapOf("resourceId" to roomId, "status" to room.status.name, "actorUserId" to actor.userId),
        )
        return RoomView(room, participant.roles)
    }
}

package com.tufin.debate.discussion.application

import com.tufin.debate.audit.application.AuditActions
import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.discussion.domain.DiscussionRoom
import com.tufin.debate.discussion.domain.RoomStatus
import com.tufin.debate.discussion.domain.RoomTransitions
import com.tufin.debate.shared.errors.ConflictException
import com.tufin.debate.shared.errors.NotFoundException
import com.tufin.debate.shared.outbox.OutboxService
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * All room state transitions go through here (docs/architecture.md §4): the transition table is
 * validated, the status change is a compare-and-set on the previous status (no lost updates), and
 * the audit + outbox events commit in the same transaction.
 *
 * Callers are responsible for actor authorization before invoking a transition.
 */
@Service
class RoomLifecycleService(
    private val mongoTemplate: MongoTemplate,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
) {
    @Transactional
    fun transition(
        roomId: String,
        to: RoomStatus,
        actorType: ActorType,
        actorId: String?,
        reason: String? = null,
    ): DiscussionRoom {
        val room = mongoTemplate.findById(roomId, DiscussionRoom::class.java)
            ?: throw NotFoundException("This discussion was not found")
        val from = room.status
        if (from == to) return room
        if (!RoomTransitions.isAllowed(from, to)) {
            throw ConflictException("This discussion cannot move from $from to $to")
        }

        val result = mongoTemplate.updateFirst(
            Query(where("_id").`is`(roomId).and("status").`is`(from)),
            Update().set("status", to).set("updatedAt", Instant.now()).inc("version", 1),
            DiscussionRoom::class.java,
        )
        if (result.modifiedCount != 1L) {
            throw ConflictException("The discussion changed at the same time. Reload and try again.")
        }

        auditService.append(
            roomId = roomId,
            actorType = actorType,
            actorId = actorId,
            action = AuditActions.ROOM_STATUS_CHANGED,
            targetType = "DiscussionRoom",
            targetId = roomId,
            metadata = buildMap {
                put("from", from.name)
                put("to", to.name)
                reason?.let { put("reason", it) }
            },
        )
        outboxService.enqueue(
            roomId = roomId,
            type = "ROOM_UPDATED",
            payload = mapOf("resourceId" to roomId, "status" to to.name, "actorUserId" to actorId),
        )

        room.status = to
        return room
    }
}

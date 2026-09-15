package com.bridge.debate.audit.application

import com.bridge.debate.audit.domain.ActorType
import com.bridge.debate.audit.domain.AuditChainHead
import com.bridge.debate.audit.domain.AuditEvent
import com.bridge.debate.audit.domain.HashChain
import com.bridge.debate.shared.Ids
import com.bridge.debate.shared.errors.ConflictException
import com.bridge.debate.shared.web.CorrelationIdFilter
import org.slf4j.MDC
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class AuditService(private val mongoTemplate: MongoTemplate) {

    /**
     * Appends one event to the room's hash chain. Must be called inside the same transaction as
     * the domain change it records. Concurrent appends to the same room are serialized by the
     * compare-and-set on the chain head (a losing writer aborts the whole transaction).
     */
    fun append(
        roomId: String,
        actorType: ActorType,
        actorId: String?,
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        metadata: Map<String, String> = emptyMap(),
        audienceSnapshotId: String? = null,
    ): AuditEvent {
        val occurredAt = Instant.now()
        val head = mongoTemplate.findById(roomId, AuditChainHead::class.java)
        val seq = (head?.seq ?: 0L) + 1
        val prevHash = head?.lastHash
        val canonical = HashChain.canonical(
            roomId, seq, actorType, actorId, action, targetType, targetId, occurredAt, metadata,
        )
        val eventHash = HashChain.compute(prevHash, canonical)

        advanceHead(roomId, prevHash, eventHash, seq)

        val event = AuditEvent(
            id = Ids.newId(),
            roomId = roomId,
            seq = seq,
            actorType = actorType,
            actorId = actorId,
            action = action,
            targetType = targetType,
            targetId = targetId,
            occurredAt = occurredAt,
            correlationId = MDC.get(CorrelationIdFilter.MDC_KEY),
            audienceSnapshotId = audienceSnapshotId,
            metadata = metadata,
            prevHash = prevHash,
            eventHash = eventHash,
        )
        return mongoTemplate.insert(event)
    }

    private fun advanceHead(roomId: String, expectedLastHash: String?, newHash: String, newSeq: Long) {
        if (expectedLastHash == null) {
            try {
                mongoTemplate.insert(AuditChainHead(roomId, newHash, newSeq))
            } catch (e: org.springframework.dao.DuplicateKeyException) {
                throw ConflictException("Another change was recorded at the same time. Try again.")
            }
            return
        }
        val result = mongoTemplate.updateFirst(
            Query(where("_id").`is`(roomId).and("lastHash").`is`(expectedLastHash)),
            Update().set("lastHash", newHash).set("seq", newSeq),
            AuditChainHead::class.java,
        )
        if (result.modifiedCount != 1L) {
            throw ConflictException("Another change was recorded at the same time. Try again.")
        }
    }

    fun roomEvents(roomId: String, limit: Int = 200): List<AuditEvent> {
        val query = Query(where("roomId").`is`(roomId))
            .with(Sort.by(Sort.Direction.ASC, "seq"))
            .limit(limit.coerceIn(1, 500))
        return mongoTemplate.find(query, AuditEvent::class.java)
    }
}

/** Common audit action names used by Phase 1 modules. */
object AuditActions {
    const val ROOM_CREATED = "ROOM_CREATED"
    const val ROOM_UPDATED = "ROOM_UPDATED"
    const val ROOM_STATUS_CHANGED = "ROOM_STATUS_CHANGED"
    const val INVITATION_CREATED = "INVITATION_CREATED"
    const val INVITATION_REVOKED = "INVITATION_REVOKED"
    const val INVITATION_ACCEPTED = "INVITATION_ACCEPTED"
    const val PARTICIPANT_JOINED = "PARTICIPANT_JOINED"
    const val PARTICIPANT_ROLES_CHANGED = "PARTICIPANT_ROLES_CHANGED"
}

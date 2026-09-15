package com.bridge.debate.shared.outbox

import com.bridge.debate.shared.Ids
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Service
import java.time.Instant

enum class OutboxStatus { PENDING, PROCESSING, PROCESSED, DEAD_LETTER }

/**
 * Transactional outbox (docs/architecture.md §6.4): written in the same MongoDB transaction as the
 * domain change and the audit event; a worker publishes asynchronously with retries.
 */
@Document("outbox_events")
class OutboxEvent(
    @Id val id: String = Ids.newId(),
    val roomId: String,
    val type: String,
    /** Minimal payload: IDs and versions only — never private content (docs/security.md T9). */
    val payload: Map<String, Any?> = emptyMap(),
    var status: OutboxStatus = OutboxStatus.PENDING,
    var retryCount: Int = 0,
    var nextAttemptAt: Instant = Instant.now(),
    val createdAt: Instant = Instant.now(),
    var processedAt: Instant? = null,
)

interface OutboxEventRepository : MongoRepository<OutboxEvent, String>

/** Writes outbox events inside the caller's transaction. */
@Service
class OutboxService(private val repository: OutboxEventRepository) {
    fun enqueue(roomId: String, type: String, payload: Map<String, Any?> = emptyMap()): OutboxEvent =
        repository.save(OutboxEvent(roomId = roomId, type = type, payload = payload))
}

/** Delivery target for processed outbox events. Phase 2 adds WebSocket + notification publishers. */
interface OutboxPublisher {
    fun publish(event: OutboxEvent)
}

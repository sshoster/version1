package com.bridge.debate.shared.outbox

import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class OutboxWorker(
    private val mongoTemplate: MongoTemplate,
    private val publishers: List<OutboxPublisher>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_RETRIES = 8
        private const val BATCH_LIMIT = 50
    }

    @Scheduled(fixedDelayString = "\${app.outbox.poll-interval-ms:2000}")
    fun processPending() {
        var processed = 0
        while (processed < BATCH_LIMIT) {
            val event = claimNext() ?: break
            processed++
            try {
                publishers.forEach { it.publish(event) }
                markProcessed(event)
            } catch (e: Exception) {
                log.warn("Outbox publish failed for event {} (retry {})", event.id, event.retryCount, e)
                scheduleRetry(event)
            }
        }
    }

    private fun claimNext(): OutboxEvent? {
        val query = Query(
            where("status").`is`(OutboxStatus.PENDING)
                .and("nextAttemptAt").lte(Instant.now()),
        )
        val update = Update().set("status", OutboxStatus.PROCESSING)
        return mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(true), OutboxEvent::class.java,
        )
    }

    private fun markProcessed(event: OutboxEvent) {
        mongoTemplate.updateFirst(
            Query(where("_id").`is`(event.id)),
            Update().set("status", OutboxStatus.PROCESSED).set("processedAt", Instant.now()),
            OutboxEvent::class.java,
        )
    }

    private fun scheduleRetry(event: OutboxEvent) {
        val retries = event.retryCount + 1
        val update = if (retries > MAX_RETRIES) {
            Update().set("status", OutboxStatus.DEAD_LETTER).set("retryCount", retries)
        } else {
            val backoff = Duration.ofSeconds(1L shl minOf(retries, 6))
            Update()
                .set("status", OutboxStatus.PENDING)
                .set("retryCount", retries)
                .set("nextAttemptAt", Instant.now().plus(backoff))
        }
        mongoTemplate.updateFirst(Query(where("_id").`is`(event.id)), update, OutboxEvent::class.java)
    }
}

/** Phase 1 placeholder publisher: real-time and notification delivery arrive in Phase 2. */
@Component
class LoggingOutboxPublisher : OutboxPublisher {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun publish(event: OutboxEvent) {
        log.info("Outbox event {} type={} room={}", event.id, event.type, event.roomId)
    }
}

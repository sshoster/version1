package com.tufin.debate.shared.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.errors.ConflictException
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Service
import java.time.Instant

enum class IdempotencyStatus { IN_PROGRESS, COMPLETED }

@Document("idempotency_records")
class IdempotencyRecord(
    @Id val id: String = Ids.newId(),
    val roomId: String,
    val operationType: String,
    val idempotencyKey: String,
    var status: IdempotencyStatus = IdempotencyStatus.IN_PROGRESS,
    var resultJson: String? = null,
    val createdAt: Instant = Instant.now(),
)

interface IdempotencyRecordRepository : MongoRepository<IdempotencyRecord, String> {
    fun findByRoomIdAndOperationTypeAndIdempotencyKey(
        roomId: String,
        operationType: String,
        idempotencyKey: String,
    ): IdempotencyRecord?
}

/**
 * Replay-safe execution: the first call with a given (roomId, operationType, key) runs [action]
 * and stores its result; a repeated call returns the stored result without re-executing.
 * Uniqueness is guaranteed by a unique compound index (see migration V001).
 */
@Service
class IdempotencyService(
    private val repository: IdempotencyRecordRepository,
    private val objectMapper: ObjectMapper,
) {
    fun <T : Any> execute(
        roomId: String,
        operationType: String,
        idempotencyKey: String?,
        resultType: Class<T>,
        action: () -> T,
    ): T {
        if (idempotencyKey.isNullOrBlank()) return action()

        val record = IdempotencyRecord(
            roomId = roomId,
            operationType = operationType,
            idempotencyKey = idempotencyKey,
        )
        try {
            repository.insert(record)
        } catch (e: DuplicateKeyException) {
            val existing = repository.findByRoomIdAndOperationTypeAndIdempotencyKey(
                roomId, operationType, idempotencyKey,
            ) ?: throw ConflictException("The same operation is already in progress. Try again shortly.")
            if (existing.status == IdempotencyStatus.COMPLETED && existing.resultJson != null) {
                return objectMapper.readValue(existing.resultJson, resultType)
            }
            throw ConflictException("The same operation is already in progress. Try again shortly.")
        }

        try {
            val result = action()
            record.status = IdempotencyStatus.COMPLETED
            record.resultJson = objectMapper.writeValueAsString(result)
            repository.save(record)
            return result
        } catch (e: Exception) {
            // Failed executions must not block a retry with the same key.
            repository.deleteById(record.id)
            throw e
        }
    }
}

package com.bridge.debate.audit.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

enum class ActorType { USER, AI, ADVISOR, SYSTEM }

/**
 * Append-only, hash-chained audit record (docs/trust-model.md §7).
 * There is no API that updates or deletes documents in this collection.
 */
@Document("audit_events")
class AuditEvent(
    @Id val id: String,
    val roomId: String,
    /** Per-room monotonic sequence, allocated via the chain head document. */
    val seq: Long,
    val actorType: ActorType,
    val actorId: String?,
    val action: String,
    val targetType: String?,
    val targetId: String?,
    val occurredAt: Instant,
    val correlationId: String?,
    val audienceSnapshotId: String? = null,
    /** Minimal, non-sensitive metadata only — never content bodies, tokens, or private data. */
    val metadata: Map<String, String> = emptyMap(),
    val prevHash: String?,
    val eventHash: String,
)

/** Head of a room's hash chain; updated with compare-and-set inside the same transaction. */
@Document("audit_chain_heads")
class AuditChainHead(
    @Id val roomId: String,
    var lastHash: String,
    var seq: Long,
)

object HashChain {
    private val hex = HexFormat.of()

    fun canonical(
        roomId: String,
        seq: Long,
        actorType: ActorType,
        actorId: String?,
        action: String,
        targetType: String?,
        targetId: String?,
        occurredAt: Instant,
        metadata: Map<String, String>,
    ): String {
        val meta = metadata.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        return listOf(
            roomId, seq.toString(), actorType.name, actorId ?: "", action,
            targetType ?: "", targetId ?: "", occurredAt.toString(), meta,
        ).joinToString("|")
    }

    fun compute(prevHash: String?, canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((prevHash ?: "GENESIS").toByteArray(StandardCharsets.UTF_8))
        digest.update(canonical.toByteArray(StandardCharsets.UTF_8))
        return hex.formatHex(digest.digest())
    }
}

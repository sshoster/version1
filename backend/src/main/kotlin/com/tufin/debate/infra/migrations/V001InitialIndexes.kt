package com.tufin.debate.infra.migrations

import io.mongock.api.annotations.ChangeUnit
import io.mongock.api.annotations.Execution
import io.mongock.api.annotations.RollbackExecution
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import java.time.Duration

/**
 * Initial collection indexes (docs/architecture.md §5). Auto-index creation is disabled; every
 * index is created here explicitly and versioned.
 */
// transactional = false: createIndexes cannot run inside a MongoDB transaction.
@ChangeUnit(id = "001-initial-indexes", order = "001", author = "system", transactional = false)
class V001InitialIndexes {

    @Execution
    fun execute(template: MongoTemplate) {
        // identity
        template.indexOps("users").createIndex(
            Index().on("email", Sort.Direction.ASC).unique().named("ux_users_email"),
        )
        template.indexOps("refresh_tokens").createIndex(
            Index().on("tokenHash", Sort.Direction.ASC).unique().named("ux_refresh_tokens_token_hash"),
        )
        template.indexOps("refresh_tokens").createIndex(
            Index().on("familyId", Sort.Direction.ASC).named("ix_refresh_tokens_family"),
        )
        // TTL cleanup of expired refresh tokens (purgeAt = expiry + retention buffer).
        template.indexOps("refresh_tokens").createIndex(
            Index().on("purgeAt", Sort.Direction.ASC).expire(Duration.ZERO).named("ttl_refresh_tokens_purge_at"),
        )

        // rooms
        template.indexOps("discussion_rooms").createIndex(
            Index().on("status", Sort.Direction.ASC).on("updatedAt", Sort.Direction.DESC)
                .named("ix_rooms_status_updated_at"),
        )

        // participants
        template.indexOps("participants").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("userId", Sort.Direction.ASC)
                .unique().named("ux_participants_room_user"),
        )
        template.indexOps("participants").createIndex(
            Index().on("userId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
                .named("ix_participants_user_status"),
        )

        // invitations (kept for audit history: expiry is lazy, not TTL)
        template.indexOps("invitations").createIndex(
            Index().on("tokenHash", Sort.Direction.ASC).unique().named("ux_invitations_token_hash"),
        )
        template.indexOps("invitations").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("ix_invitations_room_created_at"),
        )

        // audit
        template.indexOps("audit_events").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("seq", Sort.Direction.ASC)
                .unique().named("ux_audit_events_room_seq"),
        )
        template.indexOps("audit_events").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("occurredAt", Sort.Direction.ASC)
                .named("ix_audit_events_room_occurred_at"),
        )

        // outbox
        template.indexOps("outbox_events").createIndex(
            Index().on("status", Sort.Direction.ASC).on("nextAttemptAt", Sort.Direction.ASC)
                .named("ix_outbox_status_next_attempt"),
        )

        // idempotency
        template.indexOps("idempotency_records").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("operationType", Sort.Direction.ASC)
                .on("idempotencyKey", Sort.Direction.ASC).unique().named("ux_idempotency_room_op_key"),
        )
    }

    @RollbackExecution
    fun rollback(template: MongoTemplate) {
        // Index creation is idempotent; nothing to roll back destructively.
    }
}

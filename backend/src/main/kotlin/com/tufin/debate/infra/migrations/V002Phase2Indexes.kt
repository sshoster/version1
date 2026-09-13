package com.tufin.debate.infra.migrations

import io.mongock.api.annotations.ChangeUnit
import io.mongock.api.annotations.Execution
import io.mongock.api.annotations.RollbackExecution
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import java.time.Duration

/** Phase 2 indexes: private messages, share previews, shared items/versions, notifications. */
@ChangeUnit(id = "002-phase2-indexes", order = "002", author = "system", transactional = false)
class V002Phase2Indexes {

    @Execution
    fun execute(template: MongoTemplate) {
        template.indexOps("private_messages").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("authorUserId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC).named("ix_private_messages_room_author_created"),
        )

        // Previews are transient and hash-only; TTL cleanup is safe (not part of audit history).
        template.indexOps("share_previews").createIndex(
            Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO).named("ttl_share_previews_expires_at"),
        )

        template.indexOps("shared_items").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC)
                .named("ix_shared_items_room_created"),
        )

        template.indexOps("shared_item_versions").createIndex(
            Index().on("sharedItemId", Sort.Direction.ASC).on("version", Sort.Direction.ASC)
                .unique().named("ux_shared_item_versions_item_version"),
        )
        template.indexOps("shared_item_versions").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("audienceUserIds", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC).named("ix_shared_item_versions_room_audience"),
        )

        template.indexOps("audience_snapshots").createIndex(
            Index().on("roomId", Sort.Direction.ASC).named("ix_audience_snapshots_room"),
        )

        template.indexOps("notifications").createIndex(
            Index().on("userId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("ix_notifications_user_created"),
        )
        template.indexOps("notifications").createIndex(
            Index().on("eventId", Sort.Direction.ASC).on("userId", Sort.Direction.ASC)
                .unique().named("ux_notifications_event_user"),
        )
    }

    @RollbackExecution
    fun rollback(template: MongoTemplate) {
        // Index creation is idempotent; nothing to roll back destructively.
    }
}

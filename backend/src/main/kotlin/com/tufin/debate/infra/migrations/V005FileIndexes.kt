package com.tufin.debate.infra.migrations

import io.mongock.api.annotations.ChangeUnit
import io.mongock.api.annotations.Execution
import io.mongock.api.annotations.RollbackExecution
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index

/** Phase 4.5 indexes: file attachments. */
@ChangeUnit(id = "005-file-indexes", order = "005", author = "system", transactional = false)
class V005FileIndexes {

    @Execution
    fun execute(template: MongoTemplate) {
        template.indexOps("attachments").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("ownerUserId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC).named("ix_attachments_room_owner_created"),
        )
        template.indexOps("attachments").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("audienceUserIds", Sort.Direction.ASC)
                .named("ix_attachments_room_audience"),
        )
    }

    @RollbackExecution
    fun rollback(template: MongoTemplate) {
        // Index creation is idempotent; nothing to roll back destructively.
    }
}

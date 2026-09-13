package com.tufin.debate.infra.migrations

import com.tufin.debate.discussion.domain.DiscussionRoom
import com.tufin.debate.discussion.domain.JoinCodes
import io.mongock.api.annotations.ChangeUnit
import io.mongock.api.annotations.Execution
import io.mongock.api.annotations.RollbackExecution
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.index.PartialIndexFilter
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

/** Join-by-code: backfill codes for existing rooms and index join requests. */
@ChangeUnit(id = "007-join-codes", order = "007", author = "system", transactional = false)
class V007JoinCodes {

    @Execution
    fun execute(template: MongoTemplate) {
        // Backfill: every room gets a unique join code.
        template.find(Query(Criteria.where("joinCode").`is`(null)), DiscussionRoom::class.java)
            .forEach { room ->
                template.updateFirst(
                    Query(Criteria.where("_id").`is`(room.id)),
                    Update().set("joinCode", JoinCodes.generate()),
                    DiscussionRoom::class.java,
                )
            }

        template.indexOps("discussion_rooms").createIndex(
            Index().on("joinCode", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(Criteria.where("joinCode").exists(true)))
                .named("ux_rooms_join_code"),
        )
        template.indexOps("join_requests").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC).named("ix_join_requests_room_status"),
        )
        template.indexOps("join_requests").createIndex(
            Index().on("userId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
                .named("ix_join_requests_user_status"),
        )
    }

    @RollbackExecution
    fun rollback(template: MongoTemplate) {
        // Backfill and index creation are idempotent; nothing to roll back destructively.
    }
}

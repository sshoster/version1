package com.tufin.debate.infra.migrations

import io.mongock.api.annotations.ChangeUnit
import io.mongock.api.annotations.Execution
import io.mongock.api.annotations.RollbackExecution
import org.bson.Document
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.index.PartialIndexFilter
import org.springframework.data.mongodb.core.query.Criteria

/** Phase 3 indexes: negotiation runs/turns, questions, agent profiles. */
@ChangeUnit(id = "003-negotiation-indexes", order = "003", author = "system", transactional = false)
class V003NegotiationIndexes {

    @Execution
    fun execute(template: MongoTemplate) {
        // At most ONE active run per room, enforced at the database level.
        template.indexOps("negotiation_runs").createIndex(
            Index().on("roomId", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(Criteria.where("active").`is`(true)))
                .named("ux_negotiation_runs_room_active"),
        )
        template.indexOps("negotiation_runs").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("ix_negotiation_runs_room_created"),
        )

        template.indexOps("negotiation_turns").createIndex(
            Index().on("runId", Sort.Direction.ASC).on("turnNumber", Sort.Direction.ASC)
                .unique().named("ux_negotiation_turns_run_turn"),
        )
        template.indexOps("negotiation_turns").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC)
                .named("ix_negotiation_turns_room_created"),
        )

        template.indexOps("questions").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("toUserId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC).named("ix_questions_room_user_status"),
        )
        template.indexOps("questions").createIndex(
            Index().on("runId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
                .named("ix_questions_run_status"),
        )

        template.indexOps("ai_agent_profiles").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("userId", Sort.Direction.ASC)
                .unique().named("ux_agent_profiles_room_user"),
        )
    }

    @RollbackExecution
    fun rollback(template: MongoTemplate) {
        // Index creation is idempotent; nothing to roll back destructively.
    }
}

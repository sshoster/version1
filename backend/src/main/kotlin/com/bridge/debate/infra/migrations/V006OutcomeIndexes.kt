package com.bridge.debate.infra.migrations

import io.mongock.api.annotations.ChangeUnit
import io.mongock.api.annotations.Execution
import io.mongock.api.annotations.RollbackExecution
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index

/** Phase 5 indexes: outcome artifacts. */
@ChangeUnit(id = "006-outcome-indexes", order = "006", author = "system", transactional = false)
class V006OutcomeIndexes {

    @Execution
    fun execute(template: MongoTemplate) {
        template.indexOps("outcome_artifacts").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("type", Sort.Direction.ASC)
                .on("version", Sort.Direction.ASC).unique().named("ux_outcomes_room_type_version"),
        )
    }

    @RollbackExecution
    fun rollback(template: MongoTemplate) {
        // Index creation is idempotent; nothing to roll back destructively.
    }
}

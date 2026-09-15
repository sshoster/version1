package com.bridge.debate.infra.migrations

import io.mongock.api.annotations.ChangeUnit
import io.mongock.api.annotations.Execution
import io.mongock.api.annotations.RollbackExecution
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index

/** Phase 4 indexes: proposals, versions, approval requests, approvals. */
@ChangeUnit(id = "004-agreement-indexes", order = "004", author = "system", transactional = false)
class V004AgreementIndexes {

    @Execution
    fun execute(template: MongoTemplate) {
        template.indexOps("proposals").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("ix_proposals_room_created"),
        )
        template.indexOps("proposal_versions").createIndex(
            Index().on("proposalId", Sort.Direction.ASC).on("version", Sort.Direction.ASC)
                .unique().named("ux_proposal_versions_proposal_version"),
        )
        template.indexOps("approval_requests").createIndex(
            Index().on("proposalId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
                .named("ix_approval_requests_proposal_status"),
        )
        template.indexOps("approval_requests").createIndex(
            Index().on("roomId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
                .named("ix_approval_requests_room_status"),
        )
        // One decision per (request, user) — replay-safe at the database level.
        template.indexOps("approvals").createIndex(
            Index().on("approvalRequestId", Sort.Direction.ASC).on("userId", Sort.Direction.ASC)
                .unique().named("ux_approvals_request_user"),
        )
    }

    @RollbackExecution
    fun rollback(template: MongoTemplate) {
        // Index creation is idempotent; nothing to roll back destructively.
    }
}

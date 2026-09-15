package com.bridge.debate.infra.migrations

import io.mongock.api.annotations.ChangeUnit
import io.mongock.api.annotations.Execution
import io.mongock.api.annotations.RollbackExecution
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index

/** Push notifications: FCM device registry indexes. */
@ChangeUnit(id = "008-push-devices", order = "008", author = "system", transactional = false)
class V008PushDevices {

    @Execution
    fun execute(template: MongoTemplate) {
        template.indexOps("user_devices").createIndex(
            Index().on("token", Sort.Direction.ASC).unique().named("ux_user_devices_token"),
        )
        template.indexOps("user_devices").createIndex(
            Index().on("userId", Sort.Direction.ASC).named("ix_user_devices_user"),
        )
    }

    @RollbackExecution
    fun rollback(template: MongoTemplate) {
        // Index creation is idempotent; nothing destructive to roll back.
    }
}

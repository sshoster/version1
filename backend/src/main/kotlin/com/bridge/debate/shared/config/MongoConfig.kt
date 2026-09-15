package com.bridge.debate.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class MongoConfig {

    /** Multi-document transactions require a replica set (dev compose + Testcontainers provide one). */
    @Bean
    fun transactionManager(dbFactory: MongoDatabaseFactory): MongoTransactionManager =
        MongoTransactionManager(dbFactory)

    /** Retries transactions Mongo aborts with a transient label (WriteConflict etc.). */
    @Bean
    fun transactionTemplate(transactionManager: MongoTransactionManager): TransactionTemplate =
        RetryingTransactionTemplate(transactionManager)
}

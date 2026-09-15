package com.bridge.debate.shared.config

import com.mongodb.MongoException
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate

/**
 * MongoDB aborts a multi-document transaction with a TransientTransactionError label (e.g.
 * WriteConflict, code 112) whenever two transactions touch the same documents — its contract is
 * "just retry". Concurrent writers here are real (negotiation turns, outbox fan-out, audit chain
 * appends for the same room), so the shared template retries a few times with a short backoff
 * before giving up. Callback bodies are retry-safe: nothing from an aborted attempt commits.
 */
class RetryingTransactionTemplate(transactionManager: MongoTransactionManager) :
    TransactionTemplate(transactionManager) {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun <T> execute(action: TransactionCallback<T>): T? {
        var attempt = 0
        while (true) {
            try {
                return super.execute(action)
            } catch (e: RuntimeException) {
                attempt++
                if (attempt > MAX_RETRIES || !isTransient(e)) throw e
                log.info("Transient Mongo transaction error - retrying (attempt {}/{})", attempt, MAX_RETRIES)
                Thread.sleep(BASE_BACKOFF_MS * attempt)
            }
        }
    }

    private fun isTransient(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is MongoException && current.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_BACKOFF_MS = 50L
    }
}

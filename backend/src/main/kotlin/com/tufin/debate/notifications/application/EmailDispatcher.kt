package com.tufin.debate.notifications.application

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Reactive rule for outbound mail: NOTHING sends on a request thread. Every email is dispatched
 * to the dedicated notification executor; delivery failures are logged and never propagate —
 * the domain action (invitation, decision, document) has already been committed.
 */
@Component
class EmailDispatcher(private val emailSender: EmailSender) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Async("notificationExecutor")
    fun dispatch(to: String, subject: String, htmlBody: String, textBody: String, attachment: EmailAttachment? = null) {
        try {
            emailSender.send(to, subject, htmlBody, textBody, attachment)
        } catch (e: Exception) {
            log.warn("Async email delivery failed (the triggering action already succeeded)", e)
        }
    }
}

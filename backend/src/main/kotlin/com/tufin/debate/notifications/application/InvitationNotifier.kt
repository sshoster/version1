package com.tufin.debate.notifications.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Best-effort invitation email delivery: a mail failure must never fail (or roll back) the
 * invitation itself — the owner always keeps the shareable link. Called AFTER the invitation
 * transaction commits; the accept URL exists only in memory (only the token hash is persisted).
 * Delivery is dispatched asynchronously — the request thread never waits on the mail server.
 */
@Service
class InvitationNotifier(private val emailDispatcher: EmailDispatcher) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Returns true when an email was queued for delivery (async, best-effort). */
    fun invitationCreated(
        email: String?,
        recipientName: String?,
        inviterName: String,
        roomTitle: String,
        role: String,
        acceptUrl: String,
        expiresAt: Instant,
    ): Boolean {
        if (email.isNullOrBlank()) return false
        return try {
            val message = InvitationEmailComposer.compose(inviterName, roomTitle, role, acceptUrl, expiresAt, recipientName = recipientName)
            emailDispatcher.dispatch(email, message.subject, message.html, message.text)
            true
        } catch (e: Exception) {
            log.warn("Could not compose the invitation email (invitation still valid, link available in app)", e)
            false
        }
    }
}

package com.tufin.debate.notifications.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Best-effort invitation email delivery: a mail failure must never fail (or roll back) the
 * invitation itself — the owner always keeps the shareable link. Called AFTER the invitation
 * transaction commits; the accept URL exists only in memory (only the token hash is persisted).
 */
@Service
class InvitationNotifier(private val emailSender: EmailSender) {

    private val log = LoggerFactory.getLogger(javaClass)

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
            emailSender.send(email, message.subject, message.html, message.text)
            true
        } catch (e: Exception) {
            // Full exception (incl. the SMTP server's response, e.g. Gmail 535-5.7.8) for
            // diagnosis. The accept URL is never part of the exception, so this stays token-free.
            log.warn("Could not send invitation email (invitation still valid, link available in app)", e)
            false
        }
    }
}

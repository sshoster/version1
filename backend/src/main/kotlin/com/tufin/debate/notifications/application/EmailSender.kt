package com.tufin.debate.notifications.application

/** Outbound email port. SMTP (Gmail) implementation is opt-in; the default implementation logs. */
interface EmailSender {
    fun send(to: String, subject: String, htmlBody: String, textBody: String)
}

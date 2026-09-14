package com.tufin.debate.notifications.application

/** A file carried with an email (e.g. the agreement draft as a document). */
class EmailAttachment(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

/** Outbound email port. SMTP (Gmail) implementation is opt-in; the default implementation logs. */
interface EmailSender {
    fun send(to: String, subject: String, htmlBody: String, textBody: String, attachment: EmailAttachment? = null)
}

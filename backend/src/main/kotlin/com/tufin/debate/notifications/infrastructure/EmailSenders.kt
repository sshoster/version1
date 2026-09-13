package com.tufin.debate.notifications.infrastructure

import com.tufin.debate.notifications.application.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/**
 * Sends real email through the configured SMTP server (Gmail by default — see .env.example for
 * the app-password setup). Active only when app.mail.enabled=true.
 */
@Component
@ConditionalOnProperty("app.mail.enabled", havingValue = "true")
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
    @param:Value("\${app.mail.from}") private val from: String,
) : EmailSender {

    init {
        require(from.isNotBlank()) { "app.mail.from (MAIL_FROM or SMTP_USERNAME) is required when mail is enabled" }
    }

    override fun send(to: String, subject: String, htmlBody: String, textBody: String) {
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")
        helper.setFrom(from)
        helper.setTo(to)
        helper.setSubject(subject)
        helper.setText(textBody, htmlBody)
        mailSender.send(message)
    }
}

/** Default when mail is disabled: records that an email would have been sent — never its content. */
@Component
@ConditionalOnProperty("app.mail.enabled", havingValue = "false", matchIfMissing = true)
class LoggingEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(to: String, subject: String, htmlBody: String, textBody: String) {
        log.info("Email sending is disabled (app.mail.enabled=false); would have sent \"{}\" to {}", subject, mask(to))
    }

    private fun mask(email: String): String {
        val at = email.indexOf('@')
        if (at <= 1) return "***"
        return email.first() + "***" + email.substring(at)
    }
}

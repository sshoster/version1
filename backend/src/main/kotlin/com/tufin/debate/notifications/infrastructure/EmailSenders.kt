package com.tufin.debate.notifications.infrastructure

import com.tufin.debate.notifications.application.EmailAttachment
import com.tufin.debate.notifications.application.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.web.client.RestClient

/** Selects the outbound email implementation: disabled → log-only; smtp (default) or brevo. */
@Configuration
class EmailSenderConfig {

    @Bean
    fun emailSender(
        @Value("\${app.mail.enabled:false}") enabled: Boolean,
        @Value("\${app.mail.provider:smtp}") provider: String,
        @Value("\${app.mail.from:}") from: String,
        @Value("\${app.mail.brevo.api-key:}") brevoApiKey: String,
        mailSender: org.springframework.beans.factory.ObjectProvider<JavaMailSender>,
    ): EmailSender = when {
        !enabled -> LoggingEmailSender()
        provider.equals("brevo", ignoreCase = true) -> BrevoEmailSender(brevoApiKey, from)
        else -> SmtpEmailSender(mailSender.getObject(), from)
    }
}

/**
 * Sends real email through the configured SMTP server (Gmail by default — see .env.example for
 * the app-password setup). Selected via app.mail.provider=smtp. Note: some hosts (e.g. Render)
 * block outbound SMTP entirely — use the Brevo HTTP provider there.
 */
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
    private val from: String,
) : EmailSender {

    init {
        require(from.isNotBlank()) { "app.mail.from (MAIL_FROM or SMTP_USERNAME) is required when mail is enabled" }
    }

    override fun send(to: String, subject: String, htmlBody: String, textBody: String, attachment: EmailAttachment?) {
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")
        helper.setFrom(from)
        helper.setTo(to)
        helper.setSubject(subject)
        helper.setText(textBody, htmlBody)
        attachment?.let {
            helper.addAttachment(it.filename, org.springframework.core.io.ByteArrayResource(it.bytes), it.contentType)
        }
        mailSender.send(message)
    }
}

/**
 * HTTPS email via Brevo (https://developers.brevo.com) — for hosts that block SMTP ports.
 * The sender address must be verified in the Brevo account (Senders & Domains).
 */
class BrevoEmailSender(
    apiKey: String,
    private val from: String,
    baseUrl: String = "https://api.brevo.com",
) : EmailSender {

    init {
        require(apiKey.isNotBlank()) { "BREVO_API_KEY is required when app.mail.provider=brevo" }
        require(from.isNotBlank()) { "MAIL_FROM is required when mail is enabled" }
    }

    private val client: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("api-key", apiKey)
        .requestFactory(
            org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(java.time.Duration.ofSeconds(10))
                setReadTimeout(java.time.Duration.ofSeconds(20))
            },
        )
        .build()

    override fun send(to: String, subject: String, htmlBody: String, textBody: String, attachment: EmailAttachment?) {
        val payload = mutableMapOf<String, Any>(
            "sender" to mapOf("email" to from, "name" to "Bridge AI"),
            "to" to listOf(mapOf("email" to to)),
            "subject" to subject,
            "htmlContent" to htmlBody,
            "textContent" to textBody,
        )
        attachment?.let {
            payload["attachment"] = listOf(
                mapOf("name" to it.filename, "content" to java.util.Base64.getEncoder().encodeToString(it.bytes)),
            )
        }
        client.post()
            .uri("/v3/smtp/email")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .toBodilessEntity()
    }
}

/** Default when mail is disabled: records that an email would have been sent — never its content. */
class LoggingEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(to: String, subject: String, htmlBody: String, textBody: String, attachment: EmailAttachment?) {
        log.info(
            "Email sending is disabled (app.mail.enabled=false); would have sent \"{}\" to {}{}",
            subject, mask(to), if (attachment != null) " with attachment ${attachment.filename}" else "",
        )
    }

    private fun mask(email: String): String {
        val at = email.indexOf('@')
        if (at <= 1) return "***"
        return email.first() + "***" + email.substring(at)
    }
}

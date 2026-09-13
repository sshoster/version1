package com.tufin.debate.identity.application

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

@ConfigurationProperties(prefix = "app.security")
data class SecurityProperties(
    /** HMAC secret for local JWT mode; required outside the local/test profiles. */
    var jwtSecret: String = "",
    var accessTokenTtl: Duration = Duration.ofMinutes(15),
    var refreshTokenTtl: Duration = Duration.ofDays(14),
)

/**
 * Resolves the JWT signing secret. Secrets are never hardcoded: outside the `local` and `test`
 * profiles a missing JWT_SECRET fails startup; in local/test a random per-process secret is
 * generated (sessions do not survive restarts, which is acceptable for development).
 */
@Component
class JwtSecretProvider(props: SecurityProperties, environment: Environment) {

    private val log = LoggerFactory.getLogger(javaClass)

    val secretBytes: ByteArray = run {
        val configured = props.jwtSecret.trim()
        if (configured.isNotEmpty()) {
            val bytes = configured.toByteArray(Charsets.UTF_8)
            require(bytes.size >= 32) { "JWT_SECRET must be at least 32 bytes long" }
            bytes
        } else {
            // acceptsProfiles (not activeProfiles) so a profile applied via spring.profiles.default
            // — as bootRun does — counts too.
            check(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "test"))) {
                """
                |No JWT_SECRET configured and the active profile (${environment.activeProfiles.joinToString()}) is not local/test.
                |Fix one of:
                |  1. Set the JWT_SECRET environment variable (at least 32 characters, see .env.example).
                |  2. For development, use the local profile (a random dev secret is generated) —
                |     launching with NO profile does this automatically via spring.profiles.default.
                """.trimMargin()
            }
            log.warn("No JWT_SECRET configured — generated a random development secret (tokens will not survive restart)")
            ByteArray(48).also { SecureRandom().nextBytes(it) }
        }
    }

    val secretBase64: String get() = Base64.getEncoder().encodeToString(secretBytes)
}

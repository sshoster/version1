package com.bridge.debate.identity.infrastructure

import com.bridge.debate.identity.application.GoogleIdVerifier
import com.bridge.debate.identity.application.GoogleIdentity
import com.bridge.debate.identity.application.SecurityProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Verifies Google ID tokens against Google's tokeninfo endpoint, which checks the signature and
 * expiry server-side. We additionally require that the token was minted for OUR OAuth client
 * (audience check) — a valid Google token issued to some other app must not sign anyone in here.
 */
@Component
@Profile("!test")
class GoogleTokenInfoVerifier(private val props: SecurityProperties) : GoogleIdVerifier {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient = RestClient.builder()
        .baseUrl("https://oauth2.googleapis.com")
        .build()

    override fun verify(idToken: String): GoogleIdentity? {
        val clientId = props.googleClientId.trim()
        if (clientId.isEmpty()) {
            log.warn("Google sign-in attempted but GOOGLE_CLIENT_ID is not configured")
            return null
        }
        return try {
            val claims = client.get()
                .uri { builder -> builder.path("/tokeninfo").queryParam("id_token", idToken).build() }
                .retrieve()
                .body(Map::class.java) ?: return null
            if (claims["aud"] != clientId) {
                log.warn("Google ID token rejected: audience mismatch")
                return null
            }
            val email = claims["email"] as? String ?: return null
            GoogleIdentity(
                email = email,
                displayName = (claims["name"] as? String)?.takeIf { it.isNotBlank() } ?: email.substringBefore('@'),
                emailVerified = claims["email_verified"] == "true",
            )
        } catch (e: Exception) {
            // Invalid/expired tokens come back as HTTP 400 — same outcome as any transport error.
            log.info("Google ID token verification failed: {}", e.message)
            null
        }
    }
}

/**
 * Deterministic verifier for integration tests: accepts tokens shaped "GOOGLE:email:Display Name"
 * (append ":unverified" to simulate an unverified email); everything else is invalid.
 */
@Component
@Profile("test")
class FakeGoogleIdVerifier : GoogleIdVerifier {
    override fun verify(idToken: String): GoogleIdentity? {
        val parts = idToken.split(":")
        if (parts.size < 3 || parts[0] != "GOOGLE") return null
        return GoogleIdentity(
            email = parts[1],
            displayName = parts[2],
            emailVerified = parts.getOrNull(3) != "unverified",
        )
    }
}

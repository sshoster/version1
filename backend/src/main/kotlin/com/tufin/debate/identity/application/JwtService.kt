package com.tufin.debate.identity.application

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date

/** The authenticated human principal. AI agents never receive a principal (trust-model §2). */
data class AuthenticatedUser(
    val userId: String,
    val email: String,
    val displayName: String,
)

@Service
class JwtService(
    secretProvider: JwtSecretProvider,
    private val props: SecurityProperties,
) {
    private val key = Keys.hmacShaKeyFor(secretProvider.secretBytes)

    fun issueAccessToken(user: AuthenticatedUser, now: Instant = Instant.now()): String =
        Jwts.builder()
            .subject(user.userId)
            .claim("email", user.email)
            .claim("displayName", user.displayName)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(props.accessTokenTtl)))
            .signWith(key)
            .compact()

    /** Returns the principal for a valid, unexpired token; null otherwise (never throws). */
    fun parse(token: String): AuthenticatedUser? =
        try {
            val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
            AuthenticatedUser(
                userId = claims.subject,
                email = claims["email"] as? String ?: "",
                displayName = claims["displayName"] as? String ?: "",
            )
        } catch (e: Exception) {
            null
        }
}

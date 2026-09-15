package com.bridge.debate.identity.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document("users")
class User(
    @Id val id: String,
    val email: String,
    var displayName: String,
    var passwordHash: String,
    /** Set by a super admin; a suspended account cannot sign in or refresh its session. */
    var suspendedAt: Instant? = null,
    /**
     * Soft delete: sign-in is impossible, but the document (and its id) stays so the person's
     * room participations survive — registering again with the same email revives THIS identity
     * and every meeting reappears as it was.
     */
    var deletedAt: Instant? = null,
    var timezone: String = "Asia/Jerusalem",
    var avatarKey: String? = null,
    var avatarContentType: String? = null,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
)

enum class RefreshTokenStatus { ACTIVE, USED, REVOKED }

/**
 * Rotating refresh tokens: each refresh marks the presented token USED and issues a new one in the
 * same family. Presenting a USED/REVOKED token is treated as theft and revokes the whole family
 * (docs/security.md T8). Only a SHA-256 hash of the token is stored.
 */
@Document("refresh_tokens")
class RefreshToken(
    @Id val id: String,
    val userId: String,
    val familyId: String,
    val tokenHash: String,
    var status: RefreshTokenStatus = RefreshTokenStatus.ACTIVE,
    val expiresAt: Instant,
    /** TTL cleanup timestamp (expiry + retention buffer); see migration V001. */
    val purgeAt: Instant,
    val createdAt: Instant,
)

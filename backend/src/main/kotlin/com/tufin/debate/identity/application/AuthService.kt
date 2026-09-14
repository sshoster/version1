package com.tufin.debate.identity.application

import com.tufin.debate.identity.domain.RefreshToken
import com.tufin.debate.identity.domain.RefreshTokenStatus
import com.tufin.debate.identity.domain.User
import com.tufin.debate.identity.infrastructure.RefreshTokenRepository
import com.tufin.debate.identity.infrastructure.UserRepository
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.errors.ConflictException
import com.tufin.debate.shared.errors.UnauthorizedException
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val user: AuthenticatedUser,
)

@Service
class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val props: SecurityProperties,
) {
    companion object {
        /** Retention window before TTL cleanup removes expired refresh-token records. */
        private val PURGE_BUFFER: Duration = Duration.ofDays(30)
        private val random = SecureRandom()
        private val hex = HexFormat.of()
    }

    fun register(email: String, displayName: String, password: String): TokenPair {
        val normalizedEmail = email.trim().lowercase()
        val user = User(
            id = Ids.newId(),
            email = normalizedEmail,
            displayName = displayName.trim(),
            passwordHash = passwordEncoder.encode(password),
            createdAt = Instant.now(),
        )
        try {
            users.insert(user)
        } catch (e: DuplicateKeyException) {
            throw ConflictException("This email address is already registered")
        }
        return issueTokens(user)
    }

    fun login(email: String, password: String): TokenPair {
        val user = users.findByEmail(email.trim().lowercase())
        // Same generic error for unknown email and wrong password (no account enumeration).
        if (user == null || !passwordEncoder.matches(password, user.passwordHash)) {
            throw UnauthorizedException("The email or password is incorrect")
        }
        return issueTokens(user)
    }

    fun refresh(rawRefreshToken: String): TokenPair {
        val presented = refreshTokens.findByTokenHash(hash(rawRefreshToken))
            ?: throw UnauthorizedException("Please sign in again")

        if (presented.status != RefreshTokenStatus.ACTIVE || presented.expiresAt.isBefore(Instant.now())) {
            // Reuse of a rotated/revoked token — treat as theft and revoke the whole family.
            revokeFamily(presented.familyId)
            throw UnauthorizedException("Please sign in again")
        }

        presented.status = RefreshTokenStatus.USED
        refreshTokens.save(presented)

        val user = users.findById(presented.userId)
            .orElseThrow { UnauthorizedException("Please sign in again") }
        return issueTokens(user, familyId = presented.familyId)
    }

    fun findMe(userId: String): User? = users.findById(userId).orElse(null)

    /**
     * Account-level profile edit. Room display names are deliberately untouched — they are
     * per-room snapshots chosen at invitation/approval time (trust-model invariant).
     */
    fun updateProfile(userId: String, displayName: String): User {
        val user = users.findById(userId).orElseThrow { UnauthorizedException("Please sign in again") }
        user.displayName = displayName.trim()
        return users.save(user)
    }

    private fun issueTokens(user: User, familyId: String = Ids.newId()): TokenPair {
        val principal = AuthenticatedUser(user.id, user.email, user.displayName)
        val rawRefresh = newRefreshTokenValue()
        val now = Instant.now()
        refreshTokens.insert(
            RefreshToken(
                id = Ids.newId(),
                userId = user.id,
                familyId = familyId,
                tokenHash = hash(rawRefresh),
                expiresAt = now.plus(props.refreshTokenTtl),
                purgeAt = now.plus(props.refreshTokenTtl).plus(PURGE_BUFFER),
                createdAt = now,
            ),
        )
        return TokenPair(
            accessToken = jwtService.issueAccessToken(principal),
            refreshToken = rawRefresh,
            user = principal,
        )
    }

    private fun revokeFamily(familyId: String) {
        val family = refreshTokens.findByFamilyId(familyId)
        family.forEach { it.status = RefreshTokenStatus.REVOKED }
        refreshTokens.saveAll(family)
    }

    private fun newRefreshTokenValue(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return hex.formatHex(digest.digest(raw.toByteArray(StandardCharsets.UTF_8)))
    }
}

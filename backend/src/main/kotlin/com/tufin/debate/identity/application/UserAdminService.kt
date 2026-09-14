package com.tufin.debate.identity.application

import com.tufin.debate.identity.domain.User
import com.tufin.debate.identity.infrastructure.RefreshTokenRepository
import com.tufin.debate.identity.infrastructure.UserRepository
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.errors.ConflictException
import com.tufin.debate.shared.errors.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Sort
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Instant

data class AdminUserView(
    val id: String,
    val email: String,
    val displayName: String,
    val suspended: Boolean,
    val createdAt: Instant,
)

/**
 * Super-admin user management. Who counts as a super admin is an env-configured email
 * allowlist (SUPER_ADMIN_EMAILS) — nothing in the database can grant it, so a compromised
 * account cannot promote itself. Admins cannot suspend or delete their own account.
 */
@Service
class UserAdminService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val props: SecurityProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun isSuperAdmin(email: String): Boolean =
        props.superAdminEmails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .contains(email.trim().lowercase())

    fun list(query: String?): List<AdminUserView> {
        val needle = query?.trim()?.lowercase().orEmpty()
        return users.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
            .asSequence()
            .filter {
                needle.isEmpty() || it.email.lowercase().contains(needle) ||
                    it.displayName.lowercase().contains(needle)
            }
            .take(200)
            .map { it.toView() }
            .toList()
    }

    fun create(email: String, displayName: String, password: String): AdminUserView {
        val user = User(
            id = Ids.newId(),
            email = email.trim().lowercase(),
            displayName = displayName.trim(),
            passwordHash = passwordEncoder.encode(password),
            createdAt = Instant.now(),
        )
        try {
            users.insert(user)
        } catch (e: DuplicateKeyException) {
            throw ConflictException("This email address is already registered")
        }
        return user.toView()
    }

    fun update(userId: String, displayName: String?, newPassword: String?): AdminUserView {
        val user = find(userId)
        displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { user.displayName = it.take(80) }
        newPassword?.takeIf { it.isNotEmpty() }?.let {
            user.passwordHash = passwordEncoder.encode(it)
            refreshTokens.deleteByUserId(userId) // password reset ends existing sessions
        }
        return users.save(user).toView()
    }

    fun suspend(userId: String, actorUserId: String): AdminUserView {
        if (userId == actorUserId) throw ConflictException("You cannot suspend your own account")
        val user = find(userId)
        if (user.suspendedAt == null) {
            user.suspendedAt = Instant.now()
            users.save(user)
            refreshTokens.deleteByUserId(userId) // end sessions now, not at token expiry
            log.info("User {} suspended by admin {}", userId, actorUserId)
        }
        return user.toView()
    }

    fun reactivate(userId: String): AdminUserView {
        val user = find(userId)
        user.suspendedAt = null
        return users.save(user).toView()
    }

    /**
     * Deletes the ACCOUNT: sign-in becomes impossible and all sessions end. Room content the
     * person authored stays — messages, approvals, and the hash-chained audit are immutable
     * records of what the other participants relied on (trust-model invariant).
     */
    fun delete(userId: String, actorUserId: String) {
        if (userId == actorUserId) throw ConflictException("You cannot delete your own account")
        val user = find(userId)
        refreshTokens.deleteByUserId(userId)
        users.delete(user)
        log.info("User {} deleted by admin {}", userId, actorUserId)
    }

    private fun find(userId: String): User =
        users.findById(userId).orElseThrow { NotFoundException("This user was not found") }

    private fun User.toView() = AdminUserView(id, email, displayName, suspendedAt != null, createdAt)
}

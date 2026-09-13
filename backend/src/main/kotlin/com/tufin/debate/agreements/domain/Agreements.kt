package com.tufin.debate.agreements.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

enum class ProposalStatus { OPEN, AGREED, CLOSED }

@Document("proposals")
class Proposal(
    @Id val id: String,
    val roomId: String,
    val createdByUserId: String,
    var currentVersion: Int,
    var status: ProposalStatus = ProposalStatus.OPEN,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
    var updatedAt: Instant,
    @Version var version: Long? = null,
)

/** Immutable — every revision is a new document; approvals bind to (version, contentHash). */
@Document("proposal_versions")
class ProposalVersion(
    @Id val id: String,
    val proposalId: String,
    val roomId: String,
    val version: Int,
    val title: String,
    val terms: List<String>,
    val assumptions: List<String>,
    val contentHash: String,
    val createdByUserId: String,
    val createdByDisplayName: String,
    /** Set when the version originates from a negotiation run's recommendation. */
    val sourceRunId: String? = null,
    val createdAt: Instant,
)

enum class ApprovalRequestStatus { PENDING, APPROVED, REJECTED, SUPERSEDED }

/**
 * Asks every required party to independently approve ONE exact proposal version. Any revision
 * supersedes the pending request and voids its approvals (trust-model invariant 9).
 */
@Document("approval_requests")
class ApprovalRequest(
    @Id val id: String,
    val roomId: String,
    val proposalId: String,
    val proposalVersion: Int,
    val contentHash: String,
    val requestedByUserId: String,
    /** Parties whose independent approval is required, frozen at request time. */
    val requiredUserIds: List<String>,
    var status: ApprovalRequestStatus = ApprovalRequestStatus.PENDING,
    val createdAt: Instant,
    var updatedAt: Instant,
)

enum class ApprovalDecision { APPROVED, REJECTED, CHANGES_REQUESTED }

/** One party's decision. Only an authenticated human principal can create one (invariant 5). */
@Document("approvals")
class Approval(
    @Id val id: String,
    val approvalRequestId: String,
    val roomId: String,
    val proposalId: String,
    val proposalVersion: Int,
    val contentHash: String,
    val userId: String,
    val userDisplayName: String,
    val decision: ApprovalDecision,
    val comment: String? = null,
    val createdAt: Instant,
)

object ProposalContentHash {
    private val hex = HexFormat.of()

    fun compute(title: String, terms: List<String>, assumptions: List<String>): String {
        val canonical = listOf(title, terms.joinToString(""), assumptions.joinToString(""))
            .joinToString("")
        val digest = MessageDigest.getInstance("SHA-256")
        return hex.formatHex(digest.digest(canonical.toByteArray(StandardCharsets.UTF_8)))
    }
}

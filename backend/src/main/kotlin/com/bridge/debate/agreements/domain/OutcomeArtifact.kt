package com.bridge.debate.agreements.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

enum class OutcomeType { DISCUSSION_SUMMARY, APPROVED_UNDERSTANDINGS, AGREEMENT_DRAFT }

/** One party's recorded decision inside an understandings block (who, what, when, which version). */
data class ApprovalRecord(
    val userId: String,
    val displayName: String,
    val approvedAt: Instant,
    val proposalVersion: Int,
    val contentHash: String,
)

/** An agreed proposal frozen into the understandings artifact. */
data class UnderstandingBlock(
    val proposalId: String,
    val title: String,
    val terms: List<String>,
    val assumptions: List<String>,
    val proposalVersion: Int,
    val contentHash: String,
    val approvals: List<ApprovalRecord>,
)

/**
 * The three outcome levels (design doc §7). Immutable versioned documents; labels are server-set
 * flags the UI must render (test 19):
 *  - DISCUSSION_SUMMARY: aiGenerated, never presented as an approved agreement.
 *  - APPROVED_UNDERSTANDINGS: only all-party-approved terms, with per-party approval records.
 *  - AGREEMENT_DRAFT: aiGenerated + draftOnly + notLegalAdvice.
 */
@Document("outcome_artifacts")
class OutcomeArtifact(
    @Id val id: String,
    val roomId: String,
    val type: OutcomeType,
    val version: Int,
    val aiGenerated: Boolean,
    val draftOnly: Boolean,
    val notLegalAdvice: Boolean,
    /** Free text for the AI-generated artifacts. */
    val text: String? = null,
    /** Structured content for APPROVED_UNDERSTANDINGS. */
    val understandings: List<UnderstandingBlock>? = null,
    val generatedByUserId: String,
    val llmProvider: String? = null,
    val llmModel: String? = null,
    val promptTemplateId: String? = null,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
)

interface OutcomeArtifactRepository : MongoRepository<OutcomeArtifact, String> {
    fun findByRoomIdOrderByCreatedAtDesc(roomId: String): List<OutcomeArtifact>
    fun findTopByRoomIdAndTypeOrderByVersionDesc(roomId: String, type: OutcomeType): OutcomeArtifact?
}

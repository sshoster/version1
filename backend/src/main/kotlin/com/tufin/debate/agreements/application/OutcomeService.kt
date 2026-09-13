package com.tufin.debate.agreements.application

import com.tufin.debate.agreements.domain.ApprovalDecision
import com.tufin.debate.agreements.domain.ApprovalRecord
import com.tufin.debate.agreements.domain.OutcomeArtifact
import com.tufin.debate.agreements.domain.OutcomeArtifactRepository
import com.tufin.debate.agreements.domain.OutcomeType
import com.tufin.debate.agreements.domain.ProposalStatus
import com.tufin.debate.agreements.domain.UnderstandingBlock
import com.tufin.debate.agreements.infrastructure.ApprovalRepository
import com.tufin.debate.agreements.infrastructure.ApprovalRequestRepository
import com.tufin.debate.agreements.infrastructure.ProposalRepository
import com.tufin.debate.agreements.infrastructure.ProposalVersionRepository
import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.discussion.application.RoomDirectory
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.llm.application.LlmProvider
import com.tufin.debate.llm.application.LlmRequest
import com.tufin.debate.messaging.application.SharedContextReader
import com.tufin.debate.participants.application.ParticipantDirectory
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.permissions.application.PermissionsService
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.errors.ConflictException
import com.tufin.debate.shared.errors.NotFoundException
import com.tufin.debate.shared.outbox.OutboxService
import kotlinx.coroutines.runBlocking
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The three outcome levels (design doc §7). The summary and draft are AI-generated from material
 * EVERY party can see (never one side's private or narrower-scoped content); the understandings
 * are assembled deterministically from all-party-approved proposal versions — no AI involved.
 */
@Service
class OutcomeService(
    private val outcomes: OutcomeArtifactRepository,
    private val proposals: ProposalRepository,
    private val proposalVersions: ProposalVersionRepository,
    private val approvalRequests: ApprovalRequestRepository,
    private val approvals: ApprovalRepository,
    private val permissions: PermissionsService,
    private val directory: ParticipantDirectory,
    private val rooms: RoomDirectory,
    private val sharedContext: SharedContextReader,
    private val llmProvider: LlmProvider,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
) {
    companion object {
        const val SUMMARY_TEMPLATE = "summary/v1"
        const val AGREEMENT_TEMPLATE = "agreement/v1"
    }

    private val summaryPrompt: String by lazy {
        ClassPathResource("prompts/summary/v1.md").inputStream.readBytes().toString(Charsets.UTF_8)
    }
    private val agreementPrompt: String by lazy {
        ClassPathResource("prompts/agreement/v1.md").inputStream.readBytes().toString(Charsets.UTF_8)
    }

    fun list(roomId: String, actor: AuthenticatedUser): List<OutcomeArtifact> {
        requireReader(roomId, actor)
        // Latest version of each type.
        return OutcomeType.entries.mapNotNull { outcomes.findTopByRoomIdAndTypeOrderByVersionDesc(roomId, it) }
    }

    @Transactional
    fun generateSummary(roomId: String, actor: AuthenticatedUser): OutcomeArtifact {
        requireParty(roomId, actor)
        val room = rooms.find(roomId) ?: throw NotFoundException("This discussion was not found")
        val facts = sharedContext.factsVisibleToAll(roomId, partyUserIds(roomId))
        val proposalLines = agreedAndOpenProposalLines(roomId)
        if (facts.isEmpty() && proposalLines.isEmpty()) {
            throw ConflictException("There is nothing shared to summarize yet")
        }

        val material = buildString {
            appendLine("DISCUSSION_TITLE: ${room.title}")
            appendLine("OBJECTIVE: ${room.objective ?: "(none)"}")
            appendLine()
            appendLine("SHARED_STATEMENTS (author | text):")
            facts.forEach { appendLine("  - ${it.authorDisplayName} | ${it.text.replace('\n', ' ')}") }
            appendLine()
            appendLine("PROPOSALS:")
            if (proposalLines.isEmpty()) appendLine("  (none)")
            proposalLines.forEach { appendLine("  - $it") }
        }
        val response = runBlocking {
            llmProvider.generate(
                LlmRequest(
                    templateId = SUMMARY_TEMPLATE,
                    system = summaryPrompt.replace("{{roomTitle}}", room.title),
                    user = material,
                    maxOutputTokens = 1500,
                ),
            )
        }

        return persist(
            roomId, actor, OutcomeType.DISCUSSION_SUMMARY,
            aiGenerated = true, draftOnly = false, notLegalAdvice = true,
            text = response.text, understandings = null,
            provider = response.provider, model = response.model, template = SUMMARY_TEMPLATE,
        )
    }

    @Transactional
    fun generateApprovedUnderstandings(roomId: String, actor: AuthenticatedUser): OutcomeArtifact {
        requireParty(roomId, actor)
        val blocks = understandingBlocks(roomId)
        if (blocks.isEmpty()) {
            throw ConflictException("There are no proposals approved by all parties yet")
        }
        return persist(
            roomId, actor, OutcomeType.APPROVED_UNDERSTANDINGS,
            aiGenerated = false, draftOnly = false, notLegalAdvice = true,
            text = null, understandings = blocks,
            provider = null, model = null, template = null,
        )
    }

    @Transactional
    fun generateAgreementDraft(roomId: String, actor: AuthenticatedUser): OutcomeArtifact {
        requireParty(roomId, actor)
        val room = rooms.find(roomId) ?: throw NotFoundException("This discussion was not found")
        val blocks = understandingBlocks(roomId)
        if (blocks.isEmpty()) {
            throw ConflictException("An agreement draft needs at least one proposal approved by all parties")
        }
        val facts = sharedContext.factsVisibleToAll(roomId, partyUserIds(roomId))
        val partyNames = directory.activeParticipants(roomId)
            .filter { ParticipantRole.PARTY in it.roles || ParticipantRole.OWNER in it.roles }
            .joinToString(", ") { it.displayName }

        val material = buildString {
            appendLine("DISCUSSION_TITLE: ${room.title}")
            appendLine("PARTIES: $partyNames")
            appendLine()
            appendLine("APPROVED_UNDERSTANDINGS:")
            blocks.forEach { block ->
                appendLine("  ${block.title} (version ${block.proposalVersion}):")
                block.terms.forEach { appendLine("    - term: $it") }
                block.assumptions.forEach { appendLine("    - assumption: $it") }
            }
            appendLine()
            appendLine("SHARED_FACTS:")
            facts.forEach { appendLine("  - ${it.authorDisplayName} | ${it.text.replace('\n', ' ')}") }
        }
        val response = runBlocking {
            llmProvider.generate(
                LlmRequest(
                    templateId = AGREEMENT_TEMPLATE,
                    system = agreementPrompt.replace("{{roomTitle}}", room.title),
                    user = material,
                    maxOutputTokens = 2000,
                ),
            )
        }

        return persist(
            roomId, actor, OutcomeType.AGREEMENT_DRAFT,
            aiGenerated = true, draftOnly = true, notLegalAdvice = true,
            text = response.text, understandings = blocks,
            provider = response.provider, model = response.model, template = AGREEMENT_TEMPLATE,
        )
    }

    // ---------------- helpers ----------------

    /** Only terms explicitly approved by EVERY required party (design doc §7.2). */
    private fun understandingBlocks(roomId: String): List<UnderstandingBlock> =
        proposals.findByRoomIdOrderByCreatedAtDesc(roomId)
            .filter { it.status == ProposalStatus.AGREED }
            .mapNotNull { proposal ->
                val request = approvalRequests
                    .findByProposalIdAndStatus(proposal.id, com.tufin.debate.agreements.domain.ApprovalRequestStatus.APPROVED)
                    .maxByOrNull { it.createdAt } ?: return@mapNotNull null
                val version = proposalVersions.findByProposalIdAndRoomIdAndVersion(proposal.id, roomId, request.proposalVersion)
                    ?: return@mapNotNull null
                val records = approvals.findByApprovalRequestId(request.id)
                    .filter { it.decision == ApprovalDecision.APPROVED }
                    .map { ApprovalRecord(it.userId, it.userDisplayName, it.createdAt, it.proposalVersion, it.contentHash) }
                UnderstandingBlock(
                    proposalId = proposal.id,
                    title = version.title,
                    terms = version.terms,
                    assumptions = version.assumptions,
                    proposalVersion = version.version,
                    contentHash = version.contentHash,
                    approvals = records,
                )
            }

    private fun agreedAndOpenProposalLines(roomId: String): List<String> =
        proposals.findByRoomIdOrderByCreatedAtDesc(roomId).mapNotNull { proposal ->
            val version = proposalVersions.findByProposalIdAndRoomIdAndVersion(proposal.id, roomId, proposal.currentVersion)
                ?: return@mapNotNull null
            "${version.title} [${proposal.status}] terms: ${version.terms.joinToString("; ")}"
        }

    private fun persist(
        roomId: String,
        actor: AuthenticatedUser,
        type: OutcomeType,
        aiGenerated: Boolean,
        draftOnly: Boolean,
        notLegalAdvice: Boolean,
        text: String?,
        understandings: List<UnderstandingBlock>?,
        provider: String?,
        model: String?,
        template: String?,
    ): OutcomeArtifact {
        val nextVersion = (outcomes.findTopByRoomIdAndTypeOrderByVersionDesc(roomId, type)?.version ?: 0) + 1
        val artifact = outcomes.insert(
            OutcomeArtifact(
                id = Ids.newId(),
                roomId = roomId,
                type = type,
                version = nextVersion,
                aiGenerated = aiGenerated,
                draftOnly = draftOnly,
                notLegalAdvice = notLegalAdvice,
                text = text,
                understandings = understandings,
                generatedByUserId = actor.userId,
                llmProvider = provider,
                llmModel = model,
                promptTemplateId = template,
                createdAt = Instant.now(),
            ),
        )
        auditService.append(
            roomId,
            if (aiGenerated) ActorType.AI else ActorType.SYSTEM,
            actor.userId,
            "OUTCOME_CREATED", "OutcomeArtifact", artifact.id,
            metadata = mapOf("type" to type.name, "version" to nextVersion.toString()),
        )
        outboxService.enqueue(
            roomId, "OUTCOME_CREATED",
            mapOf("resourceId" to artifact.id, "resourceVersion" to nextVersion, "actorUserId" to actor.userId),
        )
        return artifact
    }

    private fun requireParty(roomId: String, actor: AuthenticatedUser) {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
    }

    /** Parties and advisors may read outcomes (a draft is meant to be shown to an advisor). */
    private fun requireReader(roomId: String, actor: AuthenticatedUser) {
        permissions.requireRole(
            roomId, actor.userId,
            ParticipantRole.PARTY, ParticipantRole.OWNER, ParticipantRole.ADVISOR,
        )
    }

    private fun partyUserIds(roomId: String): List<String> =
        directory.activeParticipants(roomId)
            .filter { ParticipantRole.PARTY in it.roles || ParticipantRole.OWNER in it.roles }
            .map { it.userId }
            .distinct()
}

package com.tufin.debate.agreements.application

import com.tufin.debate.agreements.domain.Approval
import com.tufin.debate.agreements.domain.ApprovalDecision
import com.tufin.debate.agreements.domain.ApprovalRequest
import com.tufin.debate.agreements.domain.ApprovalRequestStatus
import com.tufin.debate.agreements.domain.Proposal
import com.tufin.debate.agreements.domain.ProposalContentHash
import com.tufin.debate.agreements.domain.ProposalStatus
import com.tufin.debate.agreements.domain.ProposalVersion
import com.tufin.debate.agreements.infrastructure.ApprovalRepository
import com.tufin.debate.agreements.infrastructure.ApprovalRequestRepository
import com.tufin.debate.agreements.infrastructure.ProposalRepository
import com.tufin.debate.agreements.infrastructure.ProposalVersionRepository
import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.discussion.application.RoomDirectory
import com.tufin.debate.discussion.application.RoomLifecycleService
import com.tufin.debate.discussion.domain.RoomStatus
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.participants.application.ParticipantDirectory
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.permissions.application.PermissionsService
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.errors.BadRequestException
import com.tufin.debate.shared.errors.ConflictException
import com.tufin.debate.shared.errors.NotFoundException
import com.tufin.debate.shared.idempotency.IdempotencyService
import com.tufin.debate.shared.outbox.OutboxService
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

data class ApprovalView(
    val userId: String,
    val userDisplayName: String,
    val decision: ApprovalDecision,
    val comment: String?,
    val proposalVersion: Int,
    val createdAt: Instant,
)

data class ApprovalRequestView(
    val id: String,
    val proposalId: String,
    val proposalVersion: Int,
    val status: ApprovalRequestStatus,
    val requiredUserIds: List<String>,
    val approvals: List<ApprovalView>,
    val myDecision: ApprovalDecision?,
    val createdAt: Instant,
)

data class ProposalVersionView(
    val version: Int,
    val title: String,
    val terms: List<String>,
    val assumptions: List<String>,
    val createdByDisplayName: String,
    val superseded: Boolean,
    val createdAt: Instant,
)

data class ProposalView(
    val id: String,
    val status: ProposalStatus,
    val currentVersion: Int,
    val current: ProposalVersionView,
    val versions: List<ProposalVersionView>,
    val pendingRequest: ApprovalRequestView?,
    val createdAt: Instant,
)

/**
 * Formal proposals and the human-only approval flow (trust-model invariants 5 & 9): every approval
 * binds an exact version + content hash; any revision creates a new version and voids pending
 * approvals; a room reaches AGREED only when every required party approved the same version.
 */
@Service
class ProposalService(
    private val proposals: ProposalRepository,
    private val versions: ProposalVersionRepository,
    private val requests: ApprovalRequestRepository,
    private val approvals: ApprovalRepository,
    private val permissions: PermissionsService,
    private val directory: ParticipantDirectory,
    private val rooms: RoomDirectory,
    private val lifecycle: RoomLifecycleService,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
    private val idempotencyService: IdempotencyService,
    private val transactionTemplate: TransactionTemplate,
) {
    // ---------------- create / revise ----------------

    @Transactional
    fun create(
        roomId: String,
        actor: AuthenticatedUser,
        title: String,
        terms: List<String>,
        assumptions: List<String>,
        sourceRunId: String?,
    ): ProposalView {
        val author = permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        validateContent(title, terms)
        val now = Instant.now()
        val proposal = proposals.insert(
            Proposal(
                id = Ids.newId(), roomId = roomId, createdByUserId = actor.userId,
                currentVersion = 1, createdAt = now, updatedAt = now,
            ),
        )
        insertVersion(proposal, actor, author.displayName, title, terms, assumptions, sourceRunId, now)
        auditService.append(
            roomId, ActorType.USER, actor.userId, "PROPOSAL_CREATED", "Proposal", proposal.id,
            metadata = mapOf("version" to "1"),
        )
        outboxService.enqueue(roomId, "PROPOSAL_CREATED", mapOf("resourceId" to proposal.id, "resourceVersion" to 1))
        return view(roomId, proposal, actor)
    }

    @Transactional
    fun revise(
        roomId: String,
        proposalId: String,
        actor: AuthenticatedUser,
        title: String,
        terms: List<String>,
        assumptions: List<String>,
    ): ProposalView {
        val author = permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        validateContent(title, terms)
        val proposal = proposals.findByIdAndRoomId(proposalId, roomId)
            ?: throw NotFoundException("This proposal was not found")
        if (proposal.status != ProposalStatus.OPEN) {
            throw ConflictException("An agreed or closed proposal cannot be revised")
        }

        val now = Instant.now()
        proposal.currentVersion += 1
        proposal.updatedAt = now
        proposals.save(proposal) // optimistic @Version: concurrent revisions -> controlled 409 (test 21)
        insertVersion(proposal, actor, author.displayName, title, terms, assumptions, null, now)

        // A revision invalidates every pending approval for previous versions (test 13).
        val superseded = requests.findByProposalIdAndStatus(proposalId, ApprovalRequestStatus.PENDING)
        superseded.forEach {
            it.status = ApprovalRequestStatus.SUPERSEDED
            it.updatedAt = now
            requests.save(it)
        }

        auditService.append(
            roomId, ActorType.USER, actor.userId, "PROPOSAL_REVISED", "Proposal", proposalId,
            metadata = mapOf(
                "version" to proposal.currentVersion.toString(),
                "invalidatedRequests" to superseded.size.toString(),
            ),
        )
        outboxService.enqueue(
            roomId, "PROPOSAL_REVISED",
            mapOf("resourceId" to proposalId, "resourceVersion" to proposal.currentVersion),
        )

        // Approval was pending: the room steps back to ACTIVE until a new request is made.
        if (superseded.isNotEmpty() && rooms.find(roomId)?.status == RoomStatus.AGREEMENT_PENDING_APPROVAL) {
            lifecycle.transition(roomId, RoomStatus.ACTIVE, ActorType.SYSTEM, null, "proposal revised")
        }
        return view(roomId, proposal, actor)
    }

    // ---------------- approval flow ----------------

    @Transactional
    fun requestApproval(roomId: String, proposalId: String, actor: AuthenticatedUser): ProposalView {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        val proposal = proposals.findByIdAndRoomId(proposalId, roomId)
            ?: throw NotFoundException("This proposal was not found")
        if (proposal.status != ProposalStatus.OPEN) {
            throw ConflictException("This proposal is no longer open")
        }
        if (requests.findByProposalIdAndStatus(proposalId, ApprovalRequestStatus.PENDING).isNotEmpty()) {
            throw ConflictException("Approval was already requested for this proposal")
        }
        val current = versions.findByProposalIdAndRoomIdAndVersion(proposalId, roomId, proposal.currentVersion)
            ?: throw NotFoundException("This proposal was not found")

        val requiredUserIds = partyUserIds(roomId)
        if (requiredUserIds.size < 2) throw ConflictException("An agreement needs at least two parties")

        val request = requests.insert(
            ApprovalRequest(
                id = Ids.newId(),
                roomId = roomId,
                proposalId = proposalId,
                proposalVersion = proposal.currentVersion,
                contentHash = current.contentHash,
                requestedByUserId = actor.userId,
                requiredUserIds = requiredUserIds,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        auditService.append(
            roomId, ActorType.USER, actor.userId, "APPROVAL_REQUESTED", "ApprovalRequest", request.id,
            metadata = mapOf("proposalId" to proposalId, "version" to proposal.currentVersion.toString()),
        )
        outboxService.enqueue(
            roomId, "APPROVAL_REQUESTED",
            mapOf(
                "resourceId" to request.id, "resourceVersion" to proposal.currentVersion,
                "audienceUserIds" to requiredUserIds, "actorUserId" to actor.userId,
            ),
        )

        val room = rooms.find(roomId)
        if (room != null && room.status in setOf(RoomStatus.ACTIVE, RoomStatus.PROPOSAL_READY)) {
            if (room.status == RoomStatus.ACTIVE) {
                lifecycle.transition(roomId, RoomStatus.PROPOSAL_READY, ActorType.SYSTEM, null, "approval requested")
            }
            lifecycle.transition(roomId, RoomStatus.AGREEMENT_PENDING_APPROVAL, ActorType.SYSTEM, null, "approval requested")
        }
        return view(roomId, proposal, actor)
    }

    fun decide(
        roomId: String,
        approvalRequestId: String,
        actor: AuthenticatedUser,
        decision: ApprovalDecision,
        comment: String?,
        expectedVersion: Int?,
        idempotencyKey: String?,
    ): ApprovalRequestView =
        idempotencyService.execute(roomId, "APPROVAL_DECISION", idempotencyKey, ApprovalRequestView::class.java) {
            transactionTemplate.execute { decideInternal(roomId, approvalRequestId, actor, decision, comment, expectedVersion) }!!
        }

    private fun decideInternal(
        roomId: String,
        approvalRequestId: String,
        actor: AuthenticatedUser,
        decision: ApprovalDecision,
        comment: String?,
        expectedVersion: Int?,
    ): ApprovalRequestView {
        val participant = permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        val request = requests.findByIdAndRoomId(approvalRequestId, roomId)
            ?: throw NotFoundException("This approval request was not found")
        if (actor.userId !in request.requiredUserIds) {
            throw NotFoundException("This approval request was not found")
        }
        if (request.status == ApprovalRequestStatus.SUPERSEDED) {
            throw ConflictException("The proposal changed since this request — review the new version")
        }
        if (request.status != ApprovalRequestStatus.PENDING) {
            throw ConflictException("This approval request was already decided")
        }
        // Approving an obsolete version is impossible: the request pins version + hash, and a
        // client that saw a different version is told to reload (tests 12/13/21).
        if (expectedVersion != null && expectedVersion != request.proposalVersion) {
            throw ConflictException("The proposal changed since you viewed it. Reload and review again.")
        }
        val existing = approvals.findByApprovalRequestIdAndUserId(approvalRequestId, actor.userId)
        if (existing != null) {
            if (existing.decision == decision) return requestView(request, actor) // idempotent (test 14)
            throw ConflictException("You already responded to this request")
        }

        try {
            approvals.insert(
                Approval(
                    id = Ids.newId(),
                    approvalRequestId = approvalRequestId,
                    roomId = roomId,
                    proposalId = request.proposalId,
                    proposalVersion = request.proposalVersion,
                    contentHash = request.contentHash,
                    userId = actor.userId,
                    userDisplayName = participant.displayName,
                    decision = decision,
                    comment = comment?.trim()?.takeIf { it.isNotEmpty() },
                    createdAt = Instant.now(),
                ),
            )
        } catch (e: DuplicateKeyException) {
            throw ConflictException("You already responded to this request")
        }

        auditService.append(
            roomId, ActorType.USER, actor.userId, "APPROVAL_RECORDED", "ApprovalRequest", approvalRequestId,
            metadata = mapOf(
                "decision" to decision.name,
                "proposalId" to request.proposalId,
                "version" to request.proposalVersion.toString(),
            ),
        )
        outboxService.enqueue(
            roomId, "APPROVAL_RECORDED",
            mapOf(
                "resourceId" to approvalRequestId, "resourceVersion" to request.proposalVersion,
                "audienceUserIds" to request.requiredUserIds, "actorUserId" to actor.userId,
            ),
        )

        finalizeIfDecided(request)
        return requestView(request, actor)
    }

    private fun finalizeIfDecided(request: ApprovalRequest) {
        val recorded = approvals.findByApprovalRequestId(request.id)
        val now = Instant.now()

        if (recorded.any { it.decision != ApprovalDecision.APPROVED }) {
            request.status = ApprovalRequestStatus.REJECTED
            request.updatedAt = now
            requests.save(request)
            if (rooms.find(request.roomId)?.status == RoomStatus.AGREEMENT_PENDING_APPROVAL) {
                lifecycle.transition(request.roomId, RoomStatus.ACTIVE, ActorType.SYSTEM, null, "approval declined")
            }
            return
        }

        val approvedBy = recorded.map { it.userId }.toSet()
        // One party's approval never suffices (test 12): every required party must approve.
        if (!approvedBy.containsAll(request.requiredUserIds.toSet())) return

        request.status = ApprovalRequestStatus.APPROVED
        request.updatedAt = now
        requests.save(request)
        proposals.findById(request.proposalId).ifPresent {
            it.status = ProposalStatus.AGREED
            it.updatedAt = now
            proposals.save(it)
        }
        if (rooms.find(request.roomId)?.status == RoomStatus.AGREEMENT_PENDING_APPROVAL) {
            lifecycle.transition(request.roomId, RoomStatus.AGREED, ActorType.SYSTEM, null, "all parties approved")
        }
    }

    // ---------------- reads ----------------

    fun list(roomId: String, actor: AuthenticatedUser): List<ProposalView> {
        permissions.requireParticipant(roomId, actor.userId)
        return proposals.findByRoomIdOrderByCreatedAtDesc(roomId).map { view(roomId, it, actor) }
    }

    fun get(roomId: String, proposalId: String, actor: AuthenticatedUser): ProposalView {
        permissions.requireParticipant(roomId, actor.userId)
        val proposal = proposals.findByIdAndRoomId(proposalId, roomId)
            ?: throw NotFoundException("This proposal was not found")
        return view(roomId, proposal, actor)
    }

    private fun view(roomId: String, proposal: Proposal, actor: AuthenticatedUser): ProposalView {
        val allVersions = versions.findByProposalIdAndRoomIdOrderByVersionAsc(proposal.id, roomId)
        val versionViews = allVersions.map {
            ProposalVersionView(
                it.version, it.title, it.terms, it.assumptions, it.createdByDisplayName,
                superseded = it.version < proposal.currentVersion, createdAt = it.createdAt,
            )
        }
        val pending = requests.findByProposalIdAndStatus(proposal.id, ApprovalRequestStatus.PENDING)
            .firstOrNull()
        return ProposalView(
            id = proposal.id,
            status = proposal.status,
            currentVersion = proposal.currentVersion,
            current = versionViews.last(),
            versions = versionViews,
            pendingRequest = pending?.let { requestView(it, actor) },
            createdAt = proposal.createdAt,
        )
    }

    private fun requestView(request: ApprovalRequest, actor: AuthenticatedUser): ApprovalRequestView {
        val recorded = approvals.findByApprovalRequestId(request.id)
        return ApprovalRequestView(
            id = request.id,
            proposalId = request.proposalId,
            proposalVersion = request.proposalVersion,
            status = requests.findById(request.id).map { it.status }.orElse(request.status),
            requiredUserIds = request.requiredUserIds,
            approvals = recorded.map {
                ApprovalView(it.userId, it.userDisplayName, it.decision, it.comment, it.proposalVersion, it.createdAt)
            },
            myDecision = recorded.find { it.userId == actor.userId }?.decision,
            createdAt = request.createdAt,
        )
    }

    // ---------------- helpers ----------------

    private fun insertVersion(
        proposal: Proposal,
        actor: AuthenticatedUser,
        displayName: String,
        title: String,
        terms: List<String>,
        assumptions: List<String>,
        sourceRunId: String?,
        now: Instant,
    ) {
        versions.insert(
            ProposalVersion(
                id = Ids.newId(),
                proposalId = proposal.id,
                roomId = proposal.roomId,
                version = proposal.currentVersion,
                title = title.trim(),
                terms = terms.map { it.trim() }.filter { it.isNotEmpty() },
                assumptions = assumptions.map { it.trim() }.filter { it.isNotEmpty() },
                contentHash = ProposalContentHash.compute(title.trim(), terms, assumptions),
                createdByUserId = actor.userId,
                createdByDisplayName = displayName,
                sourceRunId = sourceRunId,
                createdAt = now,
            ),
        )
    }

    private fun validateContent(title: String, terms: List<String>) {
        if (title.isBlank() || title.length > 300) throw BadRequestException("The proposal needs a short title")
        val cleaned = terms.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty() || cleaned.size > 30 || cleaned.any { it.length > 1000 }) {
            throw BadRequestException("A proposal needs between 1 and 30 terms")
        }
    }

    private fun partyUserIds(roomId: String): List<String> =
        directory.activeParticipants(roomId)
            .filter { ParticipantRole.PARTY in it.roles || ParticipantRole.OWNER in it.roles }
            .map { it.userId }
            .distinct()
            .sorted()
}

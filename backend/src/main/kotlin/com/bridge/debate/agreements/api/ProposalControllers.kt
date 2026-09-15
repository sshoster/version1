package com.bridge.debate.agreements.api

import com.bridge.debate.agreements.application.ApprovalRequestView
import com.bridge.debate.agreements.application.ProposalService
import com.bridge.debate.agreements.application.ProposalView
import com.bridge.debate.agreements.domain.ApprovalDecision
import com.bridge.debate.identity.application.AuthenticatedUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class ProposalContentRequest(
    @field:NotBlank @field:Size(max = 300) val title: String = "",
    @field:NotEmpty val terms: List<@Size(max = 1000) String> = emptyList(),
    val assumptions: List<@Size(max = 1000) String> = emptyList(),
    val sourceRunId: String? = null,
)

data class DecisionRequest(
    @field:Size(max = 2000) val comment: String? = null,
    /** The proposal version the user saw; mismatch with the pinned version → controlled 409. */
    val expectedVersion: Int? = null,
)

@RestController
class ProposalController(private val service: ProposalService) {

    @PostMapping("/api/v1/rooms/{roomId}/proposals")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable roomId: String,
        @RequestBody @Valid request: ProposalContentRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ProposalView = service.create(roomId, user, request.title, request.terms, request.assumptions, request.sourceRunId)

    @GetMapping("/api/v1/rooms/{roomId}/proposals")
    fun list(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<ProposalView> = service.list(roomId, user)

    @GetMapping("/api/v1/rooms/{roomId}/proposals/{proposalId}")
    fun get(
        @PathVariable roomId: String,
        @PathVariable proposalId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ProposalView = service.get(roomId, proposalId, user)

    @PostMapping("/api/v1/rooms/{roomId}/proposals/{proposalId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    fun revise(
        @PathVariable roomId: String,
        @PathVariable proposalId: String,
        @RequestBody @Valid request: ProposalContentRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ProposalView = service.revise(roomId, proposalId, user, request.title, request.terms, request.assumptions)

    @PostMapping("/api/v1/rooms/{roomId}/proposals/{proposalId}/request-approval")
    fun requestApproval(
        @PathVariable roomId: String,
        @PathVariable proposalId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ProposalView = service.requestApproval(roomId, proposalId, user)

    @PostMapping("/api/v1/rooms/{roomId}/approval-requests/{approvalRequestId}/approve")
    fun approve(
        @PathVariable roomId: String,
        @PathVariable approvalRequestId: String,
        @RequestBody(required = false) @Valid request: DecisionRequest?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ApprovalRequestView = service.decide(
        roomId, approvalRequestId, user, ApprovalDecision.APPROVED,
        request?.comment, request?.expectedVersion, idempotencyKey,
    )

    @PostMapping("/api/v1/rooms/{roomId}/approval-requests/{approvalRequestId}/reject")
    fun reject(
        @PathVariable roomId: String,
        @PathVariable approvalRequestId: String,
        @RequestBody(required = false) @Valid request: DecisionRequest?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ApprovalRequestView = service.decide(
        roomId, approvalRequestId, user, ApprovalDecision.REJECTED,
        request?.comment, request?.expectedVersion, idempotencyKey,
    )

    @PostMapping("/api/v1/rooms/{roomId}/approval-requests/{approvalRequestId}/request-changes")
    fun requestChanges(
        @PathVariable roomId: String,
        @PathVariable approvalRequestId: String,
        @RequestBody(required = false) @Valid request: DecisionRequest?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ApprovalRequestView = service.decide(
        roomId, approvalRequestId, user, ApprovalDecision.CHANGES_REQUESTED,
        request?.comment, request?.expectedVersion, idempotencyKey,
    )
}

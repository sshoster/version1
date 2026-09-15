package com.bridge.debate.agreements.api

import com.bridge.debate.agreements.application.OutcomeService
import com.bridge.debate.agreements.domain.OutcomeArtifact
import com.bridge.debate.agreements.domain.OutcomeType
import com.bridge.debate.agreements.domain.UnderstandingBlock
import com.bridge.debate.identity.application.AuthenticatedUser
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class EmailDraftRequest(
    /** Empty or absent = every active participant. Always intersected with room members. */
    val recipientUserIds: List<String>? = null,
)

data class EmailDraftResponse(val sent: Int)

data class OutcomeView(
    val id: String,
    val type: OutcomeType,
    val version: Int,
    val aiGenerated: Boolean,
    val draftOnly: Boolean,
    val notLegalAdvice: Boolean,
    val text: String?,
    val understandings: List<UnderstandingBlock>?,
    val llmProvider: String?,
    val createdAt: Instant,
)

private fun OutcomeArtifact.toView() = OutcomeView(
    id, type, version, aiGenerated, draftOnly, notLegalAdvice, text, understandings, llmProvider, createdAt,
)

@RestController
class OutcomeController(private val service: OutcomeService) {

    @GetMapping("/api/v1/rooms/{roomId}/outcomes")
    fun list(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<OutcomeView> = service.list(roomId, user).map { it.toView() }

    @PostMapping("/api/v1/rooms/{roomId}/outcomes/summary")
    @ResponseStatus(HttpStatus.CREATED)
    fun summary(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): OutcomeView = service.generateSummary(roomId, user).toView()

    @PostMapping("/api/v1/rooms/{roomId}/outcomes/approved-understandings")
    @ResponseStatus(HttpStatus.CREATED)
    fun approvedUnderstandings(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): OutcomeView = service.generateApprovedUnderstandings(roomId, user).toView()

    @PostMapping("/api/v1/rooms/{roomId}/outcomes/agreement-draft")
    @ResponseStatus(HttpStatus.CREATED)
    fun agreementDraft(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): OutcomeView = service.generateAgreementDraft(roomId, user).toView()

    @PostMapping("/api/v1/rooms/{roomId}/outcomes/agreement-draft/email")
    fun emailAgreementDraft(
        @PathVariable roomId: String,
        @RequestBody(required = false) request: EmailDraftRequest?,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): EmailDraftResponse = EmailDraftResponse(service.emailAgreementDraft(roomId, user, request?.recipientUserIds))
}

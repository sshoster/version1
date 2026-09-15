package com.bridge.debate.negotiation.api

import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.negotiation.application.AgentProfileService
import com.bridge.debate.negotiation.application.AgentProfileView
import com.bridge.debate.negotiation.application.NegotiationOrchestrator
import com.bridge.debate.negotiation.application.StartRunResult
import com.bridge.debate.negotiation.domain.QuestionStatus
import com.bridge.debate.negotiation.domain.RoundResult
import com.bridge.debate.negotiation.domain.RunStatus
import com.bridge.debate.negotiation.domain.RunStopReason
import com.bridge.debate.negotiation.domain.TurnProposal
import com.bridge.debate.negotiation.infrastructure.NegotiationRunRepository
import com.bridge.debate.negotiation.infrastructure.NegotiationTurnRepository
import com.bridge.debate.negotiation.infrastructure.QuestionRepository
import com.bridge.debate.participants.domain.ParticipantRole
import com.bridge.debate.permissions.application.PermissionsService
import com.bridge.debate.shared.errors.NotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AgentProfileRequest(
    @field:Size(max = 2000) val goals: String = "",
    @field:Size(max = 2000) val boundaries: String = "",
    @field:Size(max = 2000) val flexibility: String = "",
)

data class StartRunRequest(
    @field:Min(2) @field:Max(50) val maxTurns: Int? = null,
)

data class TurnView(
    val turnNumber: Int,
    val partyDisplayName: String,
    val publicMessage: String?,
    val proposal: TurnProposal?,
    val questionsForOtherParty: List<String>,
    val createdAt: Instant,
)

data class RunView(
    val id: String,
    val status: RunStatus,
    val stopReason: RunStopReason,
    val turnCount: Int,
    val maxTurns: Int,
    val result: RoundResult?,
    val turns: List<TurnView>,
    val myOpenQuestions: List<QuestionView>,
    /** Who still owes their assistant an answer — user IDs only, never the question content. */
    val pendingAnswerUserIds: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class QuestionView(
    val id: String,
    val text: String,
    val options: List<String>,
    val status: QuestionStatus,
    val createdAt: Instant,
)

data class AnswerRequest(
    @field:NotBlank @field:Size(max = 2000) val text: String = "",
)

@RestController
class NegotiationController(
    private val orchestrator: NegotiationOrchestrator,
    private val agentProfiles: AgentProfileService,
    private val runs: NegotiationRunRepository,
    private val turns: NegotiationTurnRepository,
    private val questions: QuestionRepository,
    private val permissions: PermissionsService,
) {
    // ---- agent profile (own only) ----

    @GetMapping("/api/v1/rooms/{roomId}/agent-profile")
    fun getProfile(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): AgentProfileView = agentProfiles.get(roomId, user)

    @PutMapping("/api/v1/rooms/{roomId}/agent-profile")
    fun putProfile(
        @PathVariable roomId: String,
        @RequestBody @Valid request: AgentProfileRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): AgentProfileView = agentProfiles.put(roomId, user, request.goals, request.boundaries, request.flexibility)

    // ---- runs ----

    @PostMapping("/api/v1/rooms/{roomId}/negotiation-runs")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(
        @PathVariable roomId: String,
        @RequestBody(required = false) request: StartRunRequest?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): StartRunResult = orchestrator.start(roomId, user, request?.maxTurns, idempotencyKey)

    @GetMapping("/api/v1/rooms/{roomId}/negotiation-runs")
    fun list(
        @PathVariable roomId: String,
        @RequestParam(defaultValue = "5") limit: Int,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<RunView> {
        permissions.requireRole(roomId, user.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        return runs.findByRoomIdOrderByCreatedAtDesc(roomId)
            .take(limit.coerceIn(1, 20))
            .map { runView(roomId, it.id, user) }
    }

    @GetMapping("/api/v1/rooms/{roomId}/negotiation-runs/{runId}")
    fun get(
        @PathVariable roomId: String,
        @PathVariable runId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): RunView {
        permissions.requireRole(roomId, user.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        return runView(roomId, runId, user)
    }

    @PostMapping("/api/v1/rooms/{roomId}/negotiation-runs/{runId}/pause")
    fun pause(
        @PathVariable roomId: String,
        @PathVariable runId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = orchestrator.pause(roomId, runId, user)

    @PostMapping("/api/v1/rooms/{roomId}/negotiation-runs/{runId}/resume")
    fun resume(
        @PathVariable roomId: String,
        @PathVariable runId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = orchestrator.resume(roomId, runId, user)

    // ---- questions (own only) ----

    @GetMapping("/api/v1/rooms/{roomId}/questions")
    fun myQuestions(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<QuestionView> {
        permissions.requireRole(roomId, user.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        return questions.findByRoomIdAndToUserIdOrderByCreatedAtDesc(roomId, user.userId)
            .map { QuestionView(it.id, it.text, it.suggestedOptions, it.status, it.createdAt) }
    }

    @PostMapping("/api/v1/rooms/{roomId}/questions/{questionId}/answer")
    fun answer(
        @PathVariable roomId: String,
        @PathVariable questionId: String,
        @RequestBody @Valid request: AnswerRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = orchestrator.answerQuestion(roomId, questionId, user, request.text)

    // ---- view assembly ----

    private fun runView(roomId: String, runId: String, user: AuthenticatedUser): RunView {
        val run = runs.findByIdAndRoomId(runId, roomId) ?: throw NotFoundException("This run was not found")
        val turnViews = turns.findByRunIdAndRoomIdOrderByTurnNumberAsc(runId, roomId).map {
            TurnView(
                it.turnNumber, it.partyDisplayName, it.publicMessage, it.proposal,
                it.questionsForOtherParty, it.createdAt,
            )
        }
        val open = questions.findByRunIdAndToUserIdAndStatus(runId, user.userId, QuestionStatus.OPEN)
            .map { QuestionView(it.id, it.text, it.suggestedOptions, it.status, it.createdAt) }
        val pendingAnswerUserIds = questions.findByRunIdAndStatusOrderByCreatedAtAsc(runId, QuestionStatus.OPEN)
            .map { it.toUserId }
            .distinct()
        return RunView(
            id = run.id,
            status = run.status,
            stopReason = run.stopReason,
            turnCount = run.turnCount,
            maxTurns = run.maxTurns,
            result = run.result,
            turns = turnViews,
            myOpenQuestions = open,
            pendingAnswerUserIds = pendingAnswerUserIds,
            createdAt = run.createdAt,
            updatedAt = run.updatedAt,
        )
    }
}

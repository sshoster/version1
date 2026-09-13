package com.tufin.debate.negotiation.application

import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.discussion.application.RoomDirectory
import com.tufin.debate.discussion.application.RoomLifecycleService
import com.tufin.debate.discussion.domain.RoomStatus
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.llm.application.LlmProvider
import com.tufin.debate.llm.application.LlmRequest
import com.tufin.debate.messaging.application.SharedContextReader
import com.tufin.debate.negotiation.domain.NegotiationRun
import com.tufin.debate.negotiation.domain.NegotiationTurn
import com.tufin.debate.negotiation.domain.Question
import com.tufin.debate.negotiation.domain.QuestionStatus
import com.tufin.debate.negotiation.domain.RoundResult
import com.tufin.debate.negotiation.domain.RunStatus
import com.tufin.debate.negotiation.domain.RunStopReason
import com.tufin.debate.negotiation.infrastructure.NegotiationRunRepository
import com.tufin.debate.negotiation.infrastructure.NegotiationTurnRepository
import com.tufin.debate.negotiation.infrastructure.QuestionRepository
import com.tufin.debate.participants.application.ParticipantDirectory
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.permissions.application.PermissionsService
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.errors.BadRequestException
import com.tufin.debate.shared.errors.ConflictException
import com.tufin.debate.shared.errors.NotFoundException
import com.tufin.debate.shared.idempotency.IdempotencyService
import com.tufin.debate.shared.outbox.OutboxService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.Executor

data class StartRunResult(val runId: String, val status: RunStatus)

/**
 * Runs the automated AI-to-AI discussion (design doc §6): alternating turns with strictly
 * separated per-party contexts, server-side output validation, stopping rules, and budgets.
 * The loop executes on a background executor; progress flows through the outbox (WS events).
 */
@Service
class NegotiationOrchestrator(
    private val runs: NegotiationRunRepository,
    private val turns: NegotiationTurnRepository,
    private val questions: QuestionRepository,
    private val profiles: AgentProfileService,
    private val contextBuilder: NegotiationContextBuilder,
    private val sharedContext: SharedContextReader,
    private val llmProvider: LlmProvider,
    private val permissions: PermissionsService,
    private val participants: ParticipantDirectory,
    private val rooms: RoomDirectory,
    private val lifecycle: RoomLifecycleService,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
    private val idempotencyService: IdempotencyService,
    private val transactionTemplate: TransactionTemplate,
    @param:Qualifier("negotiationExecutor") private val executor: Executor,
    @param:Value("\${app.negotiation.max-turns:10}") private val defaultMaxTurns: Int,
    @param:Value("\${app.negotiation.max-total-tokens:20000}") private val maxTotalTokens: Int,
    @param:Value("\${app.negotiation.max-run-seconds:180}") private val maxRunSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ---------------- start / control ----------------

    fun start(roomId: String, actor: AuthenticatedUser, requestedMaxTurns: Int?, idempotencyKey: String?): StartRunResult =
        idempotencyService.execute(roomId, "NEGOTIATION_START", idempotencyKey, StartRunResult::class.java) {
            permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
            val room = rooms.find(roomId) ?: throw NotFoundException("This discussion was not found")
            if (room.status != RoomStatus.ACTIVE) {
                throw ConflictException("The assistants can start once the discussion is active")
            }
            if (partyUserIds(roomId).size != 2) {
                throw ConflictException("An automated round needs exactly two parties in the MVP")
            }

            val run = NegotiationRun(
                id = Ids.newId(),
                roomId = roomId,
                startedByUserId = actor.userId,
                maxTurns = (requestedMaxTurns ?: defaultMaxTurns).coerceIn(2, defaultMaxTurns),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
            try {
                runs.insert(run) // unique partial index: one active run per room
            } catch (e: DuplicateKeyException) {
                throw ConflictException("The assistants are already working in this discussion")
            }

            auditService.append(roomId, ActorType.USER, actor.userId, "NEGOTIATION_STARTED", "NegotiationRun", run.id)
            outboxService.enqueue(roomId, "NEGOTIATION_STARTED", mapOf("resourceId" to run.id))

            executor.execute { executeLoop(run.id, roomId) }
            StartRunResult(run.id, run.status)
        }

    fun pause(roomId: String, runId: String, actor: AuthenticatedUser) {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        val run = runs.findByIdAndRoomId(runId, roomId) ?: throw NotFoundException("This run was not found")
        if (run.status !in setOf(RunStatus.RUNNING, RunStatus.WAITING_FOR_USER)) {
            throw ConflictException("This run cannot be paused now")
        }
        run.status = RunStatus.PAUSED
        run.updatedAt = Instant.now()
        runs.save(run)
        auditService.append(roomId, ActorType.USER, actor.userId, "NEGOTIATION_PAUSED", "NegotiationRun", runId)
        outboxService.enqueue(roomId, "ROOM_UPDATED", mapOf("resourceId" to runId))
    }

    fun resume(roomId: String, runId: String, actor: AuthenticatedUser) {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        val run = runs.findByIdAndRoomId(runId, roomId) ?: throw NotFoundException("This run was not found")
        if (run.status !in setOf(RunStatus.PAUSED, RunStatus.WAITING_FOR_USER)) {
            throw ConflictException("This run cannot be resumed now")
        }
        if (questions.countByRunIdAndStatus(runId, QuestionStatus.OPEN) > 0) {
            run.status = RunStatus.WAITING_FOR_USER
            run.updatedAt = Instant.now()
            runs.save(run)
            throw ConflictException("There are unanswered questions from the assistant")
        }
        run.status = RunStatus.RUNNING
        run.updatedAt = Instant.now()
        runs.save(run)
        if (rooms.find(roomId)?.status == RoomStatus.WAITING_FOR_USER) {
            lifecycle.transition(roomId, RoomStatus.ACTIVE, ActorType.USER, actor.userId, "run resumed")
        }
        auditService.append(roomId, ActorType.USER, actor.userId, "NEGOTIATION_RESUMED", "NegotiationRun", runId)
        outboxService.enqueue(roomId, "NEGOTIATION_STARTED", mapOf("resourceId" to runId))
        executor.execute { executeLoop(runId, roomId) }
    }

    /** Answering privately feeds the asking assistant; the run resumes when nothing is left open. */
    fun answerQuestion(roomId: String, questionId: String, actor: AuthenticatedUser, answer: String) {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        val question = questions.findByIdAndRoomId(questionId, roomId)
            ?: throw NotFoundException("This question was not found")
        if (question.toUserId != actor.userId) {
            throw NotFoundException("This question was not found") // not yours: don't reveal existence
        }
        if (question.status != QuestionStatus.OPEN) {
            throw ConflictException("This question was already answered")
        }
        val trimmed = answer.trim()
        if (trimmed.isEmpty() || trimmed.length > 2000) {
            throw BadRequestException("The answer must be between 1 and 2000 characters")
        }

        question.status = QuestionStatus.ANSWERED
        question.answerText = profiles.encryptAnswer(trimmed)
        question.answeredAt = Instant.now()
        questions.save(question)
        auditService.append(roomId, ActorType.USER, actor.userId, "QUESTION_ANSWERED", "Question", questionId)

        val run = runs.findById(question.runId).orElse(null) ?: return
        if (run.status == RunStatus.WAITING_FOR_USER &&
            questions.countByRunIdAndStatus(run.id, QuestionStatus.OPEN) == 0L
        ) {
            run.status = RunStatus.RUNNING
            run.updatedAt = Instant.now()
            runs.save(run)
            if (rooms.find(roomId)?.status == RoomStatus.WAITING_FOR_USER) {
                lifecycle.transition(roomId, RoomStatus.ACTIVE, ActorType.SYSTEM, null, "questions answered")
            }
            outboxService.enqueue(roomId, "NEGOTIATION_STARTED", mapOf("resourceId" to run.id))
            executor.execute { executeLoop(run.id, roomId) }
        }
    }

    // ---------------- the loop ----------------

    private fun executeLoop(runId: String, roomId: String) {
        MDC.put("correlationId", "run-$runId")
        try {
            val startedAt = Instant.now()
            val parties = partyUserIds(roomId)
            if (parties.size != 2) return failRun(runId, roomId, "party set changed")

            while (true) {
                val run = runs.findById(runId).orElse(null) ?: return
                if (run.status != RunStatus.RUNNING) return // paused/stopped externally

                // Budgets are enforced BEFORE each model call.
                if (run.turnCount >= run.maxTurns) {
                    return completeRun(run, RunStopReason.MAX_TURNS)
                }
                if (run.tokensUsed >= maxTotalTokens) {
                    return completeRun(run, RunStopReason.BUDGET_EXCEEDED)
                }
                if (Instant.now().isAfter(startedAt.plusSeconds(maxRunSeconds))) {
                    return completeRun(run, RunStopReason.BUDGET_EXCEEDED)
                }

                val turnNumber = run.turnCount + 1
                // Alternate assistants; the initiator's assistant opens.
                val ordered = listOf(run.startedByUserId) + parties.filter { it != run.startedByUserId }
                val partyUserId = ordered[(turnNumber - 1) % 2]

                val outcome = try {
                    produceTurn(run, roomId, partyUserId, turnNumber)
                } catch (e: TurnOutputException) {
                    log.warn("Run {} turn {} rejected: {}", runId, turnNumber, e.message)
                    return failRun(runId, roomId, "invalid model output", RunStopReason.SAFETY)
                } catch (e: Exception) {
                    // Provider failure: domain state stays clean and the room remains usable (test 17).
                    log.warn("Run {} provider failure on turn {}: {}", runId, turnNumber, e.message)
                    return failRun(runId, roomId, "provider failure", RunStopReason.PROVIDER_ERROR)
                }

                when (outcome) {
                    is TurnOutcome.Continue -> Unit
                    is TurnOutcome.WaitForUser -> return waitForUser(runId, roomId)
                    is TurnOutcome.Stop -> {
                        val fresh = runs.findById(runId).orElse(null) ?: return
                        return completeRun(fresh, outcome.reason)
                    }
                }
            }
        } finally {
            MDC.remove("correlationId")
        }
    }

    private sealed interface TurnOutcome {
        data object Continue : TurnOutcome
        data object WaitForUser : TurnOutcome
        data class Stop(val reason: RunStopReason) : TurnOutcome
    }

    private fun produceTurn(run: NegotiationRun, roomId: String, partyUserId: String, turnNumber: Int): TurnOutcome {
        val room = rooms.find(roomId) ?: throw IllegalStateException("room disappeared")
        val participant = participants.activeParticipant(roomId, partyUserId)
            ?: throw IllegalStateException("party left the room")

        val facts = sharedContext.visibleFacts(roomId, partyUserId)
        val transcript = turns.findByRunIdAndRoomIdOrderByTurnNumberAsc(run.id, roomId)
        val answered = questions.findByRunIdAndToUserIdAndStatus(run.id, partyUserId, QuestionStatus.ANSWERED)
            .map { profiles.decryptQuestion(it) }

        val response = runBlocking {
            llmProvider.generate(
                LlmRequest(
                    templateId = NegotiationContextBuilder.TEMPLATE_ID,
                    expectsJson = true,
                    system = contextBuilder.systemPrompt(room, participant.displayName),
                    user = contextBuilder.userPrompt(
                        room = room,
                        profile = profiles.findDecrypted(roomId, partyUserId),
                        privateNotes = profiles.privateNotes(roomId, partyUserId),
                        sharedFacts = facts,
                        transcript = transcript,
                        answeredQuestions = answered,
                        currentTurn = turnNumber,
                    ),
                ),
            )
        }

        val (output, modelStopReason) = TurnOutputParser.parse(response.text)

        // Trust boundary: the model may only cite facts its party can actually see.
        val visibleIds = facts.map { it.versionId }.toSet()
        val invalidFacts = output.sharedFactsUsed.filter { it !in visibleIds }
        if (invalidFacts.isNotEmpty()) {
            throw TurnOutputException("Turn cites content outside the party's authorized shared facts")
        }

        val turn = NegotiationTurn(
            id = Ids.newId(),
            runId = run.id,
            roomId = roomId,
            turnNumber = turnNumber,
            partyUserId = partyUserId,
            partyDisplayName = participant.displayName,
            publicMessage = output.publicMessage?.trim()?.takeIf { it.isNotEmpty() },
            proposal = output.proposal,
            questionsForOwnUser = output.questionsForOwnUser,
            questionsForOtherParty = output.questionsForOtherParty,
            sharedFactsUsed = output.sharedFactsUsed,
            privateDataReferencedInternally = output.privateDataReferencedInternally,
            requiresUserApproval = output.requiresUserApproval,
            modelStopReason = modelStopReason,
            llmProvider = response.provider,
            llmModel = response.model,
            promptTemplateId = NegotiationContextBuilder.TEMPLATE_ID,
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
            latencyMs = response.latencyMs,
            createdAt = Instant.now(),
        )

        val newQuestions = output.questionsForOwnUser.map { text ->
            Question(
                id = Ids.newId(),
                roomId = roomId,
                runId = run.id,
                turnId = turn.id,
                toUserId = partyUserId,
                text = text,
                createdAt = Instant.now(),
            )
        }

        transactionTemplate.execute {
            turns.insert(turn)
            newQuestions.forEach { questions.insert(it) }
            run.turnCount = turnNumber
            run.tokensUsed += response.inputTokens + response.outputTokens
            run.updatedAt = Instant.now()
            runs.save(run)
            auditService.append(
                roomId = roomId,
                actorType = ActorType.AI,
                actorId = partyUserId,
                action = "NEGOTIATION_TURN_RECORDED",
                targetType = "NegotiationTurn",
                targetId = turn.id,
                metadata = mapOf(
                    "turn" to turnNumber.toString(),
                    "stopReason" to modelStopReason.name,
                    "provider" to response.provider,
                ),
            )
            outboxService.enqueue(
                roomId, "NEGOTIATION_TURN_COMPLETED",
                mapOf("resourceId" to run.id, "resourceVersion" to turnNumber),
            )
            newQuestions.forEach { question ->
                outboxService.enqueue(
                    roomId, "QUESTION_CREATED",
                    mapOf("resourceId" to question.id, "audienceUserIds" to listOf(question.toUserId), "actorUserId" to null),
                )
            }
        }

        return when (modelStopReason) {
            RunStopReason.NONE -> TurnOutcome.Continue
            RunStopReason.MISSING_INFO ->
                if (newQuestions.isEmpty()) TurnOutcome.Stop(RunStopReason.MISSING_INFO) else TurnOutcome.WaitForUser
            else -> TurnOutcome.Stop(modelStopReason)
        }
    }

    // ---------------- terminal transitions ----------------

    private fun completeRun(run: NegotiationRun, reason: RunStopReason) {
        run.status = RunStatus.COMPLETED
        run.active = false
        run.stopReason = reason
        run.result = buildResult(run, reason)
        run.updatedAt = Instant.now()
        runs.save(run)

        transactionTemplate.execute {
            auditService.append(
                run.roomId, ActorType.SYSTEM, null, "NEGOTIATION_STOPPED", "NegotiationRun", run.id,
                metadata = mapOf("stopReason" to reason.name, "turns" to run.turnCount.toString()),
            )
            outboxService.enqueue(run.roomId, "NEGOTIATION_TURN_COMPLETED", mapOf("resourceId" to run.id))
            if (reason == RunStopReason.POSSIBLE_AGREEMENT && rooms.find(run.roomId)?.status == RoomStatus.ACTIVE) {
                lifecycle.transition(run.roomId, RoomStatus.PROPOSAL_READY, ActorType.SYSTEM, null, "assistants reached a possible agreement")
            }
        }
    }

    private fun waitForUser(runId: String, roomId: String) {
        val run = runs.findById(runId).orElse(null) ?: return
        run.status = RunStatus.WAITING_FOR_USER
        run.updatedAt = Instant.now()
        runs.save(run)
        transactionTemplate.execute {
            auditService.append(roomId, ActorType.SYSTEM, null, "NEGOTIATION_WAITING", "NegotiationRun", runId)
            outboxService.enqueue(roomId, "NEGOTIATION_WAITING_FOR_USER", mapOf("resourceId" to runId))
            if (rooms.find(roomId)?.status == RoomStatus.ACTIVE) {
                lifecycle.transition(roomId, RoomStatus.WAITING_FOR_USER, ActorType.SYSTEM, null, "assistant needs an answer")
            }
        }
    }

    private fun failRun(runId: String, roomId: String, note: String, reason: RunStopReason = RunStopReason.PROVIDER_ERROR) {
        val run = runs.findById(runId).orElse(null) ?: return
        run.status = RunStatus.FAILED
        run.active = false
        run.stopReason = reason
        run.failureNote = note
        run.result = buildResult(run, reason)
        run.updatedAt = Instant.now()
        runs.save(run)
        transactionTemplate.execute {
            auditService.append(
                roomId, ActorType.SYSTEM, null, "NEGOTIATION_STOPPED", "NegotiationRun", runId,
                metadata = mapOf("stopReason" to reason.name, "note" to note),
            )
            outboxService.enqueue(roomId, "NEGOTIATION_TURN_COMPLETED", mapOf("resourceId" to runId))
        }
    }

    private fun buildResult(run: NegotiationRun, reason: RunStopReason): RoundResult {
        val allTurns = turns.findByRunIdAndRoomIdOrderByTurnNumberAsc(run.id, run.roomId)
        val proposals = allTurns.mapNotNull { it.proposal }
        val last = proposals.lastOrNull()
        return RoundResult(
            agreedPoints = if (reason == RunStopReason.POSSIBLE_AGREEMENT) last?.terms.orEmpty() else emptyList(),
            unresolvedPoints = last?.openIssues.orEmpty(),
            proposalsConsidered = proposals.map { it.title }.distinct(),
            assumptions = last?.assumptions.orEmpty(),
            recommendedProposal = if (reason == RunStopReason.POSSIBLE_AGREEMENT) last else null,
            stopReason = reason,
        )
    }

    private fun partyUserIds(roomId: String): List<String> =
        participants.activeParticipants(roomId)
            .filter { ParticipantRole.PARTY in it.roles || ParticipantRole.OWNER in it.roles }
            .map { it.userId }
            .distinct()
}

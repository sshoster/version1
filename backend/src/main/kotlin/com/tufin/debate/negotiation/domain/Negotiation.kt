package com.tufin.debate.negotiation.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class RunStatus { RUNNING, WAITING_FOR_USER, PAUSED, COMPLETED, FAILED }

/** Why a run stopped — superset of the model-reportable reasons (design doc §6.4). */
enum class RunStopReason {
    NONE,
    MISSING_INFO,
    NEW_CONCESSION,
    SENSITIVE_DISCLOSURE,
    POSSIBLE_AGREEMENT,
    DEADLOCK,
    MAX_TURNS,
    SAFETY,
    BUDGET_EXCEEDED,
    PROVIDER_ERROR,
}

/**
 * A party's guidance to their own assistant: goals, hard boundaries, flexibility. Private to its
 * owner (like private messages) and stored encrypted via FieldCipher.
 */
@Document("ai_agent_profiles")
class AiAgentProfile(
    @Id val id: String,
    val roomId: String,
    val userId: String,
    /** Encrypted at rest. */
    var goals: String = "",
    var boundaries: String = "",
    var flexibility: String = "",
    val schemaVersion: Int = 1,
    val createdAt: Instant,
    var updatedAt: Instant,
)

data class TurnProposal(
    val title: String = "",
    val terms: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val openIssues: List<String> = emptyList(),
)

/** Human-readable outcome of a round (design doc §6.5), embedded in the run. */
data class RoundResult(
    val agreedPoints: List<String> = emptyList(),
    val unresolvedPoints: List<String> = emptyList(),
    val proposalsConsidered: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val recommendedProposal: TurnProposal? = null,
    val stopReason: RunStopReason = RunStopReason.NONE,
)

@Document("negotiation_runs")
class NegotiationRun(
    @Id val id: String,
    val roomId: String,
    val startedByUserId: String,
    var status: RunStatus = RunStatus.RUNNING,
    /** true while the run blocks new runs in the room (unique partial index enforces one). */
    var active: Boolean = true,
    val maxTurns: Int,
    var turnCount: Int = 0,
    var tokensUsed: Int = 0,
    var stopReason: RunStopReason = RunStopReason.NONE,
    var result: RoundResult? = null,
    var failureNote: String? = null,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
    var updatedAt: Instant,
    @Version var version: Long? = null,
)

/**
 * One assistant turn. `publicMessage` and the proposal form the shared transcript visible to both
 * parties; everything the model consumed privately never leaves its own context.
 */
@Document("negotiation_turns")
class NegotiationTurn(
    @Id val id: String,
    val runId: String,
    val roomId: String,
    val turnNumber: Int,
    /** The party whose assistant produced this turn. */
    val partyUserId: String,
    val partyDisplayName: String,
    val publicMessage: String?,
    val proposal: TurnProposal?,
    val questionsForOwnUser: List<String> = emptyList(),
    val questionsForOtherParty: List<String> = emptyList(),
    val sharedFactsUsed: List<String> = emptyList(),
    val privateDataReferencedInternally: Boolean = false,
    val requiresUserApproval: Boolean = true,
    val modelStopReason: RunStopReason = RunStopReason.NONE,
    // Operational metadata only — never chain-of-thought.
    val llmProvider: String,
    val llmModel: String,
    val promptTemplateId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val latencyMs: Long,
    val createdAt: Instant,
)

enum class QuestionStatus { OPEN, ANSWERED }

/** A question an assistant asks its OWN user; the answer joins that user's private context. */
@Document("questions")
class Question(
    @Id val id: String,
    val roomId: String,
    val runId: String,
    val turnId: String,
    val toUserId: String,
    val text: String,
    /** One-tap answer suggestions offered by the assistant alongside the question. */
    val suggestedOptions: List<String> = emptyList(),
    var status: QuestionStatus = QuestionStatus.OPEN,
    /** Encrypted at rest (private to the asked user and their assistant). */
    var answerText: String? = null,
    var answeredAt: Instant? = null,
    val createdAt: Instant,
)

package com.tufin.debate.negotiation.application

import com.tufin.debate.discussion.domain.DiscussionRoom
import com.tufin.debate.messaging.application.SharedFact
import com.tufin.debate.negotiation.domain.AiAgentProfile
import com.tufin.debate.negotiation.domain.NegotiationTurn
import com.tufin.debate.negotiation.domain.Question
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

/**
 * Builds one party's agent context strictly from server state (trust-model §5): own profile and
 * private notes, ONLY the shared facts that party may see, and the shared transcript. Nothing from
 * the other party's private space can ever enter, because it is never passed in.
 */
@Service
class NegotiationContextBuilder {

    companion object {
        const val TEMPLATE_ID = "negotiation/v1"
    }

    private val systemTemplate: String by lazy {
        ClassPathResource("prompts/negotiation/v1.md").inputStream.readBytes().toString(Charsets.UTF_8)
    }

    fun systemPrompt(room: DiscussionRoom, displayName: String): String =
        systemTemplate
            .replace("{{roomTitle}}", room.title)
            .replace("{{displayName}}", displayName)

    fun userPrompt(
        room: DiscussionRoom,
        profile: AiAgentProfile?,
        privateNotes: List<String>,
        sharedFacts: List<SharedFact>,
        transcript: List<NegotiationTurn>,
        answeredQuestions: List<Question>,
        currentTurn: Int,
    ): String = buildString {
        appendLine("DISCUSSION_TITLE: ${room.title}")
        appendLine("OBJECTIVE: ${room.objective ?: "(none provided)"}")
        appendLine()
        appendLine("OWN_PROFILE (private to your user):")
        appendLine("  goals: ${profile?.goals.orEmpty().ifBlank { "(not provided)" }}")
        appendLine("  boundaries: ${profile?.boundaries.orEmpty().ifBlank { "(not provided)" }}")
        appendLine("  flexibility: ${profile?.flexibility.orEmpty().ifBlank { "(not provided)" }}")
        appendLine()
        appendLine("PRIVATE_NOTES (your user's private messages; never reveal):")
        if (privateNotes.isEmpty()) appendLine("  (none)")
        privateNotes.takeLast(20).forEach { appendLine("  - ${it.replace('\n', ' ')}") }
        appendLine()
        appendLine("SHARED_FACTS (the only content you may cite; format: id | author | text):")
        if (sharedFacts.isEmpty()) appendLine("  (none)")
        sharedFacts.forEach { appendLine("  - ${it.versionId} | ${it.authorDisplayName} | ${it.text.replace('\n', ' ')}") }
        appendLine()
        appendLine("TRANSCRIPT (assistant exchange so far):")
        if (transcript.isEmpty()) appendLine("  (no turns yet)")
        transcript.forEach { turn ->
            appendLine("  [turn ${turn.turnNumber} | assistant of ${turn.partyDisplayName}]")
            turn.publicMessage?.let { appendLine("    message: ${it.replace('\n', ' ')}") }
            turn.proposal?.let {
                appendLine("    proposal: ${it.title} | terms: ${it.terms.joinToString("; ")} | open: ${it.openIssues.joinToString("; ")}")
            }
            if (turn.questionsForOtherParty.isNotEmpty()) {
                appendLine("    questions to the other party: ${turn.questionsForOtherParty.joinToString(" | ")}")
            }
        }
        appendLine()
        appendLine("ANSWERED_QUESTIONS (answers from YOUR user; private):")
        if (answeredQuestions.isEmpty()) appendLine("  (none)") else {
            answeredQuestions.forEach { appendLine("  - Q: ${it.text} | A: ${it.answerText.orEmpty()}") }
        }
        appendLine()
        appendLine("PROPOSAL_ON_TABLE: ${if (transcript.any { it.proposal != null }) "yes" else "no"}")
        appendLine("CURRENT_TURN: $currentTurn")
    }
}

package com.bridge.debate.negotiation.application

import com.bridge.debate.discussion.domain.DiscussionRoom
import com.bridge.debate.llm.application.LlmProvider
import com.bridge.debate.llm.application.LlmRequest
import com.bridge.debate.negotiation.domain.NegotiationTurn
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

data class CycleHandoff(val agreedPoints: List<String> = emptyList(), val openIssues: List<String> = emptyList())

/**
 * When a cycle ends WITHOUT a concluded agreement, distill the shared transcript into what was
 * already agreed and what stayed open. The result is stored on the run (visible to both parties —
 * it is derived only from the shared exchange) and seeds the next cycle's context, so the
 * assistants resume instead of restarting. Best-effort: any failure returns null and the caller
 * falls back to its heuristic.
 */
@Service
class CycleHandoffSummarizer(private val llmProvider: LlmProvider) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    private val systemTemplate: String by lazy {
        ClassPathResource("prompts/negotiation-handoff/v1.md").inputStream.readBytes().toString(Charsets.UTF_8)
    }

    companion object {
        const val TEMPLATE_ID = "negotiation-handoff/v1"
    }

    fun summarize(room: DiscussionRoom, transcript: List<NegotiationTurn>): CycleHandoff? {
        if (transcript.none { it.publicMessage != null || it.proposal != null }) return null
        return try {
            val response = runBlocking {
                llmProvider.generate(
                    LlmRequest(
                        templateId = TEMPLATE_ID,
                        expectsJson = true,
                        system = systemTemplate.replace("{{roomTitle}}", room.title),
                        user = sharedTranscript(transcript),
                    ),
                )
            }
            val cleaned = response.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val handoff = json.readValue<CycleHandoff>(cleaned)
            CycleHandoff(
                agreedPoints = handoff.agreedPoints.map { it.trim() }.filter { it.isNotEmpty() }.take(8),
                openIssues = handoff.openIssues.map { it.trim() }.filter { it.isNotEmpty() }.take(8),
            )
        } catch (e: Exception) {
            log.warn("Cycle handoff summary failed: {}", e.javaClass.simpleName)
            null
        }
    }

    /** Only the SHARED exchange — content both parties already see. No private context enters. */
    private fun sharedTranscript(transcript: List<NegotiationTurn>): String = buildString {
        appendLine("SHARED_TRANSCRIPT (both parties see all of this):")
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
    }
}

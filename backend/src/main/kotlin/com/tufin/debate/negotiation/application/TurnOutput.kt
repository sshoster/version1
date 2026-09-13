package com.tufin.debate.negotiation.application

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.tufin.debate.negotiation.domain.RunStopReason
import com.tufin.debate.negotiation.domain.TurnProposal

/** Schema-validated structured output of one assistant turn (design doc §6.3). */
data class AgentTurnOutput(
    val publicMessage: String? = null,
    val proposal: TurnProposal? = null,
    val questionsForOwnUser: List<String> = emptyList(),
    val questionsForOtherParty: List<String> = emptyList(),
    val sharedFactsUsed: List<String> = emptyList(),
    val privateDataReferencedInternally: Boolean = false,
    val requiresUserApproval: Boolean = true,
    val stopReason: String = "NONE",
)

class TurnOutputException(message: String) : RuntimeException(message)

object TurnOutputParser {

    /** Reasons a model is allowed to report itself. */
    private val MODEL_REPORTABLE = setOf(
        RunStopReason.NONE, RunStopReason.MISSING_INFO, RunStopReason.NEW_CONCESSION,
        RunStopReason.SENSITIVE_DISCLOSURE, RunStopReason.POSSIBLE_AGREEMENT,
        RunStopReason.DEADLOCK, RunStopReason.SAFETY,
    )

    private val mapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun parse(raw: String): Pair<AgentTurnOutput, RunStopReason> {
        val json = stripFences(raw.trim())
        val output = try {
            mapper.readValue<AgentTurnOutput>(json)
        } catch (e: Exception) {
            throw TurnOutputException("Turn output is not valid JSON matching the schema: ${e.message}")
        }
        val stopReason = RunStopReason.entries.find { it.name == output.stopReason }
            ?.takeIf { it in MODEL_REPORTABLE }
            ?: throw TurnOutputException("Unknown or non-reportable stopReason '${output.stopReason}'")
        if (output.publicMessage != null && output.publicMessage.length > 4000) {
            throw TurnOutputException("publicMessage exceeds the allowed length")
        }
        if (output.questionsForOwnUser.size > 5 || output.questionsForOtherParty.size > 5) {
            throw TurnOutputException("Too many questions in a single turn")
        }
        return output to stopReason
    }

    private fun stripFences(text: String): String {
        if (!text.startsWith("```")) return text
        return text
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}

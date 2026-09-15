package com.bridge.debate.llm.infrastructure

import com.bridge.debate.llm.application.LlmProvider
import com.bridge.debate.llm.application.LlmRequest
import com.bridge.debate.llm.application.LlmResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Deterministic local provider: no API key, no network, same input → same output. Default for
 * development, integration tests, and E2E (design doc §6.6). The OpenAI adapter arrives in
 * Phase 3 behind the same interface.
 */
@Component
@ConditionalOnProperty("app.llm.provider", havingValue = "fake", matchIfMissing = true)
class FakeLlmProvider : LlmProvider {

    override val providerName = "fake"
    override val modelName = "fake-deterministic-1"

    private val json = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val text = when {
            request.templateId.startsWith("draft/") -> draft(request.user)
            request.templateId.startsWith("negotiation-handoff/") -> handoffSummary()
            request.templateId.startsWith("negotiation/") -> negotiationTurn(request.user)
            request.templateId.startsWith("summary/") -> summary(request.user)
            request.templateId.startsWith("agreement/") -> agreementDraft(request.user)
            else -> "תשובה לדוגמה עבור: ${request.user.take(120)}"
        }
        return LlmResponse(
            text = text,
            provider = providerName,
            model = modelName,
            inputTokens = request.user.length / 4,
            outputTokens = text.length / 4,
            latencyMs = 1,
        )
    }

    /**
     * Deterministic negotiation behavior, steerable by SCENARIO markers anywhere in the context
     * (tests place them in the room objective):
     *  - SCENARIO:MISSING_INFO — asks the own user a question (once), then proceeds normally.
     *  - SCENARIO:SENSITIVE  — stops with SENSITIVE_DISCLOSURE.
     *  - SCENARIO:LOOP       — never converges (exercises MAX_TURNS).
     *  - SCENARIO:INVALID_FACT — cites a nonexistent shared-fact id (exercises server rejection).
     *  - SCENARIO:PROVIDER_ERROR — throws (exercises clean failure handling).
     *  - default             — proposes on the first turn, accepts once a proposal is on the table.
     */
    private fun negotiationTurn(context: String): String {
        if (context.contains("SCENARIO:PROVIDER_ERROR")) {
            throw IllegalStateException("Simulated provider outage")
        }

        val proposalOnTable = context.contains("PROPOSAL_ON_TABLE: yes")
        val answeredQuestions = !context.contains("ANSWERED_QUESTIONS (answers from YOUR user; private):\n  (none)")
        val firstFactId = Regex("SHARED_FACTS[^\\n]*\\n  - ([^ |]+) \\|").find(context)?.groupValues?.get(1)

        val output: Map<String, Any?> = when {
            context.contains("SCENARIO:SENSITIVE") -> mapOf(
                "publicMessage" to null,
                "proposal" to null,
                "stopReason" to "SENSITIVE_DISCLOSURE",
            )

            // Only the opening assistant asks its user once; afterwards the flow proceeds normally.
            context.contains("SCENARIO:MISSING_INFO") && !answeredQuestions && context.contains("CURRENT_TURN: 1") -> mapOf(
                "publicMessage" to null,
                "proposal" to null,
                "questionsForOwnUser" to listOf(
                    mapOf(
                        "text" to "מה הסכום המקסימלי שנוח לך להציע?",
                        "options" to listOf("עד 3,000 ₪", "עד 3,500 ₪", "גמיש, תלוי בתנאים"),
                    ),
                ),
                "stopReason" to "MISSING_INFO",
            )

            context.contains("SCENARIO:INVALID_FACT") -> mapOf(
                "publicMessage" to "מסתמך על עובדה",
                "proposal" to null,
                "sharedFactsUsed" to listOf("00000000-0000-0000-0000-000000000000"),
                "stopReason" to "NONE",
            )

            context.contains("SCENARIO:LOOP") -> mapOf(
                // Echo whether a previous cycle's summary reached this context (asserted in tests).
                "publicMessage" to if (context.contains("PREVIOUS_CYCLE_SUMMARY")) {
                    "ממשיכים מהנקודות הפתוחות של הסבב הקודם."
                } else {
                    "נקודה נוספת לחידוד הדדי."
                },
                "proposal" to null,
                "sharedFactsUsed" to listOfNotNull(firstFactId),
                "stopReason" to "NONE",
            )

            proposalOnTable -> mapOf(
                "publicMessage" to "ההצעה נראית הוגנת לצד שלי. ממליץ/ה לאשר אותה.",
                "proposal" to mapOf(
                    "title" to "הסכמה מוצעת",
                    "terms" to listOf("הצדדים מאשרים את ההצעה שעל השולחן"),
                    "assumptions" to listOf("שני הצדדים מאשרים באופן עצמאי"),
                    "openIssues" to emptyList<String>(),
                ),
                "sharedFactsUsed" to listOfNotNull(firstFactId),
                "requiresUserApproval" to true,
                "stopReason" to "POSSIBLE_AGREEMENT",
            )

            else -> mapOf(
                "publicMessage" to "מציע/ה נקודת פתיחה מאוזנת לשני הצדדים.",
                "proposal" to mapOf(
                    "title" to "הצעת ביניים",
                    "terms" to listOf("פשרה שוויונית על בסיס העובדות המשותפות"),
                    "assumptions" to listOf("העובדות המשותפות מוסכמות"),
                    "openIssues" to listOf("פרטים סופיים"),
                ),
                "sharedFactsUsed" to listOfNotNull(firstFactId),
                "stopReason" to "NONE",
            )
        }

        val base = mapOf(
            "publicMessage" to null,
            "proposal" to null,
            "questionsForOwnUser" to emptyList<String>(),
            "questionsForOtherParty" to emptyList<String>(),
            "sharedFactsUsed" to emptyList<String>(),
            "privateDataReferencedInternally" to false,
            "requiresUserApproval" to true,
            "stopReason" to "NONE",
        )
        return json.writeValueAsString(base + output)
    }

    /** Deterministic discussion summary built from the provided material. */
    /** Deterministic handoff summary for an unfinished assistants' cycle. */
    private fun handoffSummary(): String = json.writeValueAsString(
        mapOf(
            "agreedPoints" to listOf("שני הצדדים מעוניינים להגיע להסכמה"),
            "openIssues" to listOf("טרם סוכמו התנאים הסופיים"),
        ),
    )

    private fun summary(material: String): String {
        val statements = Regex("SHARED_STATEMENTS[^\\n]*\\n((?:  - [^\\n]+\\n?)*)").find(material)
            ?.groupValues?.get(1)?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        val proposals = Regex("PROPOSALS:\\n((?:  - [^\\n]+\\n?)*)").find(material)
            ?.groupValues?.get(1)?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        return buildString {
            appendLine("## סיכום הדיון")
            appendLine()
            appendLine("### הנושאים והעמדות")
            statements.forEach { appendLine(it.trim().removePrefix("- ")) }
            appendLine()
            appendLine("### הצעות שנדונו")
            if (proposals.isEmpty()) appendLine("(עוד אין הצעות)")
            proposals.forEach { appendLine(it.trim().removePrefix("- ")) }
            appendLine()
            appendLine("### נקודות פתוחות")
            appendLine("הצדדים ממשיכים לדייק את הפרטים.")
        }.trim()
    }

    /** Deterministic agreement draft built from the approved understandings. */
    private fun agreementDraft(material: String): String {
        val title = Regex("DISCUSSION_TITLE: ([^\\n]+)").find(material)?.groupValues?.get(1) ?: "ההסכם"
        val parties = Regex("PARTIES: ([^\\n]+)").find(material)?.groupValues?.get(1) ?: ""
        val terms = Regex("- term: ([^\\n]+)").findAll(material).map { it.groupValues[1] }.toList()
        val assumptions = Regex("- assumption: ([^\\n]+)").findAll(material).map { it.groupValues[1] }.toList()
        return buildString {
            appendLine("# טיוטת הסכם — $title")
            appendLine()
            appendLine("בין: $parties")
            appendLine()
            appendLine("## סעיפים")
            terms.forEachIndexed { index, term -> appendLine("${index + 1}. $term") }
            if (assumptions.isNotEmpty()) {
                appendLine()
                appendLine("## הנחות")
                assumptions.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("מסמך זה משקף את ההבנות שאושרו על ידי הצדדים בפלטפורמה.")
        }.trim()
    }

    /** Calm, structured rewording of the user's text — deterministic on purpose. */
    private fun draft(userText: String): String {
        val cleaned = userText
            .replace("!", ".")
            .replace(Regex("\\?{2,}"), "?")
            .trim()
        return """
            |נוסח מוצע:
            |$cleaned
            |
            |חשוב לי שנגיע להבנה שנוחה לשנינו. אשמח לשמוע איך זה נשמע לך, ואם יש משהו שכדאי לדייק.
        """.trimMargin()
    }
}

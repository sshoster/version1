package com.tufin.debate.llm.infrastructure

import com.tufin.debate.llm.application.LlmProvider
import com.tufin.debate.llm.application.LlmRequest
import com.tufin.debate.llm.application.LlmResponse
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

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val text = when {
            request.templateId.startsWith("draft/") -> draft(request.user)
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

package com.bridge.debate.llm.application

/**
 * Provider-independent LLM port (docs/architecture.md §7). Domain and orchestration code depend
 * only on this interface; adapters (OpenAI in Phase 3, Fake for dev/tests) live in infrastructure.
 */
interface LlmProvider {
    suspend fun generate(request: LlmRequest): LlmResponse
    val providerName: String
    val modelName: String
}

data class LlmRequest(
    /** Versioned prompt-template id, e.g. "draft/v1" — recorded as operational metadata. */
    val templateId: String,
    val system: String,
    val user: String,
    val maxOutputTokens: Int = 1024,
    /** When true the adapter enforces a JSON response format (the prompt must mention JSON). */
    val expectsJson: Boolean = false,
)

data class LlmResponse(
    val text: String,
    val provider: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val latencyMs: Long,
)

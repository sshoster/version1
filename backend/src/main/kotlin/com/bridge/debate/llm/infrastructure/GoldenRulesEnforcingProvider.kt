package com.bridge.debate.llm.infrastructure

import com.bridge.debate.llm.application.GoldenRulesService
import com.bridge.debate.llm.application.LlmProvider
import com.bridge.debate.llm.application.LlmRequest
import com.bridge.debate.llm.application.LlmResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * The single choke point for the platform golden rules: every LLM call — private drafts, calm
 * rewording, negotiation turns, summaries, agreement drafts — flows through here, and the rules
 * are prepended to the system prompt. No call site can forget them, and nothing a participant
 * writes can remove them.
 */
@Component
@Primary
class GoldenRulesEnforcingProvider(
    private val rules: GoldenRulesService,
    private val providers: ObjectProvider<LlmProvider>,
) : LlmProvider {

    /** The active adapter (fake or openai) — resolved lazily, excluding this decorator itself. */
    private val delegate: LlmProvider by lazy {
        providers.stream().filter { it !== this }.findFirst()
            .orElseThrow { IllegalStateException("No LlmProvider adapter is configured") }
    }

    override suspend fun generate(request: LlmRequest): LlmResponse =
        delegate.generate(request.copy(system = rules.current() + "\n\n" + request.system))

    override val providerName: String get() = delegate.providerName
    override val modelName: String get() = delegate.modelName
}

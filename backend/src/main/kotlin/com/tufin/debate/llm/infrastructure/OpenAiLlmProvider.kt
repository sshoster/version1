package com.tufin.debate.llm.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tufin.debate.llm.application.LlmProvider
import com.tufin.debate.llm.application.LlmRequest
import com.tufin.debate.llm.application.LlmResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenAI chat-completions adapter (owner decision, docs/architecture.md §2). Activated with
 * app.llm.provider=openai + OPENAI_API_KEY. JSON-object response format; the orchestrator still
 * schema-validates everything server-side. Retries with backoff on 429/5xx and a small circuit
 * breaker guard against hammering a failing API. Content is never logged.
 */
@Component
@ConditionalOnProperty("app.llm.provider", havingValue = "openai")
class OpenAiLlmProvider(
    @Value("\${app.llm.openai.api-key:}") apiKey: String,
    @param:Value("\${app.llm.openai.model:gpt-5.6-luna}") private val model: String,
    @Value("\${app.llm.openai.base-url:https://api.openai.com/v1}") baseUrl: String,
    @Value("\${app.llm.openai.timeout-seconds:60}") timeoutSeconds: Long,
    private val objectMapper: ObjectMapper,
) : LlmProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val BREAKER_THRESHOLD = 5
        private val BREAKER_COOLDOWN: Duration = Duration.ofSeconds(60)
    }

    init {
        require(apiKey.isNotBlank()) { "OPENAI_API_KEY is required when app.llm.provider=openai" }
    }

    override val providerName = "openai"
    override val modelName get() = model

    private val consecutiveFailures = AtomicInteger(0)
    private val breakerOpenedAtMs = AtomicLong(0)

    private val client: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Authorization", "Bearer $apiKey")
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(10))
                setReadTimeout(Duration.ofSeconds(timeoutSeconds))
            },
        )
        .build()

    override suspend fun generate(request: LlmRequest): LlmResponse {
        checkBreaker()
        val body = buildMap {
            put("model", model)
            put("max_completion_tokens", request.maxOutputTokens)
            put(
                "messages",
                listOf(
                    mapOf("role" to "system", "content" to request.system),
                    mapOf("role" to "user", "content" to request.user),
                ),
            )
            // Only for structured calls: OpenAI requires the prompt itself to mention JSON.
            if (request.expectsJson) put("response_format", mapOf("type" to "json_object"))
        }

        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val started = Instant.now()
                val response = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String::class.java)
                    ?: throw IllegalStateException("Empty response from provider")
                val json: JsonNode = objectMapper.readTree(response)
                val text = json["choices"]?.get(0)?.get("message")?.get("content")?.asText()
                    ?: throw IllegalStateException("Response missing message content")
                consecutiveFailures.set(0)
                return LlmResponse(
                    text = text,
                    provider = providerName,
                    model = json["model"]?.asText() ?: model,
                    inputTokens = json["usage"]?.get("prompt_tokens")?.asInt() ?: 0,
                    outputTokens = json["usage"]?.get("completion_tokens")?.asInt() ?: 0,
                    latencyMs = Duration.between(started, Instant.now()).toMillis(),
                )
            } catch (e: Exception) {
                lastError = e
                log.warn("OpenAI call failed (attempt {}/{}): {}", attempt + 1, MAX_ATTEMPTS, e.message)
                if (attempt < MAX_ATTEMPTS - 1) {
                    Thread.sleep((500L shl attempt) + (0..250).random())
                }
            }
        }

        if (consecutiveFailures.incrementAndGet() >= BREAKER_THRESHOLD) {
            breakerOpenedAtMs.set(System.currentTimeMillis())
            log.warn("OpenAI circuit breaker opened after {} consecutive failures", BREAKER_THRESHOLD)
        }
        throw IllegalStateException("LLM provider call failed after $MAX_ATTEMPTS attempts", lastError)
    }

    private fun checkBreaker() {
        val openedAt = breakerOpenedAtMs.get()
        if (openedAt == 0L) return
        if (System.currentTimeMillis() - openedAt > BREAKER_COOLDOWN.toMillis()) {
            breakerOpenedAtMs.set(0)
            consecutiveFailures.set(0)
            return
        }
        throw IllegalStateException("LLM provider temporarily unavailable (circuit breaker open)")
    }
}

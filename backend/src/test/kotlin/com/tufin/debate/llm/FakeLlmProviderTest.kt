package com.tufin.debate.llm

import com.tufin.debate.llm.application.LlmRequest
import com.tufin.debate.llm.infrastructure.FakeLlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeLlmProviderTest {

    private val provider = FakeLlmProvider()

    private fun draft(text: String) = runBlocking {
        provider.generate(LlmRequest(templateId = "draft/v1", system = "s", user = text))
    }

    @Test
    fun `same input produces the same output`() {
        assertEquals(draft("אני כועס!!").text, draft("אני כועס!!").text)
    }

    @Test
    fun `draft is calm and structured`() {
        val result = draft("תשלם לי עכשיו!!!")
        assertTrue(result.text.startsWith("נוסח מוצע:"))
        assertFalse(result.text.contains("!"), "exclamation marks are softened")
    }

    @Test
    fun `metadata identifies the fake provider`() {
        val result = draft("שלום")
        assertEquals("fake", result.provider)
        assertTrue(result.latencyMs >= 0)
    }
}

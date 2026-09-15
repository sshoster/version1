package com.bridge.debate.negotiation

import com.bridge.debate.negotiation.application.TurnOutputException
import com.bridge.debate.negotiation.application.TurnOutputParser
import com.bridge.debate.negotiation.domain.RunStopReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TurnOutputParserTest {

    @Test
    fun `parses a complete turn`() {
        val (output, stop) = TurnOutputParser.parse(
            """
            {"publicMessage":"שלום","proposal":{"title":"פ","terms":["א"],"assumptions":[],"openIssues":[]},
             "questionsForOwnUser":[],"questionsForOtherParty":["ש?"],"sharedFactsUsed":["id1"],
             "privateDataReferencedInternally":true,"requiresUserApproval":true,"stopReason":"POSSIBLE_AGREEMENT"}
            """.trimIndent(),
        )
        assertEquals(RunStopReason.POSSIBLE_AGREEMENT, stop)
        assertEquals("שלום", output.publicMessage)
        assertEquals(listOf("id1"), output.sharedFactsUsed)
    }

    @Test
    fun `accepts fenced json and missing optional fields`() {
        val (output, stop) = TurnOutputParser.parse("```json\n{\"stopReason\":\"NONE\"}\n```")
        assertEquals(RunStopReason.NONE, stop)
        assertNull(output.publicMessage)
    }

    @Test
    fun `rejects garbage, unknown reasons, and non-reportable reasons`() {
        assertThrows<TurnOutputException> { TurnOutputParser.parse("hello there") }
        assertThrows<TurnOutputException> { TurnOutputParser.parse("""{"stopReason":"WIN_AT_ALL_COSTS"}""") }
        // MAX_TURNS/BUDGET are decided by the server, never self-reported by the model.
        assertThrows<TurnOutputException> { TurnOutputParser.parse("""{"stopReason":"MAX_TURNS"}""") }
    }

    @Test
    fun `rejects oversized output`() {
        val huge = "א".repeat(5000)
        assertThrows<TurnOutputException> {
            TurnOutputParser.parse("""{"publicMessage":"$huge","stopReason":"NONE"}""")
        }
    }
}

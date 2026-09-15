package com.bridge.debate.agreements

import com.fasterxml.jackson.databind.JsonNode
import com.bridge.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Acceptance tests 19 + 20 (design doc §14): the three outcome types generate with correct labels,
 * and a discussion can be paused, closed, reopened, and reviewed later.
 */
class OutcomesFlowIT : IntegrationTestBase() {

    private lateinit var alice: TestUser
    private lateinit var bob: TestUser
    private lateinit var adam: TestUser // advisor
    private lateinit var olivia: TestUser // observer
    private lateinit var roomId: String

    @BeforeEach
    fun setUpAgreedRoom() {
        alice = registerUser("Alice")
        bob = registerUser("Bob")
        adam = registerUser("Adam")
        olivia = registerUser("Olivia")
        roomId = json(post("/api/v1/rooms", mapOf("title" to "Outcome room", "objective" to "הסכם שכירות"), alice.accessToken))["id"].asText()
        listOf("PARTY" to bob, "ADVISOR" to adam, "OBSERVER" to olivia).forEach { (role, user) ->
            val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to role), alice.accessToken))
            post("/api/v1/invitations/${invite["token"].asText()}/accept", null, user.accessToken)
        }
        // Shared fact visible to everyone relevant → room ACTIVE.
        val body = mapOf("text" to "שכר הדירה הנוכחי 4500", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        val preview = json(post("/api/v1/rooms/$roomId/share-previews", body, alice.accessToken))
        post("/api/v1/rooms/$roomId/shared-items", body + mapOf("previewId" to preview["previewId"].asText()), alice.accessToken)
    }

    private fun agreeOnProposal(): String {
        val proposal = json(
            post(
                "/api/v1/rooms/$roomId/proposals",
                mapOf("title" to "הסכם שכירות מעודכן", "terms" to listOf("שכר דירה 4600 מ-1 בינואר", "חוזה לשנה"), "assumptions" to listOf("מדד יציב")),
                alice.accessToken,
            ),
        )
        val proposalId = proposal["id"].asText()
        val requestId = json(post("/api/v1/rooms/$roomId/proposals/$proposalId/request-approval", null, alice.accessToken))["pendingRequest"]["id"].asText()
        post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", mapOf("expectedVersion" to 1), alice.accessToken)
        post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", mapOf("expectedVersion" to 1), bob.accessToken)
        return proposalId
    }

    // ---- Test 19: all three outcome types with correct labels ----
    @Test
    fun `all three outcomes generate with correct labels`() {
        // Understandings/draft need an all-party-approved proposal first.
        assertEquals(409, post("/api/v1/rooms/$roomId/outcomes/approved-understandings", null, alice.accessToken).statusCode.value())
        assertEquals(409, post("/api/v1/rooms/$roomId/outcomes/agreement-draft", null, alice.accessToken).statusCode.value())

        agreeOnProposal()

        // 1. AI summary: labeled AI-generated, never an approved agreement.
        val summary = json(post("/api/v1/rooms/$roomId/outcomes/summary", null, alice.accessToken))
        assertEquals("DISCUSSION_SUMMARY", summary["type"].asText())
        assertTrue(summary["aiGenerated"].asBoolean())
        assertFalse(summary["draftOnly"].asBoolean())
        assertTrue(summary["text"].asText().contains("סיכום"))
        assertEquals("fake", summary["llmProvider"].asText())

        // 2. Approved understandings: NOT AI; only all-party-approved terms with approval records.
        val understandings = json(post("/api/v1/rooms/$roomId/outcomes/approved-understandings", null, bob.accessToken))
        assertEquals("APPROVED_UNDERSTANDINGS", understandings["type"].asText())
        assertFalse(understandings["aiGenerated"].asBoolean())
        val block = understandings["understandings"][0]
        assertEquals("הסכם שכירות מעודכן", block["title"].asText())
        assertEquals(2, block["approvals"].size(), "both parties' approval records")
        val approvalNames = block["approvals"].map { it["displayName"].asText() }.toSet()
        assertEquals(setOf("Alice", "Bob"), approvalNames)
        assertTrue(block["approvals"][0]["contentHash"].asText().isNotEmpty())
        assertEquals(1, block["approvals"][0]["proposalVersion"].asInt())

        // 3. Agreement draft: AI-generated + draft-only + not legal advice.
        val draft = json(post("/api/v1/rooms/$roomId/outcomes/agreement-draft", null, alice.accessToken))
        assertEquals("AGREEMENT_DRAFT", draft["type"].asText())
        assertTrue(draft["aiGenerated"].asBoolean())
        assertTrue(draft["draftOnly"].asBoolean())
        assertTrue(draft["notLegalAdvice"].asBoolean())
        assertTrue(draft["text"].asText().contains("טיוטת הסכם"))
        assertTrue(draft["text"].asText().contains("שכר דירה 4600"))

        // Listing returns the latest of each type; regeneration bumps versions.
        post("/api/v1/rooms/$roomId/outcomes/summary", null, alice.accessToken)
        val outcomes = json(get("/api/v1/rooms/$roomId/outcomes", alice.accessToken))
        assertEquals(3, outcomes.size())
        val summaryLatest = outcomes.first { it["type"].asText() == "DISCUSSION_SUMMARY" }
        assertEquals(2, summaryLatest["version"].asInt())

        // Advisors may read outcomes (a draft is meant for advisor review); observers may not.
        assertEquals(200, get("/api/v1/rooms/$roomId/outcomes", adam.accessToken).statusCode.value())
        assertEquals(403, get("/api/v1/rooms/$roomId/outcomes", olivia.accessToken).statusCode.value())
        // Observers/advisors cannot generate.
        assertEquals(403, post("/api/v1/rooms/$roomId/outcomes/summary", null, adam.accessToken).statusCode.value())
        assertEquals(403, post("/api/v1/rooms/$roomId/outcomes/summary", null, olivia.accessToken).statusCode.value())
    }

    // ---- Test 20: pause, close, reopen, review later ----
    @Test
    fun `a discussion can be paused, closed, reopened, and reviewed`() {
        assertEquals("ACTIVE", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())

        // Pause blocks sharing; resume restores it.
        assertEquals(200, post("/api/v1/rooms/$roomId/pause", null, bob.accessToken).statusCode.value())
        assertEquals("PAUSED", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())
        val body = mapOf("text" to "ניסיון", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        assertEquals(409, post("/api/v1/rooms/$roomId/share-previews", body, alice.accessToken).statusCode.value())
        assertEquals(200, post("/api/v1/rooms/$roomId/resume", null, bob.accessToken).statusCode.value())

        // Close; content remains reviewable; observers cannot close anything.
        assertEquals(200, post("/api/v1/rooms/$roomId/close", null, alice.accessToken).statusCode.value())
        assertEquals("CLOSED", json(get("/api/v1/rooms/$roomId", bob.accessToken))["status"].asText())
        assertEquals(1, json(get("/api/v1/rooms/$roomId/shared-items", bob.accessToken)).size(), "history stays reviewable")
        assertEquals(200, get("/api/v1/rooms/$roomId/timeline", bob.accessToken).statusCode.value())
        assertEquals(403, post("/api/v1/rooms/$roomId/pause", null, olivia.accessToken).statusCode.value())

        // Reopen is owner-only.
        assertEquals(403, post("/api/v1/rooms/$roomId/reopen", null, bob.accessToken).statusCode.value())
        assertEquals(200, post("/api/v1/rooms/$roomId/reopen", null, alice.accessToken).statusCode.value())
        assertEquals("ACTIVE", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())

        // Illegal transition is a controlled conflict (pausing a closed discussion).
        post("/api/v1/rooms/$roomId/close", null, alice.accessToken)
        assertEquals(409, post("/api/v1/rooms/$roomId/pause", null, alice.accessToken).statusCode.value())
    }
}

package com.bridge.debate.negotiation

import com.fasterxml.jackson.databind.JsonNode
import com.bridge.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Acceptance tests 8, 9, 10, 17, 18 (design doc §14): the automated AI-to-AI discussion uses only
 * shared content, stops on sensitive disclosure / missing info / max turns, provider failure keeps
 * domain state clean, and everything runs deterministically on FakeLlmProvider without a key.
 */
class NegotiationFlowIT : IntegrationTestBase() {

    private data class Room(val roomId: String, val alice: TestUser, val bob: TestUser)

    /** Creates an ACTIVE two-party room with one shared fact and both profiles set. */
    private fun activeRoom(objective: String): Room {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val roomId = json(
            post("/api/v1/rooms", mapOf("title" to "Negotiation", "objective" to objective), alice.accessToken),
        )["id"].asText()
        val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), alice.accessToken))
        post("/api/v1/invitations/${invite["token"].asText()}/accept", null, bob.accessToken)

        // Profiles (private guidance for each assistant).
        put("/api/v1/rooms/$roomId/agent-profile", mapOf("goals" to "הסכם הוגן", "boundaries" to "עד 5000", "flexibility" to "גמישות בתנאי תשלום"), alice.accessToken)
        put("/api/v1/rooms/$roomId/agent-profile", mapOf("goals" to "ודאות", "boundaries" to "לא פחות מ-4000", "flexibility" to "מועדים"), bob.accessToken)

        // One shared fact → INTAKE moves to ACTIVE.
        val body = mapOf("text" to "שכר הדירה הנוכחי הוא 4500", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        val preview = json(post("/api/v1/rooms/$roomId/share-previews", body, alice.accessToken))
        post("/api/v1/rooms/$roomId/shared-items", body + mapOf("previewId" to preview["previewId"].asText()), alice.accessToken)
        assertEquals("ACTIVE", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())
        return Room(roomId, alice, bob)
    }

    private fun put(path: String, requestBody: Any, token: String) =
        rest.exchange(
            path, org.springframework.http.HttpMethod.PUT,
            org.springframework.http.HttpEntity(objectMapper.writeValueAsString(requestBody), jsonHeaders(token)),
            String::class.java,
        )

    private fun startRun(room: Room, maxTurns: Int? = null): String {
        val response = post(
            "/api/v1/rooms/${room.roomId}/negotiation-runs",
            maxTurns?.let { mapOf("maxTurns" to it) } ?: emptyMap<String, Any>(),
            room.alice.accessToken,
        )
        assertEquals(201, response.statusCode.value(), response.body)
        return json(response)["runId"].asText()
    }

    private fun awaitRun(room: Room, runId: String, vararg statuses: String, timeoutSeconds: Int = 30): JsonNode {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val run = json(get("/api/v1/rooms/${room.roomId}/negotiation-runs/$runId", room.alice.accessToken))
            if (run["status"].asText() in statuses) return run
            Thread.sleep(300)
        }
        error("run $runId did not reach ${statuses.toList()} in time")
    }

    // ---- Test 8 + happy path: shared-facts-only negotiation reaching a possible agreement ----
    @Test
    fun `assistants converge on a proposal using only shared facts`() {
        val room = activeRoom("להסכים על שכר דירה")
        val runId = startRun(room)
        val run = awaitRun(room, runId, "COMPLETED")

        assertEquals("POSSIBLE_AGREEMENT", run["stopReason"].asText())
        assertTrue(run["turnCount"].asInt() >= 2)
        assertTrue(run["result"]["recommendedProposal"]["title"].asText().isNotEmpty())
        assertTrue(run["result"]["agreedPoints"].size() > 0)

        // Alternating assistants, and turns carry the transcript.
        val turns = run["turns"]
        assertEquals("Alice", turns[0]["partyDisplayName"].asText())
        assertEquals("Bob", turns[1]["partyDisplayName"].asText())

        // The room advanced to PROPOSAL_READY.
        assertEquals("PROPOSAL_READY", json(get("/api/v1/rooms/${room.roomId}", room.alice.accessToken))["status"].asText())

        // A party may run another round while a proposal is on the table: the room steps back
        // to ACTIVE and the new run completes like the first.
        val secondRunId = startRun(room)
        awaitRun(room, secondRunId, "COMPLETED")
        assertEquals("PROPOSAL_READY", json(get("/api/v1/rooms/${room.roomId}", room.alice.accessToken))["status"].asText())
    }

    // ---- Test 9a: missing info stops the run, question waits for the user, answer resumes ----
    @Test
    fun `missing info pauses for a user answer and resumes to completion`() {
        val room = activeRoom("להסכים על תשלום SCENARIO:MISSING_INFO")
        val runId = startRun(room)
        val waiting = awaitRun(room, runId, "WAITING_FOR_USER")

        assertEquals("WAITING_FOR_USER", json(get("/api/v1/rooms/${room.roomId}", room.alice.accessToken))["status"].asText())
        val openQuestions = waiting["myOpenQuestions"]
        assertEquals(1, openQuestions.size(), "the initiator's assistant asked one question")
        assertTrue(openQuestions[0]["options"].size() >= 2, "the question carries one-tap answer suggestions")

        // Bob has no open questions — the question is private to Alice.
        val bobRun = json(get("/api/v1/rooms/${room.roomId}/negotiation-runs/$runId", room.bob.accessToken))
        assertEquals(0, bobRun["myOpenQuestions"].size())

        // Bob cannot answer Alice's question.
        val questionId = openQuestions[0]["id"].asText()
        assertEquals(
            404,
            post("/api/v1/rooms/${room.roomId}/questions/$questionId/answer", mapOf("text" to "התערבות"), room.bob.accessToken)
                .statusCode.value(),
        )

        // Alice answers; the run resumes automatically and completes.
        assertEquals(
            200,
            post("/api/v1/rooms/${room.roomId}/questions/$questionId/answer", mapOf("text" to "עד 4800"), room.alice.accessToken)
                .statusCode.value(),
        )
        val done = awaitRun(room, runId, "COMPLETED")
        assertEquals("POSSIBLE_AGREEMENT", done["stopReason"].asText())
    }

    // ---- Test 9b: sensitive disclosure stops the run ----
    @Test
    fun `run stops when progress would require private disclosure`() {
        val room = activeRoom("SCENARIO:SENSITIVE נושא רגיש")
        val runId = startRun(room)
        val run = awaitRun(room, runId, "COMPLETED")
        assertEquals("SENSITIVE_DISCLOSURE", run["stopReason"].asText())
        // No proposal was fabricated on the way out.
        assertTrue(run["result"]["recommendedProposal"].isNull)
    }

    // ---- Test 10: the run stops and summarizes at max turns ----
    @Test
    fun `run stops at the turn limit`() {
        val room = activeRoom("SCENARIO:LOOP ללא התכנסות")
        val runId = startRun(room)
        val run = awaitRun(room, runId, "COMPLETED")
        assertEquals("MAX_TURNS", run["stopReason"].asText())
        assertEquals(10, run["turnCount"].asInt(), "default max is 10 AI-to-AI turns")
        assertEquals(10, run["turns"].size())
    }

    // ---- Inconclusive cycles hand off: summary stored on the run, next cycle resumes from it ----
    @Test
    fun `inconclusive cycle stores a handoff summary and the next cycle resumes from it`() {
        val room = activeRoom("SCENARIO:LOOP ללא התכנסות")
        val firstRun = awaitRun(room, startRun(room), "COMPLETED")
        assertEquals("MAX_TURNS", firstRun["stopReason"].asText())

        // The handoff summary (agreed + open points) is stored on the run — both parties see it.
        val result = firstRun["result"]
        assertTrue(result["agreedPoints"].size() > 0, "agreed points distilled from the shared transcript")
        assertTrue(result["unresolvedPoints"].size() > 0, "open issues distilled from the shared transcript")

        // The conclusion is posted to the common chat automatically as a system message —
        // no approval gate, visible to both parties, provenance-labeled SYSTEM_GENERATED.
        val bobChat = json(get("/api/v1/rooms/${room.roomId}/shared-items", room.bob.accessToken))
        val systemNote = bobChat.first { it["origin"].asText() == "SYSTEM_GENERATED" }
        assertTrue(systemNote["text"].asText().contains("סיכום"), "cycle summary posted to the chat")
        assertTrue(systemNote["text"].asText().contains("נקודות שנותרו פתוחות"))

        // The next cycle's assistants receive the summary and continue from it (the fake provider
        // echoes a distinct message when PREVIOUS_CYCLE_SUMMARY is present in its context).
        val secondRun = awaitRun(room, startRun(room), "COMPLETED")
        val firstTurnMessage = secondRun["turns"][0]["publicMessage"].asText()
        assertTrue(
            firstTurnMessage.contains("הסבב הקודם"),
            "second cycle should start from the previous cycle's summary, got: $firstTurnMessage",
        )
    }

    // ---- Fact-reference integrity: citing unshared content kills the run safely ----
    @Test
    fun `citing content outside the party's shared facts stops the run`() {
        val room = activeRoom("SCENARIO:INVALID_FACT")
        val runId = startRun(room)
        val run = awaitRun(room, runId, "FAILED")
        assertEquals("SAFETY", run["stopReason"].asText())
    }

    // ---- Test 17: provider failure leaves clean, retryable state ----
    @Test
    fun `provider failure fails the run cleanly and a new run can start`() {
        val room = activeRoom("SCENARIO:PROVIDER_ERROR")
        val runId = startRun(room)
        val run = awaitRun(room, runId, "FAILED")
        assertEquals("PROVIDER_ERROR", run["stopReason"].asText())

        // The room is still ACTIVE and a retry is possible (the failed run released the lock).
        assertEquals("ACTIVE", json(get("/api/v1/rooms/${room.roomId}", room.alice.accessToken))["status"].asText())
        assertEquals(
            201,
            post("/api/v1/rooms/${room.roomId}/negotiation-runs", emptyMap<String, Any>(), room.alice.accessToken)
                .statusCode.value(),
        )
    }

    // ---- Authorization: only parties may run or read negotiations ----
    @Test
    fun `observers cannot start or read runs`() {
        val room = activeRoom("להסכים")
        val olivia = registerUser("Olivia")
        val invite = json(post("/api/v1/rooms/${room.roomId}/invitations", mapOf("role" to "OBSERVER"), room.alice.accessToken))
        post("/api/v1/invitations/${invite["token"].asText()}/accept", null, olivia.accessToken)

        assertEquals(
            403,
            post("/api/v1/rooms/${room.roomId}/negotiation-runs", emptyMap<String, Any>(), olivia.accessToken)
                .statusCode.value(),
        )
        assertEquals(403, get("/api/v1/rooms/${room.roomId}/negotiation-runs", olivia.accessToken).statusCode.value())

        val mallory = registerUser("Mallory")
        assertEquals(404, get("/api/v1/rooms/${room.roomId}/negotiation-runs", mallory.accessToken).statusCode.value())
    }
}

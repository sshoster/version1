package com.tufin.debate.agreements

import com.fasterxml.jackson.databind.JsonNode
import com.tufin.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Acceptance tests 11, 12, 13, 14, 21, 22 (design doc §14): human-only approvals bound to exact
 * versions, invalidation on revision, idempotent decisions, controlled conflicts, and audit+domain
 * consistency.
 */
class ApprovalFlowIT : IntegrationTestBase() {

    private lateinit var alice: TestUser
    private lateinit var bob: TestUser
    private lateinit var olivia: TestUser // observer
    private lateinit var roomId: String

    @BeforeEach
    fun setUpRoom() {
        alice = registerUser("Alice")
        bob = registerUser("Bob")
        olivia = registerUser("Olivia")
        roomId = json(post("/api/v1/rooms", mapOf("title" to "Agreement room"), alice.accessToken))["id"].asText()
        val party = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), alice.accessToken))
        post("/api/v1/invitations/${party["token"].asText()}/accept", null, bob.accessToken)
        val observer = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "OBSERVER"), alice.accessToken))
        post("/api/v1/invitations/${observer["token"].asText()}/accept", null, olivia.accessToken)

        // Move room to ACTIVE via a first shared message.
        val body = mapOf("text" to "נקודת פתיחה", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        val preview = json(post("/api/v1/rooms/$roomId/share-previews", body, alice.accessToken))
        post("/api/v1/rooms/$roomId/shared-items", body + mapOf("previewId" to preview["previewId"].asText()), alice.accessToken)
    }

    private fun createProposal(): JsonNode {
        val response = post(
            "/api/v1/rooms/$roomId/proposals",
            mapOf("title" to "חלוקת הוצאות", "terms" to listOf("אליס משלמת 60%", "בוב משלם 40%"), "assumptions" to listOf("הכנסות נוכחיות")),
            alice.accessToken,
        )
        assertEquals(201, response.statusCode.value(), response.body)
        return json(response)
    }

    private fun requestApproval(proposalId: String): JsonNode {
        val response = post("/api/v1/rooms/$roomId/proposals/$proposalId/request-approval", null, alice.accessToken)
        assertEquals(200, response.statusCode.value(), response.body)
        return json(response)
    }

    // ---- Tests 12 + 22: independent approvals, full-agreement transition, audit consistency ----
    @Test
    fun `agreement requires every party and everything is audited`() {
        val proposal = createProposal()
        val proposalId = proposal["id"].asText()
        val requested = requestApproval(proposalId)
        val requestId = requested["pendingRequest"]["id"].asText()

        assertEquals(
            "AGREEMENT_PENDING_APPROVAL",
            json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText(),
        )

        // Alice approves — one approval is NOT an agreement (test 12).
        val aliceDecision = post(
            "/api/v1/rooms/$roomId/approval-requests/$requestId/approve",
            mapOf("expectedVersion" to 1),
            alice.accessToken,
        )
        assertEquals(200, aliceDecision.statusCode.value(), aliceDecision.body)
        assertEquals("PENDING", json(aliceDecision)["status"].asText())
        assertEquals(
            "AGREEMENT_PENDING_APPROVAL",
            json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText(),
        )

        // Bob approves — now the same exact version is approved by everyone.
        val bobDecision = post(
            "/api/v1/rooms/$roomId/approval-requests/$requestId/approve",
            mapOf("expectedVersion" to 1),
            bob.accessToken,
        )
        assertEquals("APPROVED", json(bobDecision)["status"].asText())
        assertEquals("AGREED", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())
        assertEquals("AGREED", json(get("/api/v1/rooms/$roomId/proposals/$proposalId", bob.accessToken))["status"].asText())

        // Audit + timeline record the whole path (test 22).
        val audit = json(get("/api/v1/rooms/$roomId/audit", alice.accessToken))
        val actions = audit.map { it["action"].asText() }
        assertTrue("PROPOSAL_CREATED" in actions)
        assertTrue("APPROVAL_REQUESTED" in actions)
        assertTrue(actions.count { it == "APPROVAL_RECORDED" } == 2)

        val timeline = json(get("/api/v1/rooms/$roomId/timeline", bob.accessToken))
        assertTrue(timeline.any { it["action"].asText() == "APPROVAL_RECORDED" })
    }

    // ---- Test 14: idempotent approvals ----
    @Test
    fun `repeating an approval with the same key or decision does not duplicate`() {
        val proposalId = createProposal()["id"].asText()
        val requestId = requestApproval(proposalId)["pendingRequest"]["id"].asText()

        val key = mapOf("Idempotency-Key" to "approve-once")
        val first = post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", mapOf("expectedVersion" to 1), alice.accessToken, key)
        assertEquals(200, first.statusCode.value(), first.body)
        val second = post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", mapOf("expectedVersion" to 1), alice.accessToken, key)
        assertEquals(200, second.statusCode.value(), second.body)

        // Repeating without a key but with the same decision is also safe.
        val third = post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", mapOf("expectedVersion" to 1), alice.accessToken)
        assertEquals(200, third.statusCode.value(), third.body)
        assertEquals(1, json(third)["approvals"].size(), "exactly one recorded approval for Alice")

        // But flipping the decision after deciding is a controlled conflict.
        assertEquals(
            409,
            post("/api/v1/rooms/$roomId/approval-requests/$requestId/reject", null, alice.accessToken).statusCode.value(),
        )
    }

    // ---- Tests 13 + 21: revision invalidates approvals; stale version → controlled conflict ----
    @Test
    fun `revision invalidates pending approvals and stale approvals conflict`() {
        val proposalId = createProposal()["id"].asText()
        val requestId = requestApproval(proposalId)["pendingRequest"]["id"].asText()
        post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", mapOf("expectedVersion" to 1), alice.accessToken)

        // Bob revises instead of approving → version 2, prior request superseded.
        val revised = post(
            "/api/v1/rooms/$roomId/proposals/$proposalId/revisions",
            mapOf("title" to "חלוקת הוצאות מעודכנת", "terms" to listOf("אליס 55%", "בוב 45%")),
            bob.accessToken,
        )
        assertEquals(201, revised.statusCode.value(), revised.body)
        assertEquals(2, json(revised)["currentVersion"].asInt())
        assertTrue(json(revised)["pendingRequest"].isNull, "old request is no longer pending")

        // Bob's approval attempt against the superseded request → controlled 409 (tests 13/21).
        assertEquals(
            409,
            post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", mapOf("expectedVersion" to 1), bob.accessToken)
                .statusCode.value(),
        )

        // New request for v2: a client that still displays v1 gets a controlled conflict (test 21).
        val newRequestId = requestApproval(proposalId)["pendingRequest"]["id"].asText()
        assertEquals(
            409,
            post("/api/v1/rooms/$roomId/approval-requests/$newRequestId/approve", mapOf("expectedVersion" to 1), bob.accessToken)
                .statusCode.value(),
        )
        // Fresh view of v2 approves fine.
        assertEquals(
            200,
            post("/api/v1/rooms/$roomId/approval-requests/$newRequestId/approve", mapOf("expectedVersion" to 2), bob.accessToken)
                .statusCode.value(),
        )
        // Alice's earlier v1 approval does NOT carry over: the request is still pending.
        val state = json(get("/api/v1/rooms/$roomId/proposals/$proposalId", alice.accessToken))
        assertEquals("PENDING", state["pendingRequest"]["status"].asText())
        assertEquals("OPEN", state["status"].asText())
    }

    // ---- Test 11 + authorization: only required human parties can decide ----
    @Test
    fun `only required parties may decide and rejection reopens the discussion`() {
        val proposalId = createProposal()["id"].asText()
        val requestId = requestApproval(proposalId)["pendingRequest"]["id"].asText()

        // The observer can't decide (no approval is ever created by anything but a required human).
        assertEquals(
            403,
            post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", null, olivia.accessToken).statusCode.value(),
        )
        // An unauthenticated call can't either.
        assertEquals(
            401,
            post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", null).statusCode.value(),
        )
        // An outsider gets 404.
        val mallory = registerUser("Mallory")
        assertEquals(
            404,
            post("/api/v1/rooms/$roomId/approval-requests/$requestId/approve", null, mallory.accessToken).statusCode.value(),
        )

        // Bob requests changes → request rejected, room returns to ACTIVE.
        val changes = post(
            "/api/v1/rooms/$roomId/approval-requests/$requestId/request-changes",
            mapOf("comment" to "צריך סעיף על תאריכים", "expectedVersion" to 1),
            bob.accessToken,
        )
        assertEquals("REJECTED", json(changes)["status"].asText())
        assertEquals("ACTIVE", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())
    }
}

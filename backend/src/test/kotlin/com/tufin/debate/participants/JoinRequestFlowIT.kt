package com.tufin.debate.participants

import com.tufin.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Join-by-code with admin approval, and the grantable admin (OWNER) role. */
class JoinRequestFlowIT : IntegrationTestBase() {

    @Test
    fun `join by code requires admin approval and grants the chosen role`() {
        val alice = registerUser("Alice")
        val carol = registerUser("Carol")

        val room = json(post("/api/v1/rooms", mapOf("title" to "Code room"), alice.accessToken))
        val roomId = room["id"].asText()
        val joinCode = room["joinCode"].asText()
        assertTrue(joinCode.length == 6, "room gets a join code, got '$joinCode'")

        // Wrong code → generic not-found; correct code → pending request, no access yet.
        assertEquals(404, post("/api/v1/join-requests", mapOf("code" to "NOPE99"), carol.accessToken).statusCode.value())
        val request = json(post("/api/v1/join-requests", mapOf("code" to joinCode.lowercase()), carol.accessToken))
        assertEquals("PENDING", request["status"].asText())
        assertEquals("Code room", request["roomTitle"].asText())
        assertEquals(404, get("/api/v1/rooms/$roomId", carol.accessToken).statusCode.value(), "no access before approval")

        // Repeating is idempotent.
        assertEquals(request["id"].asText(), json(post("/api/v1/join-requests", mapOf("code" to joinCode), carol.accessToken))["id"].asText())
        assertEquals(1, json(get("/api/v1/join-requests/mine", carol.accessToken)).size())

        // Only admins see/decide requests.
        assertEquals(404, get("/api/v1/rooms/$roomId/join-requests", carol.accessToken).statusCode.value())
        val pending = json(get("/api/v1/rooms/$roomId/join-requests", alice.accessToken))
        assertEquals(1, pending.size())
        assertEquals("Carol", pending[0]["displayName"].asText())

        // Approve as ADVISOR: participant created with the chosen role, requester gains access.
        val requestId = pending[0]["id"].asText()
        val approved = post("/api/v1/rooms/$roomId/join-requests/$requestId/approve", mapOf("role" to "ADVISOR"), alice.accessToken)
        assertEquals(200, approved.statusCode.value(), approved.body)
        val roles = json(get("/api/v1/rooms/$roomId/participants", carol.accessToken))
            .first { it["displayName"].asText() == "Carol" }["roles"].map { it.asText() }
        assertEquals(listOf("ADVISOR"), roles)
        assertEquals(200, get("/api/v1/rooms/$roomId", carol.accessToken).statusCode.value())

        // A member entering the code again is told so; deciding twice conflicts.
        assertEquals("ALREADY_MEMBER", json(post("/api/v1/join-requests", mapOf("code" to joinCode), carol.accessToken))["code"].asText())
        assertEquals(409, post("/api/v1/rooms/$roomId/join-requests/$requestId/reject", null, alice.accessToken).statusCode.value())
    }

    @Test
    fun `rejection records the decision without granting access`() {
        val alice = registerUser("Alice")
        val mallory = registerUser("Mallory")
        val room = json(post("/api/v1/rooms", mapOf("title" to "Gate room"), alice.accessToken))
        val roomId = room["id"].asText()

        val request = json(post("/api/v1/join-requests", mapOf("code" to room["joinCode"].asText()), mallory.accessToken))
        post("/api/v1/rooms/$roomId/join-requests/${request["id"].asText()}/reject", null, alice.accessToken)

        assertEquals(404, get("/api/v1/rooms/$roomId", mallory.accessToken).statusCode.value())
        assertEquals(0, json(get("/api/v1/join-requests/mine", mallory.accessToken)).size(), "no longer pending")
        // A rejected person may ask again (new request).
        assertEquals("PENDING", json(post("/api/v1/join-requests", mapOf("code" to room["joinCode"].asText()), mallory.accessToken))["status"].asText())
    }

    @Test
    fun `admins can promote and demote other admins but never the creator`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val carol = registerUser("Carol")
        val roomId = json(post("/api/v1/rooms", mapOf("title" to "Admin room"), alice.accessToken))["id"].asText()
        listOf(bob, carol).forEach { user ->
            val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), alice.accessToken))
            post("/api/v1/invitations/${invite["token"].asText()}/accept", null, user.accessToken)
        }
        val participants = json(get("/api/v1/rooms/$roomId/participants", alice.accessToken))
        fun participantId(user: TestUser) = participants.first { it["userId"].asText() == user.userId }["id"].asText()

        // Bob (not admin) cannot promote anyone.
        assertEquals(
            403,
            patch("/api/v1/rooms/$roomId/participants/${participantId(carol)}", mapOf("roles" to listOf("PARTY", "OWNER")), bob.accessToken)
                .statusCode.value(),
        )

        // Alice promotes Bob to admin; Bob can now act as one (e.g. approve join requests / promote).
        assertEquals(
            200,
            patch("/api/v1/rooms/$roomId/participants/${participantId(bob)}", mapOf("roles" to listOf("PARTY", "OWNER")), alice.accessToken)
                .statusCode.value(),
        )
        assertEquals(
            200,
            patch("/api/v1/rooms/$roomId/participants/${participantId(carol)}", mapOf("roles" to listOf("PARTY", "OWNER")), bob.accessToken)
                .statusCode.value(),
        )
        // Bob demotes Carol back.
        assertEquals(
            200,
            patch("/api/v1/rooms/$roomId/participants/${participantId(carol)}", mapOf("roles" to listOf("PARTY")), bob.accessToken)
                .statusCode.value(),
        )
        // Nobody demotes the creator, and nobody edits their own roles.
        assertEquals(
            403,
            patch("/api/v1/rooms/$roomId/participants/${participantId(alice)}", mapOf("roles" to listOf("PARTY")), bob.accessToken)
                .statusCode.value(),
        )
        assertEquals(
            403,
            patch("/api/v1/rooms/$roomId/participants/${participantId(bob)}", mapOf("roles" to listOf("PARTY")), bob.accessToken)
                .statusCode.value(),
        )
    }
}

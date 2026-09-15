package com.bridge.debate.participants

import com.bridge.debate.IntegrationTestBase
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
    fun `one identity appears once no matter how they were invited or joined`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val room = json(post("/api/v1/rooms", mapOf("title" to "Dedup room"), alice.accessToken))
        val roomId = room["id"].asText()

        // Inviting the same email twice keeps only the newest pending invitation.
        post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY", "email" to bob.email), alice.accessToken)
        post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY", "email" to bob.email), alice.accessToken)
        val pendingBefore = json(get("/api/v1/rooms/$roomId/invitations", alice.accessToken))
            .filter { it["status"].asText() == "PENDING" && it["email"].asText() == bob.email }
        assertEquals(1, pendingBefore.size, "re-inviting supersedes the older pending invitation")

        // Bob also has a pending join-by-code request… then joins via the invitation instead.
        post("/api/v1/join-requests", mapOf("code" to room["joinCode"].asText()), bob.accessToken)
        val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY", "email" to bob.email), alice.accessToken))
        post("/api/v1/invitations/${invite["token"].asText()}/accept", null, bob.accessToken)

        // No leftovers: no pending invitation for his email, no pending join request, one participant.
        val pendingAfter = json(get("/api/v1/rooms/$roomId/invitations", alice.accessToken))
            .filter { it["status"].asText() == "PENDING" }
        assertEquals(0, pendingAfter.size, "joining clears every pending entry for that identity")
        assertEquals(0, json(get("/api/v1/rooms/$roomId/join-requests", alice.accessToken)).size())
        assertEquals(0, json(get("/api/v1/join-requests/mine", bob.accessToken)).size())
        val bobs = json(get("/api/v1/rooms/$roomId/participants", alice.accessToken))
            .count { it["userId"].asText() == bob.userId }
        assertEquals(1, bobs)

        // Inviting an email that already belongs to a member is refused outright.
        assertEquals(
            409,
            post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY", "email" to bob.email), alice.accessToken)
                .statusCode.value(),
        )

        // The reverse route: Carol is invited by EMAIL but joins via CODE — approving the join
        // request revokes her pending email invitation, so she never appears twice.
        val carol = registerUser("Carol")
        post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY", "email" to carol.email), alice.accessToken)
        val carolRequest = json(post("/api/v1/join-requests", mapOf("code" to room["joinCode"].asText()), carol.accessToken))
        post("/api/v1/rooms/$roomId/join-requests/${carolRequest["id"].asText()}/approve", mapOf("role" to "PARTY"), alice.accessToken)

        val carolPending = json(get("/api/v1/rooms/$roomId/invitations", alice.accessToken))
            .filter { it["status"].asText() == "PENDING" && it["email"].asText() == carol.email }
        assertEquals(0, carolPending.size, "approval via code revokes the pending email invitation")
        assertEquals(200, get("/api/v1/rooms/$roomId", carol.accessToken).statusCode.value())
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

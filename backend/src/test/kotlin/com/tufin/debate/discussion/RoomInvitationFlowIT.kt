package com.tufin.debate.discussion

import com.tufin.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomInvitationFlowIT : IntegrationTestBase() {

    private fun createRoom(token: String, title: String = "Rent discussion"): String {
        val response = post("/api/v1/rooms", mapOf("title" to title, "objective" to "Agree on rent"), token)
        assertEquals(201, response.statusCode.value(), response.body)
        return json(response)["id"].asText()
    }

    private fun invite(token: String, roomId: String, role: String, idempotencyKey: String? = null): com.fasterxml.jackson.databind.JsonNode {
        val headers = idempotencyKey?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap()
        val response = post("/api/v1/rooms/$roomId/invitations", mapOf("role" to role), token, headers)
        assertEquals(201, response.statusCode.value(), response.body)
        return json(response)
    }

    @Test
    fun `full flow - create, invite, accept, and the room advances to intake`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val roomId = createRoom(alice.accessToken)

        assertEquals("DRAFT", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())

        val invitation = invite(alice.accessToken, roomId, "PARTY")
        val rawToken = invitation["token"].asText()
        assertTrue(invitation["acceptUrl"].asText().contains(rawToken))

        // Creating the first invitation moves the room to INVITING.
        assertEquals("INVITING", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())

        // Bob can see minimal info before accepting, without authentication.
        val publicInfo = json(get("/api/v1/invitations/$rawToken"))
        assertEquals("PARTY", publicInfo["role"].asText())
        assertEquals("Rent discussion", publicInfo["roomTitle"].asText())

        val accept = post("/api/v1/invitations/$rawToken/accept", null, bob.accessToken)
        assertEquals(200, accept.statusCode.value(), accept.body)
        assertEquals(roomId, json(accept)["roomId"].asText())

        // Two parties present: the room advances to INTAKE.
        assertEquals("INTAKE", json(get("/api/v1/rooms/$roomId", bob.accessToken))["status"].asText())

        val participants = json(get("/api/v1/rooms/$roomId/participants", alice.accessToken))
        assertEquals(2, participants.size())

        // A single-use invitation cannot be accepted twice.
        val carol = registerUser("Carol")
        assertEquals(409, post("/api/v1/invitations/$rawToken/accept", null, carol.accessToken).statusCode.value())
    }

    @Test
    fun `invitation creation is idempotent with the same key`() {
        val alice = registerUser("Alice")
        val roomId = createRoom(alice.accessToken)

        val first = invite(alice.accessToken, roomId, "PARTY", idempotencyKey = "key-1")
        val second = invite(alice.accessToken, roomId, "PARTY", idempotencyKey = "key-1")
        assertEquals(first["invitationId"].asText(), second["invitationId"].asText())
        assertEquals(first["token"].asText(), second["token"].asText())

        val distinct = invite(alice.accessToken, roomId, "PARTY", idempotencyKey = "key-2")
        assertTrue(distinct["invitationId"].asText() != first["invitationId"].asText())
    }

    @Test
    fun `non-members cannot see the room and non-owners cannot invite`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val mallory = registerUser("Mallory")
        val roomId = createRoom(alice.accessToken)

        // Outsider: room-scoped resources answer 404, never content.
        assertEquals(404, get("/api/v1/rooms/$roomId", mallory.accessToken).statusCode.value())
        assertEquals(404, get("/api/v1/rooms/$roomId/participants", mallory.accessToken).statusCode.value())
        assertEquals(404, get("/api/v1/rooms/$roomId/audit", mallory.accessToken).statusCode.value())
        assertEquals(
            404,
            post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), mallory.accessToken).statusCode.value(),
        )

        // Member without OWNER cannot invite.
        val invitation = invite(alice.accessToken, roomId, "PARTY")
        post("/api/v1/invitations/${invitation["token"].asText()}/accept", null, bob.accessToken)
        assertEquals(
            403,
            post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), bob.accessToken).statusCode.value(),
        )

        // Unauthenticated requests are rejected outright.
        assertEquals(401, get("/api/v1/rooms/$roomId").statusCode.value())
    }

    @Test
    fun `accepting your own invitation does not consume it`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val roomId = createRoom(alice.accessToken)
        val invitation = invite(alice.accessToken, roomId, "PARTY")
        val token = invitation["token"].asText()

        // Alice (already a participant) tries her own link: clear rejection, nothing consumed.
        val selfAccept = post("/api/v1/invitations/$token/accept", null, alice.accessToken)
        assertEquals(409, selfAccept.statusCode.value())
        assertEquals("ALREADY_MEMBER", json(selfAccept)["code"].asText())

        // The invitation is still PENDING and usable by the intended recipient.
        assertEquals("PENDING", json(get("/api/v1/invitations/$token"))["status"].asText())
        assertEquals(200, post("/api/v1/invitations/$token/accept", null, bob.accessToken).statusCode.value())
        assertEquals("INTAKE", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())
    }

    @Test
    fun `revoked invitations cannot be accepted`() {
        val alice = registerUser("Alice")
        val carol = registerUser("Carol")
        val roomId = createRoom(alice.accessToken)

        val invitation = invite(alice.accessToken, roomId, "PARTY")
        val revoke = post(
            "/api/v1/rooms/$roomId/invitations/${invitation["invitationId"].asText()}/revoke",
            null,
            alice.accessToken,
        )
        assertEquals(200, revoke.statusCode.value(), revoke.body)

        assertEquals(
            409,
            post("/api/v1/invitations/${invitation["token"].asText()}/accept", null, carol.accessToken).statusCode.value(),
        )
    }

    @Test
    fun `observer can see the room but cannot invite, change it, or read the audit trail`() {
        val alice = registerUser("Alice")
        val olivia = registerUser("Olivia")
        val roomId = createRoom(alice.accessToken)

        val invitation = invite(alice.accessToken, roomId, "OBSERVER")
        post("/api/v1/invitations/${invitation["token"].asText()}/accept", null, olivia.accessToken)

        assertEquals(200, get("/api/v1/rooms/$roomId", olivia.accessToken).statusCode.value())
        assertEquals(403, get("/api/v1/rooms/$roomId/audit", olivia.accessToken).statusCode.value())
        assertEquals(
            403,
            post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), olivia.accessToken).statusCode.value(),
        )
        assertEquals(
            403,
            patch("/api/v1/rooms/$roomId", mapOf("title" to "Hijacked"), olivia.accessToken).statusCode.value(),
        )
    }

    @Test
    fun `audit trail is hash-chained and readable by parties`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val roomId = createRoom(alice.accessToken)
        val invitation = invite(alice.accessToken, roomId, "PARTY")
        post("/api/v1/invitations/${invitation["token"].asText()}/accept", null, bob.accessToken)

        val audit = json(get("/api/v1/rooms/$roomId/audit", alice.accessToken))
        assertTrue(audit.size() >= 4, "expected at least room-created + invitation + accept events, got ${audit.size()}")

        var prevHash: String? = null
        var prevSeq = 0L
        audit.forEach { event ->
            assertEquals(prevSeq + 1, event["seq"].asLong(), "sequence must be gapless")
            if (prevHash == null) {
                assertTrue(event["prevHash"].isNull, "first event has no previous hash")
            } else {
                assertEquals(prevHash, event["prevHash"].asText(), "chain must link to the previous event hash")
            }
            prevHash = event["eventHash"].asText()
            prevSeq = event["seq"].asLong()
        }

        // Bob (PARTY) may read the audit trail too.
        assertEquals(200, get("/api/v1/rooms/$roomId/audit", bob.accessToken).statusCode.value())
    }
}

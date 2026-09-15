package com.bridge.debate.discussion

import com.bridge.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod

/** Room admins may permanently delete their discussion — but only after closing it. */
class RoomDeleteIT : IntegrationTestBase() {

    private fun delete(roomId: String, token: String) =
        rest.exchange("/api/v1/rooms/$roomId", HttpMethod.DELETE, HttpEntity<Void>(jsonHeaders(token)), String::class.java)

    @Test
    fun `only the admin can delete, and only a closed discussion`() {
        val alice = registerUser("Alice")
        val bob = registerUser("Bob")
        val roomId = json(post("/api/v1/rooms", mapOf("title" to "Ephemeral"), alice.accessToken))["id"].asText()
        val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), alice.accessToken))
        post("/api/v1/invitations/${invite["token"].asText()}/accept", null, bob.accessToken)

        // Not closed yet → refused; a non-admin member → refused (authorization).
        assertEquals(409, delete(roomId, alice.accessToken).statusCode.value())
        post("/api/v1/rooms/$roomId/close", null, alice.accessToken)
        assertEquals(403, delete(roomId, bob.accessToken).statusCode.value())

        // Closed + admin → gone for everyone.
        assertEquals(200, delete(roomId, alice.accessToken).statusCode.value())
        assertEquals(404, get("/api/v1/rooms/$roomId", alice.accessToken).statusCode.value())
        assertEquals(404, get("/api/v1/rooms/$roomId", bob.accessToken).statusCode.value())
    }
}

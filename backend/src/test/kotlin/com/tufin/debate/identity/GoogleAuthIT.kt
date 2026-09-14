package com.tufin.debate.identity

import com.tufin.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Sign in with Google: first token registers, later tokens sign into the same account. */
class GoogleAuthIT : IntegrationTestBase() {

    @Test
    fun `google token registers on first use and signs into the same account afterwards`() {
        val email = "google-user-${System.nanoTime()}@example.test"

        // First sign-in creates the account with Google's display name.
        val first = post("/api/v1/auth/google", mapOf("idToken" to "GOOGLE:$email:Gal Google"))
        assertEquals(200, first.statusCode.value(), first.body)
        val firstBody = json(first)
        assertEquals("Gal Google", firstBody["user"]["displayName"].asText())
        val userId = firstBody["user"]["id"].asText()
        val accessToken = firstBody["accessToken"].asText()

        // The issued tokens work like any other login.
        assertEquals(200, get("/api/v1/users/me", accessToken).statusCode.value())

        // Second sign-in lands in the SAME account (matched by email), not a duplicate.
        val second = json(post("/api/v1/auth/google", mapOf("idToken" to "GOOGLE:$email:Gal Google")))
        assertEquals(userId, second["user"]["id"].asText())

        // A password account with the same email is also reachable via Google (email ownership proven).
        val alice = registerUser("Alice")
        val linked = json(post("/api/v1/auth/google", mapOf("idToken" to "GOOGLE:${alice.email}:Alice G")))
        assertEquals(alice.userId, linked["user"]["id"].asText())
        assertEquals("Alice", linked["user"]["displayName"].asText(), "existing profile is kept as-is")
    }

    @Test
    fun `invalid and unverified google tokens are rejected`() {
        assertEquals(401, post("/api/v1/auth/google", mapOf("idToken" to "not-a-google-token")).statusCode.value())
        val unverified = post("/api/v1/auth/google", mapOf("idToken" to "GOOGLE:shady@example.test:Shady:unverified"))
        assertEquals(401, unverified.statusCode.value())
        assertTrue(post("/api/v1/auth/google", mapOf("idToken" to "")).statusCode.value() in setOf(400, 401))
    }
}

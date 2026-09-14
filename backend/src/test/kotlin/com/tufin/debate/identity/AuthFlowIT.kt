package com.tufin.debate.identity

import com.tufin.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class AuthFlowIT : IntegrationTestBase() {

    @Test
    fun `register login and fetch me`() {
        val user = registerUser("Alice")

        val me = get("/api/v1/users/me", user.accessToken)
        assertEquals(200, me.statusCode.value())
        assertEquals("Alice", json(me)["displayName"].asText())

        val login = post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        assertEquals(200, login.statusCode.value())
    }

    @Test
    fun `me requires authentication`() {
        assertEquals(401, get("/api/v1/users/me").statusCode.value())
        assertEquals(401, get("/api/v1/users/me", "not-a-real-token").statusCode.value())
    }

    @Test
    fun `duplicate registration is rejected without leaking details`() {
        val user = registerUser("Bob")
        val again = post(
            "/api/v1/auth/register",
            mapOf("email" to user.email, "displayName" to "Bob2", "password" to "another-long-password"),
        )
        assertEquals(409, again.statusCode.value())
    }

    @Test
    fun `wrong password and unknown email get the same generic error`() {
        val user = registerUser("Carol")
        val wrongPassword = post("/api/v1/auth/login", mapOf("email" to user.email, "password" to "wrong-password-123"))
        val unknownEmail = post("/api/v1/auth/login", mapOf("email" to "nobody@example.test", "password" to "wrong-password-123"))
        assertEquals(401, wrongPassword.statusCode.value())
        assertEquals(401, unknownEmail.statusCode.value())
        assertEquals(
            json(wrongPassword)["message"].asText(),
            json(unknownEmail)["message"].asText(),
        )
    }

    @Test
    fun `refresh rotates the token and reuse revokes the whole family`() {
        val user = registerUser("Dave")

        val first = post("/api/v1/auth/refresh", mapOf("refreshToken" to user.refreshToken))
        assertEquals(200, first.statusCode.value())
        val rotated = json(first)["refreshToken"].asText()
        assertNotEquals(user.refreshToken, rotated)

        // Reusing the already-rotated token is treated as theft...
        val reuse = post("/api/v1/auth/refresh", mapOf("refreshToken" to user.refreshToken))
        assertEquals(401, reuse.statusCode.value())

        // ...and revokes the whole family, including the newest token.
        val afterRevoke = post("/api/v1/auth/refresh", mapOf("refreshToken" to rotated))
        assertEquals(401, afterRevoke.statusCode.value())
    }

    @Test
    fun `password change requires the current password and takes effect at once`() {
        val user = registerUser("Chen")

        // Wrong current password → rejected, nothing changes.
        val wrong = post(
            "/api/v1/users/me/password",
            mapOf("currentPassword" to "not-my-password", "newPassword" to "fresh-12345"),
            user.accessToken,
        )
        assertEquals(401, wrong.statusCode.value())

        // Correct current password → old stops working, new works.
        val ok = post(
            "/api/v1/users/me/password",
            mapOf("currentPassword" to user.password, "newPassword" to "fresh-12345"),
            user.accessToken,
        )
        assertEquals(200, ok.statusCode.value(), ok.body)
        assertEquals(401, post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password)).statusCode.value())
        assertEquals(200, post("/api/v1/auth/login", mapOf("email" to user.email, "password" to "fresh-12345")).statusCode.value())

        // Too-short new password → validation error.
        val short = post(
            "/api/v1/users/me/password",
            mapOf("currentPassword" to "fresh-12345", "newPassword" to "tiny"),
            user.accessToken,
        )
        assertEquals(400, short.statusCode.value())
    }

    @Test
    fun `passwords shorter than 5 characters are rejected`() {
        val response = post(
            "/api/v1/auth/register",
            mapOf("email" to "weak@example.test", "displayName" to "Weak", "password" to "tiny"),
        )
        assertEquals(400, response.statusCode.value())

        // Exactly 5 is accepted.
        val minimal = post(
            "/api/v1/auth/register",
            mapOf("email" to "weak-ok@example.test", "displayName" to "Weak", "password" to "ab123"),
        )
        assertEquals(201, minimal.statusCode.value(), minimal.body)
    }
}

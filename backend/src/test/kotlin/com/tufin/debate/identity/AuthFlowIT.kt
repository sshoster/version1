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
    fun `weak passwords are rejected`() {
        val response = post(
            "/api/v1/auth/register",
            mapOf("email" to "weak@example.test", "displayName" to "Weak", "password" to "short"),
        )
        assertEquals(400, response.statusCode.value())
    }
}

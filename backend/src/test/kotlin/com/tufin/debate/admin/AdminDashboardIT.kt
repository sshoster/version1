package com.tufin.debate.admin

import com.tufin.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Super-admin dashboard: env-allowlisted access, user management, room purge. */
class AdminDashboardIT : IntegrationTestBase() {

    /** The allowlisted admin (application-test.yml: root@admin.test); registered once per run. */
    private fun adminToken(): String {
        val login = post("/api/v1/auth/login", mapOf("email" to "root@admin.test", "password" to "root-admin-pass"))
        if (login.statusCode.value() == 200) return json(login)["accessToken"].asText()
        val registered = post(
            "/api/v1/auth/register",
            mapOf("email" to "root@admin.test", "displayName" to "Root", "password" to "root-admin-pass"),
        )
        return json(registered)["accessToken"].asText()
    }

    @Test
    fun `only allowlisted emails can enter and they manage the user lifecycle`() {
        val outsider = registerUser("Regular")
        assertEquals(404, get("/api/v1/admin/users", outsider.accessToken).statusCode.value(), "non-admins see nothing")

        val admin = adminToken()
        assertTrue(json(get("/api/v1/admin/users", admin)).size() > 0)

        // /users/me reports the flag both ways.
        assertTrue(json(get("/api/v1/users/me", admin))["superAdmin"].asBoolean())
        assertEquals(false, json(get("/api/v1/users/me", outsider.accessToken))["superAdmin"].asBoolean())

        // Create → the person can sign in.
        val email = "created-${UUID.randomUUID()}@example.test"
        val created = post(
            "/api/v1/admin/users",
            mapOf("email" to email, "displayName" to "Created", "password" to "start-123"),
            admin,
        )
        assertEquals(201, created.statusCode.value(), created.body)
        val createdId = json(created)["id"].asText()
        assertEquals(200, post("/api/v1/auth/login", mapOf("email" to email, "password" to "start-123")).statusCode.value())

        // Edit: rename + password reset (old password stops working).
        val updated = json(patch("/api/v1/admin/users/$createdId", mapOf("displayName" to "Renamed", "newPassword" to "next-456"), admin))
        assertEquals("Renamed", updated["displayName"].asText())
        assertEquals(401, post("/api/v1/auth/login", mapOf("email" to email, "password" to "start-123")).statusCode.value())

        // Suspend → sign-in blocked with an explicit message; reactivate → works again.
        assertTrue(json(post("/api/v1/admin/users/$createdId/suspend", null, admin))["suspended"].asBoolean())
        val blocked = post("/api/v1/auth/login", mapOf("email" to email, "password" to "next-456"))
        assertEquals(401, blocked.statusCode.value())
        assertTrue(blocked.body!!.contains("suspended"))
        post("/api/v1/admin/users/$createdId/reactivate", null, admin)
        assertEquals(200, post("/api/v1/auth/login", mapOf("email" to email, "password" to "next-456")).statusCode.value())

        // Delete → account gone; generic error (no enumeration).
        val delete = rest.exchange("/api/v1/admin/users/$createdId", org.springframework.http.HttpMethod.DELETE,
            org.springframework.http.HttpEntity<Void>(jsonHeaders(admin)), String::class.java)
        assertEquals(200, delete.statusCode.value(), delete.body)
        assertEquals(401, post("/api/v1/auth/login", mapOf("email" to email, "password" to "next-456")).statusCode.value())

        // Self-protection: an admin cannot suspend or delete themself.
        val adminId = json(get("/api/v1/users/me", admin))["id"].asText()
        assertEquals(409, post("/api/v1/admin/users/$adminId/suspend", null, admin).statusCode.value())
    }

    @Test
    fun `golden rules are super-admin only, default until edited, and versioned`() {
        val outsider = registerUser("Nosy")
        assertEquals(404, get("/api/v1/admin/golden-rules", outsider.accessToken).statusCode.value())

        val admin = adminToken()
        val initial = json(get("/api/v1/admin/golden-rules", admin))
        assertTrue(initial["text"].asText().contains("GOLDEN RULES"), "defaults are seeded")

        val updated = json(
            rest.exchange(
                "/api/v1/admin/golden-rules", org.springframework.http.HttpMethod.PUT,
                org.springframework.http.HttpEntity(
                    objectMapper.writeValueAsString(mapOf("text" to "Rule 1: always be kind.")),
                    jsonHeaders(admin),
                ),
                String::class.java,
            ),
        )
        assertEquals("Rule 1: always be kind.", updated["text"].asText())
        assertEquals(false, updated["isDefault"].asBoolean())
        assertTrue(updated["version"].asInt() >= 1)
    }

    @Test
    fun `a deleted user who registers again gets the same identity and meetings back`() {
        val person = registerUser("Phoenix")
        val roomId = json(post("/api/v1/rooms", mapOf("title" to "Phoenix room"), person.accessToken))["id"].asText()

        val admin = adminToken()
        rest.exchange("/api/v1/admin/users/${person.userId}", org.springframework.http.HttpMethod.DELETE,
            org.springframework.http.HttpEntity<Void>(jsonHeaders(admin)), String::class.java)

        // Deleted: no sign-in with the old password.
        assertEquals(401, post("/api/v1/auth/login", mapOf("email" to person.email, "password" to person.password)).statusCode.value())

        // Re-registering with the same email revives the SAME identity…
        val reborn = post(
            "/api/v1/auth/register",
            mapOf("email" to person.email, "displayName" to "Phoenix Reborn", "password" to "fresh-start-1"),
        )
        assertEquals(201, reborn.statusCode.value(), reborn.body)
        val rebornBody = json(reborn)
        assertEquals(person.userId, rebornBody["user"]["id"].asText(), "same user id — participations stay linked")

        // …and the meetings are right there as they were.
        val token = rebornBody["accessToken"].asText()
        assertEquals(200, get("/api/v1/rooms/$roomId", token).statusCode.value())
        assertTrue(json(get("/api/v1/rooms", token)).any { it["id"].asText() == roomId })
    }

    @Test
    fun `deleting a room purges it for its members`() {
        val alice = registerUser("Alice")
        val roomId = json(post("/api/v1/rooms", mapOf("title" to "Doomed room"), alice.accessToken))["id"].asText()
        post("/api/v1/rooms/$roomId/invitations", mapOf("role" to "PARTY"), alice.accessToken)

        val admin = adminToken()
        assertTrue(json(get("/api/v1/admin/rooms", admin)).any { it["id"].asText() == roomId })

        val delete = rest.exchange("/api/v1/admin/rooms/$roomId", org.springframework.http.HttpMethod.DELETE,
            org.springframework.http.HttpEntity<Void>(jsonHeaders(admin)), String::class.java)
        assertEquals(200, delete.statusCode.value(), delete.body)

        assertEquals(404, get("/api/v1/rooms/$roomId", alice.accessToken).statusCode.value(), "gone for members too")
        assertEquals(404, rest.exchange("/api/v1/admin/rooms/$roomId", org.springframework.http.HttpMethod.DELETE,
            org.springframework.http.HttpEntity<Void>(jsonHeaders(admin)), String::class.java).statusCode.value())
    }
}

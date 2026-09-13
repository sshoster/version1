package com.tufin.debate.security

import com.tufin.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/** docs/security.md T13: auth endpoints are rate-limited per IP; abuse gets a controlled 429. */
@TestPropertySource(properties = ["app.rate-limit.enabled=true", "app.rate-limit.auth-per-minute=5"])
class RateLimitIT : IntegrationTestBase() {

    @Test
    fun `login attempts beyond the budget are rejected with 429`() {
        val statuses = (1..8).map {
            post("/api/v1/auth/login", mapOf("email" to "nobody@example.test", "password" to "wrong-password-1"))
                .statusCode.value()
        }
        assertTrue(statuses.take(5).all { it == 401 }, "within budget: normal auth failures, got $statuses")
        assertEquals(429, statuses.last(), "beyond budget: rate limited, got $statuses")
    }
}

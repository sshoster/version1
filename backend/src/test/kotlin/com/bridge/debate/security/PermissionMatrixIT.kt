package com.bridge.debate.security

import com.bridge.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 6 permission matrix (role × action): every endpoint is checked for every role.
 * "allowed" means the caller gets past authorization (2xx or a domain 400/409) — never 401/403/404.
 * Non-members always get 404 for room-scoped paths (no existence leak); anonymous always 401.
 */
class PermissionMatrixIT : IntegrationTestBase() {

    private enum class Role { OWNER, PARTY, ADVISOR, OBSERVER, OUTSIDER, ANONYMOUS }

    private data class Check(
        val name: String,
        val method: String,
        val path: String,
        val body: Map<String, Any?>?,
        val allowed: Set<Role>,
    )

    @Test
    fun `role x action matrix holds across the API`() {
        val owner = registerUser("Owner")
        val party = registerUser("Party")
        val advisor = registerUser("Advisor")
        val observer = registerUser("Observer")
        val outsider = registerUser("Outsider")

        val roomId = json(post("/api/v1/rooms", mapOf("title" to "Matrix room"), owner.accessToken))["id"].asText()
        listOf("PARTY" to party, "ADVISOR" to advisor, "OBSERVER" to observer).forEach { (role, user) ->
            val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to role), owner.accessToken))
            post("/api/v1/invitations/${invite["token"].asText()}/accept", null, user.accessToken)
        }

        val tokens = mapOf(
            Role.OWNER to owner.accessToken,
            Role.PARTY to party.accessToken,
            Role.ADVISOR to advisor.accessToken,
            Role.OBSERVER to observer.accessToken,
            Role.OUTSIDER to outsider.accessToken,
            Role.ANONYMOUS to null,
        )

        val members = setOf(Role.OWNER, Role.PARTY, Role.ADVISOR, Role.OBSERVER)
        val parties = setOf(Role.OWNER, Role.PARTY)
        val sharers = setOf(Role.OWNER, Role.PARTY, Role.ADVISOR)

        val previewBody = mapOf("text" to "בדיקה", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        val checks = listOf(
            Check("read room", "GET", "/api/v1/rooms/$roomId", null, members),
            Check("update room", "PATCH", "/api/v1/rooms/$roomId", mapOf("title" to "x"), setOf(Role.OWNER)),
            Check("create invitation", "POST", "/api/v1/rooms/$roomId/invitations", mapOf("role" to "OBSERVER"), setOf(Role.OWNER)),
            Check("list participants", "GET", "/api/v1/rooms/$roomId/participants", null, members),
            Check("presence", "GET", "/api/v1/rooms/$roomId/presence", null, members),
            Check("write private message", "POST", "/api/v1/rooms/$roomId/private-messages", mapOf("text" to "פרטי"), parties),
            Check("read private messages", "GET", "/api/v1/rooms/$roomId/private-messages", null, parties),
            Check("share preview", "POST", "/api/v1/rooms/$roomId/share-previews", previewBody, sharers),
            Check("read shared items", "GET", "/api/v1/rooms/$roomId/shared-items", null, members),
            Check("agent profile", "GET", "/api/v1/rooms/$roomId/agent-profile", null, parties),
            Check("start negotiation", "POST", "/api/v1/rooms/$roomId/negotiation-runs", emptyMap(), parties),
            Check("list negotiation runs", "GET", "/api/v1/rooms/$roomId/negotiation-runs", null, parties),
            Check("create proposal", "POST", "/api/v1/rooms/$roomId/proposals", mapOf("title" to "t", "terms" to listOf("a")), parties),
            Check("list proposals", "GET", "/api/v1/rooms/$roomId/proposals", null, members),
            Check("list outcomes", "GET", "/api/v1/rooms/$roomId/outcomes", null, sharers),
            Check("generate summary", "POST", "/api/v1/rooms/$roomId/outcomes/summary", null, parties),
            Check("timeline", "GET", "/api/v1/rooms/$roomId/timeline", null, members),
            Check("audit", "GET", "/api/v1/rooms/$roomId/audit", null, parties),
            Check("list files", "GET", "/api/v1/rooms/$roomId/files", null, members),
            Check("pause room", "POST", "/api/v1/rooms/$roomId/pause", null, parties),
            Check("resume room", "POST", "/api/v1/rooms/$roomId/resume", null, parties),
            Check("notifications (global)", "GET", "/api/v1/notifications", null, members + Role.OUTSIDER),
        )

        val failures = mutableListOf<String>()
        for (check in checks) {
            for ((role, token) in tokens) {
                val response = when (check.method) {
                    "GET" -> get(check.path, token)
                    "PATCH" -> patch(check.path, check.body, token)
                    else -> post(check.path, check.body, token)
                }
                val status = response.statusCode.value()
                val expectation = when {
                    role == Role.ANONYMOUS -> status == 401
                    role in check.allowed -> status !in setOf(401, 403, 404)
                    role == Role.OUTSIDER && check.path.contains("/rooms/") -> status == 404
                    else -> status in setOf(403, 404)
                }
                if (!expectation) {
                    failures += "${check.name}: role=$role got $status (allowed=${role in check.allowed})"
                }
            }
        }
        assertTrue(failures.isEmpty(), "Permission matrix violations:\n" + failures.joinToString("\n"))
    }
}

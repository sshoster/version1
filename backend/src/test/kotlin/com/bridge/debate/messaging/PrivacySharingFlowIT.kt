package com.bridge.debate.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.bridge.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Acceptance tests 1–7 and 16 from the design doc (§14) for the privacy & sharing flow.
 */
class PrivacySharingFlowIT : IntegrationTestBase() {

    private lateinit var alice: TestUser // OWNER + PARTY
    private lateinit var bob: TestUser // PARTY
    private lateinit var olivia: TestUser // OBSERVER
    private lateinit var adam: TestUser // ADVISOR
    private lateinit var roomId: String

    @BeforeEach
    fun setUpRoom() {
        alice = registerUser("Alice")
        bob = registerUser("Bob")
        olivia = registerUser("Olivia")
        adam = registerUser("Adam")

        val room = post("/api/v1/rooms", mapOf("title" to "Rent split", "objective" to "Agree on rent"), alice.accessToken)
        roomId = json(room)["id"].asText()

        acceptInvite(invite("PARTY"), bob)
        acceptInvite(invite("OBSERVER"), olivia)
        acceptInvite(invite("ADVISOR"), adam)
    }

    private fun invite(role: String): String {
        val response = post("/api/v1/rooms/$roomId/invitations", mapOf("role" to role), alice.accessToken)
        assertEquals(201, response.statusCode.value(), response.body)
        return json(response)["token"].asText()
    }

    private fun acceptInvite(token: String, user: TestUser) {
        assertEquals(200, post("/api/v1/invitations/$token/accept", null, user.accessToken).statusCode.value())
    }

    private fun writePrivate(user: TestUser, text: String): JsonNode {
        val response = post("/api/v1/rooms/$roomId/private-messages", mapOf("text" to text), user.accessToken)
        assertEquals(201, response.statusCode.value(), response.body)
        return json(response)
    }

    private fun preview(user: TestUser, body: Map<String, Any?>): JsonNode {
        val response = post("/api/v1/rooms/$roomId/share-previews", body, user.accessToken)
        assertEquals(201, response.statusCode.value(), response.body)
        return json(response)
    }

    private fun publish(user: TestUser, body: Map<String, Any?>, expected: Int = 201): JsonNode {
        val response = post("/api/v1/rooms/$roomId/shared-items", body, user.accessToken)
        assertEquals(expected, response.statusCode.value(), response.body)
        return json(response)
    }

    // ---- Test 1: private messages are inaccessible to everyone else ----
    @Test
    fun `private messages are visible only to their author`() {
        writePrivate(alice, "My real limit is 5000, don't tell Bob")

        val aliceList = json(get("/api/v1/rooms/$roomId/private-messages", alice.accessToken))
        assertEquals(1, aliceList.size())

        // Bob's private space is separate and empty.
        val bobList = json(get("/api/v1/rooms/$roomId/private-messages", bob.accessToken))
        assertEquals(0, bobList.size())

        // Observers and advisors have no private-assistant space at all.
        assertEquals(403, get("/api/v1/rooms/$roomId/private-messages", olivia.accessToken).statusCode.value())
        assertEquals(403, get("/api/v1/rooms/$roomId/private-messages", adam.accessToken).statusCode.value())

        // Outsiders get 404 on the room itself.
        val mallory = registerUser("Mallory")
        assertEquals(404, get("/api/v1/rooms/$roomId/private-messages", mallory.accessToken).statusCode.value())
    }

    // ---- Tests 2 + 3: AI draft -> edit -> preview -> publish; recipients see label, not the draft ----
    @Test
    fun `edited ai draft publishes with exact preview and provenance label`() {
        val message = writePrivate(alice, "You always pay late!! I want the rent money now!!")

        val draft = post(
            "/api/v1/rooms/$roomId/private-messages/${message["id"].asText()}/ai-draft", null, alice.accessToken,
        )
        assertEquals(201, draft.statusCode.value(), draft.body)
        val draftBody = json(draft)
        assertEquals("ASSISTANT", draftBody["sender"].asText())
        val draftText = draftBody["text"].asText()
        assertTrue(draftText.startsWith("נוסח מוצע:"))

        val editedText = draftText + "\nתודה, אליס"
        val previewBody = mapOf(
            "text" to editedText,
            "scope" to "ALL_PARTIES",
            "origin" to "AI_DRAFT_USER_EDITED",
            "sourceDraftId" to draftBody["id"].asText(),
        )
        val previewResult = preview(alice, previewBody)
        assertEquals(editedText, previewResult["text"].asText(), "preview shows the exact final text")
        val recipientNames = previewResult["recipients"].map { it["displayName"].asText() }.toSet()
        assertEquals(setOf("Alice", "Bob"), recipientNames, "ALL_PARTIES = the two parties, not observer/advisor")

        val published = publish(alice, previewBody + mapOf("previewId" to previewResult["previewId"].asText()))
        assertEquals("AI_DRAFT_USER_EDITED", published["origin"].asText())

        // Bob sees the final text and the origin label data — never Alice's original private draft.
        val bobItems = json(get("/api/v1/rooms/$roomId/shared-items", bob.accessToken))
        assertEquals(1, bobItems.size())
        val item = bobItems[0]
        assertEquals(editedText, item["text"].asText())
        assertEquals("AI_DRAFT_USER_EDITED", item["origin"].asText())
        assertEquals("Alice", item["authorDisplayName"].asText())

        // Bob cannot read Alice's private messages or drafts anywhere.
        assertEquals(0, json(get("/api/v1/rooms/$roomId/private-messages", bob.accessToken)).size())
    }

    // ---- Test 4: any drift after preview forces a new preview ----
    @Test
    fun `changing text scope or recipients after preview is rejected`() {
        val previewBody = mapOf("text" to "הצעה: 4000 לחודש", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        val previewResult = preview(alice, previewBody)
        val previewId = previewResult["previewId"].asText()

        // Text drift.
        publish(alice, previewBody + mapOf("previewId" to previewId, "text" to "הצעה: 3000 לחודש"), expected = 409)

        // The preview is single-use even after a failed attempt — a fresh one is required.
        publish(alice, previewBody + mapOf("previewId" to previewId), expected = 409)

        // Scope drift on a fresh preview.
        val second = preview(alice, previewBody)
        publish(
            alice,
            previewBody + mapOf("previewId" to second["previewId"].asText(), "scope" to "ALL_ROOM_PARTICIPANTS"),
            expected = 409,
        )

        // Clean preview + publish succeeds.
        val third = preview(alice, previewBody)
        publish(alice, previewBody + mapOf("previewId" to third["previewId"].asText()))
    }

    // ---- Test 5: no hard delete; withdrawal is an event, history stays ----
    @Test
    fun `withdrawal marks the item and keeps history`() {
        val body = mapOf("text" to "אני מוכנה ל-4500", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        val p = preview(alice, body)
        val item = publish(alice, body + mapOf("previewId" to p["previewId"].asText()))
        val itemId = item["id"].asText()

        // Bob cannot withdraw Alice's item.
        assertEquals(
            403,
            post("/api/v1/rooms/$roomId/shared-items/$itemId/withdraw", null, bob.accessToken).statusCode.value(),
        )

        assertEquals(
            200,
            post("/api/v1/rooms/$roomId/shared-items/$itemId/withdraw", null, alice.accessToken).statusCode.value(),
        )

        // The item still appears, marked WITHDRAWN, with its live text hidden.
        val bobItems = json(get("/api/v1/rooms/$roomId/shared-items", bob.accessToken))
        assertEquals(1, bobItems.size())
        assertEquals("WITHDRAWN", bobItems[0]["status"].asText())
        assertTrue(bobItems[0]["text"].isNull)

        // History (versions) remains available to the authorized audience.
        val versions = json(get("/api/v1/rooms/$roomId/shared-items/$itemId/versions", bob.accessToken))
        assertEquals(1, versions.size())
        assertEquals("אני מוכנה ל-4500", versions[0]["text"].asText())

        // The audit trail records the withdrawal as a new event.
        val audit = json(get("/api/v1/rooms/$roomId/audit", alice.accessToken))
        assertTrue(audit.any { it["action"].asText() == "SHARED_ITEM_WITHDRAWN" })
        assertTrue(audit.any { it["action"].asText() == "SHARED_ITEM_PUBLISHED" })
    }

    // ---- Test 6: observers are read-only ----
    @Test
    fun `observer cannot write share or withdraw`() {
        assertEquals(
            403,
            post("/api/v1/rooms/$roomId/private-messages", mapOf("text" to "x"), olivia.accessToken).statusCode.value(),
        )
        assertEquals(
            403,
            post(
                "/api/v1/rooms/$roomId/share-previews",
                mapOf("text" to "x", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED"),
                olivia.accessToken,
            ).statusCode.value(),
        )

        val body = mapOf("text" to "משהו לכולם", "scope" to "ALL_ROOM_PARTICIPANTS", "origin" to "USER_AUTHORED")
        val p = preview(alice, body)
        val item = publish(alice, body + mapOf("previewId" to p["previewId"].asText()))

        // The observer can read content shared with everyone...
        val observerItems = json(get("/api/v1/rooms/$roomId/shared-items", olivia.accessToken))
        assertEquals(1, observerItems.size())
        // ...but cannot withdraw it.
        assertEquals(
            403,
            post(
                "/api/v1/rooms/$roomId/shared-items/${item["id"].asText()}/withdraw", null, olivia.accessToken,
            ).statusCode.value(),
        )
    }

    // ---- Test 7: advisors see only scopes that include them ----
    @Test
    fun `advisor sees only content shared with advisors`() {
        val partiesOnly = mapOf("text" to "בין הצדדים בלבד", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        publish(alice, partiesOnly + mapOf("previewId" to preview(alice, partiesOnly)["previewId"].asText()))

        assertEquals(0, json(get("/api/v1/rooms/$roomId/shared-items", adam.accessToken)).size())

        val forAdvisors = mapOf("text" to "שאלה ליועץ שלי", "scope" to "MY_ADVISORS", "origin" to "USER_AUTHORED")
        val advisorPreview = preview(alice, forAdvisors)
        val advisorRecipients = advisorPreview["recipients"].map { it["displayName"].asText() }.toSet()
        assertEquals(setOf("Alice", "Adam"), advisorRecipients)
        publish(alice, forAdvisors + mapOf("previewId" to advisorPreview["previewId"].asText()))

        val adamItems = json(get("/api/v1/rooms/$roomId/shared-items", adam.accessToken))
        assertEquals(1, adamItems.size())
        assertEquals("שאלה ליועץ שלי", adamItems[0]["text"].asText())

        // Bob (the other party) does NOT see Alice's advisor-scoped content.
        val bobItems = json(get("/api/v1/rooms/$roomId/shared-items", bob.accessToken))
        assertEquals(1, bobItems.size())
        assertEquals("בין הצדדים בלבד", bobItems[0]["text"].asText())
    }

    // ---- Test 16 + origin integrity ----
    @Test
    fun `cross room ids fail and origin claims are validated`() {
        // A second room that Bob is not part of.
        val other = post("/api/v1/rooms", mapOf("title" to "Other"), alice.accessToken)
        val otherRoomId = json(other)["id"].asText()

        val body = mapOf("text" to "היי", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        val p = preview(alice, body)
        val item = publish(alice, body + mapOf("previewId" to p["previewId"].asText()))

        // Valid item id through the wrong room path → 404 (Bob is not a member of otherRoom).
        assertEquals(
            404,
            get("/api/v1/rooms/$otherRoomId/shared-items/${item["id"].asText()}/versions", bob.accessToken)
                .statusCode.value(),
        )
        // Even for Alice (member of both) the item does not exist under the other room.
        assertEquals(
            404,
            get("/api/v1/rooms/$otherRoomId/shared-items/${item["id"].asText()}/versions", alice.accessToken)
                .statusCode.value(),
        )

        // A fabricated AI-origin claim is rejected: the source draft must be a real assistant draft.
        val fake = mapOf(
            "text" to "נוסח כביכול של AI",
            "scope" to "ALL_PARTIES",
            "origin" to "AI_DRAFT_ACCEPTED",
            "sourceDraftId" to item["id"].asText(), // not a private draft at all
        )
        assertEquals(
            400,
            post("/api/v1/rooms/$roomId/share-previews", fake, alice.accessToken).statusCode.value(),
        )

        // ACCEPTED with modified text is rejected — it must be marked as edited.
        val message = writePrivate(alice, "טקסט מקורי")
        val draft = json(
            post("/api/v1/rooms/$roomId/private-messages/${message["id"].asText()}/ai-draft", null, alice.accessToken),
        )
        val tampered = mapOf(
            "text" to draft["text"].asText() + " בתוספת שלי",
            "scope" to "ALL_PARTIES",
            "origin" to "AI_DRAFT_ACCEPTED",
            "sourceDraftId" to draft["id"].asText(),
        )
        assertEquals(
            400,
            post("/api/v1/rooms/$roomId/share-previews", tampered, alice.accessToken).statusCode.value(),
        )
    }

    // ---- New versions supersede prior ones; room advances INTAKE -> ACTIVE on first share ----
    @Test
    fun `versioning and room activation work`() {
        assertEquals("INTAKE", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())

        val v1 = mapOf("text" to "הצעה ראשונה", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED")
        val item = publish(alice, v1 + mapOf("previewId" to preview(alice, v1)["previewId"].asText()))
        assertEquals("ACTIVE", json(get("/api/v1/rooms/$roomId", alice.accessToken))["status"].asText())

        val v2 = mapOf(
            "text" to "הצעה מעודכנת", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED",
            "itemId" to item["id"].asText(),
        )
        val updated = publish(alice, v2 + mapOf("previewId" to preview(alice, v2)["previewId"].asText()))
        assertEquals(2, updated["version"].asInt())
        assertNotEquals(item["version"].asInt(), updated["version"].asInt())

        val versions = json(
            get("/api/v1/rooms/$roomId/shared-items/${item["id"].asText()}/versions", bob.accessToken),
        )
        assertEquals(2, versions.size())
        assertTrue(versions[0]["superseded"].asBoolean())
        assertFalse(versions[1]["superseded"].asBoolean())

        // Only the author can publish a new version of an item.
        val hijack = mapOf(
            "text" to "גרסה זדונית", "scope" to "ALL_PARTIES", "origin" to "USER_AUTHORED",
            "itemId" to item["id"].asText(),
        )
        val hijackPreview = preview(bob, hijack)
        publish(bob, hijack + mapOf("previewId" to hijackPreview["previewId"].asText()), expected = 403)
    }
}

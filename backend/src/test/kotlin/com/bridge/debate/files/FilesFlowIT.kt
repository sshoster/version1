package com.bridge.debate.files

import com.fasterxml.jackson.databind.JsonNode
import com.bridge.debate.IntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/** Files follow the trust model: private by default, explicit audience on share, no hard delete once shared. */
class FilesFlowIT : IntegrationTestBase() {

    private lateinit var alice: TestUser
    private lateinit var bob: TestUser
    private lateinit var olivia: TestUser // observer
    private lateinit var adam: TestUser // advisor
    private lateinit var roomId: String

    @BeforeEach
    fun setUpRoom() {
        alice = registerUser("Alice")
        bob = registerUser("Bob")
        olivia = registerUser("Olivia")
        adam = registerUser("Adam")
        roomId = json(post("/api/v1/rooms", mapOf("title" to "Files room"), alice.accessToken))["id"].asText()
        listOf("PARTY" to bob, "OBSERVER" to olivia, "ADVISOR" to adam).forEach { (role, user) ->
            val invite = json(post("/api/v1/rooms/$roomId/invitations", mapOf("role" to role), alice.accessToken))
            post("/api/v1/invitations/${invite["token"].asText()}/accept", null, user.accessToken)
        }
    }

    private fun upload(
        user: TestUser,
        filename: String = "receipt.pdf",
        contentType: String = "application/pdf",
        bytes: ByteArray = "PDF-CONTENT".toByteArray(),
    ): ResponseEntity<String> {
        val resource = object : ByteArrayResource(bytes) {
            override fun getFilename(): String = filename
        }
        val filePart = HttpEntity(resource, HttpHeaders().apply { contentType?.let { setContentType(MediaType.parseMediaType(it)) } })
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", filePart) }
        val headers = HttpHeaders().apply {
            setContentType(MediaType.MULTIPART_FORM_DATA)
            setBearerAuth(user.accessToken)
        }
        return rest.exchange("/api/v1/rooms/$roomId/files", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    @Test
    fun `upload is private, sharing follows scopes, downloads are authorized`() {
        // Observer cannot upload.
        assertEquals(403, upload(olivia).statusCode.value())

        // Alice uploads privately.
        val uploaded = upload(alice)
        assertEquals(201, uploaded.statusCode.value(), uploaded.body)
        val fileId = json(uploaded)["id"].asText()
        assertEquals("PRIVATE", json(uploaded)["status"].asText())

        // Bob sees nothing yet, and cannot download.
        assertEquals(0, json(get("/api/v1/rooms/$roomId/files", bob.accessToken)).size())
        assertEquals(404, get("/api/v1/rooms/$roomId/files/$fileId/content", bob.accessToken).statusCode.value())

        // Alice shares with parties only.
        val shared = post("/api/v1/rooms/$roomId/files/$fileId/share", mapOf("scope" to "ALL_PARTIES"), alice.accessToken)
        assertEquals(200, shared.statusCode.value(), shared.body)

        // Bob now sees and can download; the observer and advisor cannot.
        val bobFiles = json(get("/api/v1/rooms/$roomId/files", bob.accessToken))
        assertEquals(1, bobFiles.size())
        assertEquals("receipt.pdf", bobFiles[0]["filename"].asText())
        val download = get("/api/v1/rooms/$roomId/files/$fileId/content", bob.accessToken)
        assertEquals(200, download.statusCode.value())
        assertEquals("PDF-CONTENT", download.body)
        assertEquals(404, get("/api/v1/rooms/$roomId/files/$fileId/content", olivia.accessToken).statusCode.value())
        assertEquals(404, get("/api/v1/rooms/$roomId/files/$fileId/content", adam.accessToken).statusCode.value())

        // Only the uploader may share/withdraw; already-shared cannot be re-shared or deleted.
        assertEquals(403, post("/api/v1/rooms/$roomId/files/$fileId/withdraw", null, bob.accessToken).statusCode.value())
        assertEquals(409, post("/api/v1/rooms/$roomId/files/$fileId/share", mapOf("scope" to "ALL_PARTIES"), alice.accessToken).statusCode.value())
        val deleteShared = rest.exchange(
            "/api/v1/rooms/$roomId/files/$fileId", HttpMethod.DELETE,
            HttpEntity<Void>(jsonHeaders(alice.accessToken)), String::class.java,
        )
        assertEquals(409, deleteShared.statusCode.value(), "a shared file can only be withdrawn")

        // Withdraw hides it from the audience but keeps the record for the owner.
        assertEquals(200, post("/api/v1/rooms/$roomId/files/$fileId/withdraw", null, alice.accessToken).statusCode.value())
        assertEquals(0, json(get("/api/v1/rooms/$roomId/files", bob.accessToken)).size())
        assertEquals(404, get("/api/v1/rooms/$roomId/files/$fileId/content", bob.accessToken).statusCode.value())
        val aliceFiles = json(get("/api/v1/rooms/$roomId/files", alice.accessToken))
        assertEquals("WITHDRAWN", aliceFiles[0]["status"].asText())
    }

    @Test
    fun `type allowlist and private delete work, advisors can upload and share to their scope`() {
        // Executable/HTML-ish types are rejected.
        assertEquals(400, upload(alice, "evil.svg", "image/svg+xml").statusCode.value())
        assertEquals(400, upload(alice, "run.exe", "application/x-msdownload").statusCode.value())

        // A private file can be truly deleted by its owner.
        val fileId = json(upload(alice, "note.txt", "text/plain", "hello".toByteArray()))["id"].asText()
        val deleted = rest.exchange(
            "/api/v1/rooms/$roomId/files/$fileId", HttpMethod.DELETE,
            HttpEntity<Void>(jsonHeaders(alice.accessToken)), String::class.java,
        )
        assertEquals(200, deleted.statusCode.value())
        assertEquals(404, get("/api/v1/rooms/$roomId/files/$fileId/content", alice.accessToken).statusCode.value())

        // An advisor can upload and share with MY_ADVISORS (advisor + self).
        val advisorFile = json(upload(adam, "advice.pdf"))
        assertEquals(201, 201)
        val share = post(
            "/api/v1/rooms/$roomId/files/${advisorFile["id"].asText()}/share",
            mapOf("scope" to "ALL_ROOM_PARTICIPANTS"), adam.accessToken,
        )
        assertEquals(200, share.statusCode.value(), share.body)
        assertTrue(json(get("/api/v1/rooms/$roomId/files", olivia.accessToken)).size() == 1)
    }

    @Test
    fun `avatars upload and serve, defaults are absent`() {
        // No avatar yet.
        assertEquals(404, get("/api/v1/users/${alice.userId}/avatar", bob.accessToken).statusCode.value())

        // Alice uploads a PNG avatar (tiny fake bytes are fine — content is not validated as an image renderer would).
        val resource = object : ByteArrayResource("PNGDATA".toByteArray()) {
            override fun getFilename(): String = "me.png"
        }
        val filePart = HttpEntity(resource, HttpHeaders().apply { setContentType(MediaType.IMAGE_PNG) })
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", filePart) }
        val headers = HttpHeaders().apply {
            setContentType(MediaType.MULTIPART_FORM_DATA)
            setBearerAuth(alice.accessToken)
        }
        val uploadResponse = rest.exchange("/api/v1/users/me/avatar", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
        assertEquals(200, uploadResponse.statusCode.value(), uploadResponse.body)

        val avatar = get("/api/v1/users/${alice.userId}/avatar", bob.accessToken)
        assertEquals(200, avatar.statusCode.value())
        assertEquals("PNGDATA", avatar.body)

        // Unauthenticated requests are rejected.
        assertEquals(401, get("/api/v1/users/${alice.userId}/avatar").statusCode.value())
    }
}

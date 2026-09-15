package com.bridge.debate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * Integration-test base: one shared MongoDB replica-set container (Testcontainers) for the whole
 * test JVM; Mongock migrations run at context startup, so index changelogs are exercised too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    companion object {
        private val mongo: MongoDBContainer =
            MongoDBContainer(DockerImageName.parse("mongo:7.0")).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { mongo.replicaSetUrl }
        }
    }

    protected fun jsonHeaders(accessToken: String? = null): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accessToken?.let { setBearerAuth(it) }
        }

    protected fun post(path: String, body: Any?, accessToken: String? = null, extraHeaders: Map<String, String> = emptyMap()): ResponseEntity<String> {
        val headers = jsonHeaders(accessToken)
        extraHeaders.forEach { (k, v) -> headers.set(k, v) }
        return rest.exchange(path, HttpMethod.POST, HttpEntity(body?.let { objectMapper.writeValueAsString(it) }, headers), String::class.java)
    }

    protected fun patch(path: String, body: Any?, accessToken: String? = null): ResponseEntity<String> =
        rest.exchange(path, HttpMethod.PATCH, HttpEntity(body?.let { objectMapper.writeValueAsString(it) }, jsonHeaders(accessToken)), String::class.java)

    protected fun get(path: String, accessToken: String? = null): ResponseEntity<String> =
        rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(jsonHeaders(accessToken)), String::class.java)

    protected fun json(response: ResponseEntity<String>): JsonNode =
        objectMapper.readTree(response.body ?: "{}")

    data class TestUser(val email: String, val displayName: String, val password: String, val accessToken: String, val refreshToken: String, val userId: String)

    protected fun registerUser(displayName: String): TestUser {
        val email = "user-${UUID.randomUUID()}@example.test"
        val password = "correct-horse-battery"
        val response = post(
            "/api/v1/auth/register",
            mapOf("email" to email, "displayName" to displayName, "password" to password),
        )
        check(response.statusCode.value() == 201) { "register failed: ${response.statusCode} ${response.body}" }
        val body = json(response)
        return TestUser(
            email = email,
            displayName = displayName,
            password = password,
            accessToken = body["accessToken"].asText(),
            refreshToken = body["refreshToken"].asText(),
            userId = body["user"]["id"].asText(),
        )
    }
}

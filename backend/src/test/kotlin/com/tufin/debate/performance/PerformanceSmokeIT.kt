package com.tufin.debate.performance

import com.tufin.debate.IntegrationTestBase
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.audit.domain.AuditEvent
import com.tufin.debate.messaging.domain.ContentOrigin
import com.tufin.debate.messaging.domain.ContentType
import com.tufin.debate.messaging.domain.SharedItem
import com.tufin.debate.messaging.domain.SharedItemVersion
import com.tufin.debate.messaging.domain.VisibilityScope
import com.tufin.debate.shared.Ids
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Instant

/**
 * Phase 6 basic performance validation: timeline and shared-message reads stay fast and bounded
 * (limit enforcement) with hundreds of documents in the room.
 */
class PerformanceSmokeIT : IntegrationTestBase() {

    @Autowired
    lateinit var mongoTemplate: MongoTemplate

    @Test
    fun `timeline and shared items stay fast and bounded at volume`() {
        val alice = registerUser("Alice")
        val roomId = json(post("/api/v1/rooms", mapOf("title" to "Perf room"), alice.accessToken))["id"].asText()

        // Bulk-seed 400 audit events and 300 shared item versions directly (read-path focus).
        val now = Instant.now()
        val auditEvents = (1..400).map { index ->
            AuditEvent(
                id = Ids.newId(), roomId = roomId, seq = 100L + index, actorType = ActorType.USER,
                actorId = alice.userId, action = "ROOM_UPDATED", targetType = "DiscussionRoom", targetId = roomId,
                occurredAt = now.plusMillis(index.toLong()), correlationId = null,
                metadata = emptyMap(), prevHash = "seed", eventHash = "seed-$index",
            )
        }
        mongoTemplate.insertAll(auditEvents)

        val items = mutableListOf<SharedItem>()
        val versions = mutableListOf<SharedItemVersion>()
        repeat(300) { index ->
            val itemId = Ids.newId()
            items += SharedItem(
                id = itemId, roomId = roomId, authorUserId = alice.userId, contentType = ContentType.TEXT,
                currentVersion = 1, createdAt = now.plusMillis(index.toLong()), updatedAt = now,
            )
            versions += SharedItemVersion(
                id = Ids.newId(), sharedItemId = itemId, roomId = roomId, version = 1,
                text = "הודעה מספר $index", origin = ContentOrigin.USER_AUTHORED,
                authorUserId = alice.userId, authorDisplayName = "Alice",
                audienceSnapshotId = "snap", audienceUserIds = listOf(alice.userId),
                scope = VisibilityScope.ALL_PARTIES, createdAt = now.plusMillis(index.toLong()),
            )
        }
        mongoTemplate.insertAll(items)
        mongoTemplate.insertAll(versions)

        // Timeline: bounded by limit and fast.
        val timelineStart = System.nanoTime()
        val timeline = json(get("/api/v1/rooms/$roomId/timeline?limit=200", alice.accessToken))
        val timelineMs = (System.nanoTime() - timelineStart) / 1_000_000
        assertEquals(200, timeline.size(), "timeline respects its limit")
        assertTrue(timelineMs < 3000, "timeline took ${timelineMs}ms")

        // Shared items: bounded by limit and fast.
        val itemsStart = System.nanoTime()
        val sharedItems = json(get("/api/v1/rooms/$roomId/shared-items?limit=100", alice.accessToken))
        val itemsMs = (System.nanoTime() - itemsStart) / 1_000_000
        assertEquals(100, sharedItems.size(), "shared items respect their limit")
        assertTrue(itemsMs < 3000, "shared items took ${itemsMs}ms")
    }
}

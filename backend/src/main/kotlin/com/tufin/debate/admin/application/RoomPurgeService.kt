package com.tufin.debate.admin.application

import com.tufin.debate.files.application.FileStorage
import org.slf4j.LoggerFactory
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import java.time.Instant

/** Minimal local projections — the admin module never touches other modules' domain classes. */
class AdminRoomDoc(
    @Id val id: String = "",
    val title: String = "",
    val status: String = "",
    val joinCode: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)

private class StoredKeyDoc(val storedKey: String = "")

/**
 * Super-admin room removal: erases a discussion and EVERYTHING room-scoped — messages,
 * versions, proposals, outcomes, files (storage objects included), audit chain, notifications,
 * outbox events. Works at the collection level (by roomId) so no module boundary is crossed.
 */
@Service
class RoomPurgeService(
    private val mongoTemplate: MongoTemplate,
    private val fileStorage: FileStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Every collection whose documents carry a roomId field. */
        private val ROOM_SCOPED_COLLECTIONS = listOf(
            "participants", "invitations", "join_requests",
            "private_messages", "share_previews", "shared_items", "shared_item_versions",
            "audience_snapshots", "ai_agent_profiles",
            "negotiation_runs", "negotiation_turns", "questions",
            "proposals", "proposal_versions", "approval_requests", "approvals",
            "outcome_artifacts", "attachments",
            "audit_events", "notifications", "outbox_events", "idempotency_records",
        )
    }

    fun listRooms(): List<AdminRoomDoc> =
        mongoTemplate.find(
            Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(300),
            AdminRoomDoc::class.java,
            "discussion_rooms",
        )

    /** Returns false when no such room exists. */
    fun purgeRoom(roomId: String): Boolean {
        val byId = Query(where("_id").`is`(roomId))
        val room = mongoTemplate.findOne(byId, AdminRoomDoc::class.java, "discussion_rooms") ?: return false

        // Storage objects first — once the metadata is gone the keys are unrecoverable.
        mongoTemplate.find(Query(where("roomId").`is`(roomId)), StoredKeyDoc::class.java, "attachments")
            .forEach { file ->
                try {
                    fileStorage.delete(file.storedKey)
                } catch (e: Exception) {
                    log.warn("Could not delete storage object {} of room {}: {}", file.storedKey, roomId, e.message)
                }
            }

        var removed = 0L
        ROOM_SCOPED_COLLECTIONS.forEach { collection ->
            removed += mongoTemplate.remove(Query(where("roomId").`is`(roomId)), collection).deletedCount
        }
        mongoTemplate.remove(byId, "audit_chain_heads") // keyed by roomId as _id
        mongoTemplate.remove(byId, "discussion_rooms")
        log.info("Room {} ({}) purged: {} room-scoped documents removed", roomId, room.title, removed)
        return true
    }
}

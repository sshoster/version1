package com.tufin.debate.messaging.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

enum class VisibilityScope {
    PRIVATE_TO_AUTHOR_AND_AI,
    MY_ADVISORS,
    SELECTED_PARTICIPANTS,
    ALL_PARTIES,
    ALL_ROOM_PARTICIPANTS,
}

enum class ContentOrigin {
    USER_AUTHORED,
    AI_DRAFT_ACCEPTED,
    AI_DRAFT_USER_EDITED,
    SYSTEM_GENERATED,
    ADVISOR_AUTHORED,
}

enum class ContentType { TEXT }

enum class SharedItemStatus { ACTIVE, WITHDRAWN }

/**
 * Immutable record of exactly who could see an item at publication time (trust-model invariant 1/9):
 * later role changes never rewrite history.
 */
@Document("audience_snapshots")
class AudienceSnapshot(
    @Id val id: String,
    val roomId: String,
    val scope: VisibilityScope,
    val participantIds: List<String>,
    val userIds: List<String>,
    val createdAt: Instant,
)

@Document("shared_items")
class SharedItem(
    @Id val id: String,
    val roomId: String,
    val authorUserId: String,
    val contentType: ContentType,
    var currentVersion: Int,
    var status: SharedItemStatus = SharedItemStatus.ACTIVE,
    var withdrawnAt: Instant? = null,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
    var updatedAt: Instant,
)

/** Immutable — a revision creates a new document; earlier versions are implicitly superseded. */
@Document("shared_item_versions")
class SharedItemVersion(
    @Id val id: String,
    val sharedItemId: String,
    val roomId: String,
    val version: Int,
    val text: String,
    val origin: ContentOrigin,
    val authorUserId: String,
    /** Display-name snapshot for the provenance label at publication time. */
    val authorDisplayName: String,
    val audienceSnapshotId: String,
    /** Denormalized from the snapshot for efficient authorized reads. */
    val audienceUserIds: List<String>,
    val scope: VisibilityScope,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
)

/**
 * Short-lived, hash-bound preview (trust-model invariant 3). Stores no content — only the hash the
 * confirming publish request must reproduce; any drift in text, type, scope, or recipients breaks
 * the hash and forces a new preview. TTL-expired (not audit-relevant until published).
 */
@Document("share_previews")
class SharePreview(
    @Id val id: String,
    val roomId: String,
    val authorUserId: String,
    val contentHash: String,
    val scope: VisibilityScope,
    val recipientUserIds: List<String>,
    val recipientParticipantIds: List<String>,
    val expiresAt: Instant,
    val createdAt: Instant,
)

object ShareContentHash {
    private val hex = HexFormat.of()

    fun compute(
        text: String,
        contentType: ContentType,
        scope: VisibilityScope,
        recipientUserIds: Collection<String>,
        origin: ContentOrigin,
    ): String {
        val canonical = listOf(
            text, contentType.name, scope.name, recipientUserIds.sorted().joinToString(","), origin.name,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        return hex.formatHex(digest.digest(canonical.toByteArray(StandardCharsets.UTF_8)))
    }
}

package com.bridge.debate.files.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

enum class FileKind { IMAGE, DOCUMENT }

enum class FileStatus { PRIVATE, SHARED, WITHDRAWN }

/**
 * Attachment metadata (design doc §8.3 AttachmentMetadata). Follows the same trust model as
 * messages: PRIVATE to the uploader by default; sharing freezes an audience snapshot; a shared
 * file can be withdrawn (event kept) but never hard-deleted; only PRIVATE files can be deleted.
 * The stored key is always randomized — the user's filename never touches the storage layer.
 */
@Document("attachments")
class StoredFile(
    @Id val id: String,
    val roomId: String,
    val ownerUserId: String,
    val ownerDisplayName: String,
    val originalFilename: String,
    val storedKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val kind: FileKind,
    var status: FileStatus = FileStatus.PRIVATE,
    var scope: String? = null,
    var audienceSnapshotId: String? = null,
    var audienceUserIds: List<String> = emptyList(),
    var sharedAt: Instant? = null,
    var withdrawnAt: Instant? = null,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
)

interface StoredFileRepository : MongoRepository<StoredFile, String> {
    fun findByIdAndRoomId(id: String, roomId: String): StoredFile?
    fun findByRoomIdAndOwnerUserIdOrderByCreatedAtDesc(roomId: String, ownerUserId: String): List<StoredFile>
    fun findByRoomIdAndAudienceUserIdsOrderBySharedAtDesc(roomId: String, userId: String): List<StoredFile>
}

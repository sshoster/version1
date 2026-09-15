package com.bridge.debate.files.application

import com.bridge.debate.audit.application.AuditService
import com.bridge.debate.audit.domain.ActorType
import com.bridge.debate.files.domain.FileKind
import com.bridge.debate.files.domain.FileStatus
import com.bridge.debate.files.domain.StoredFile
import com.bridge.debate.files.domain.StoredFileRepository
import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.messaging.application.AudienceResolver
import com.bridge.debate.messaging.application.AudienceSnapshotWriter
import com.bridge.debate.messaging.domain.VisibilityScope
import com.bridge.debate.participants.domain.ParticipantRole
import com.bridge.debate.permissions.application.PermissionsService
import com.bridge.debate.shared.Ids
import com.bridge.debate.shared.errors.BadRequestException
import com.bridge.debate.shared.errors.ConflictException
import com.bridge.debate.shared.errors.ForbiddenException
import com.bridge.debate.shared.errors.NotFoundException
import com.bridge.debate.shared.outbox.OutboxService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

data class FileView(
    val id: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val kind: FileKind,
    val status: FileStatus,
    val scope: String?,
    val ownerUserId: String,
    val ownerDisplayName: String,
    val mine: Boolean,
    val createdAt: Instant,
    val sharedAt: Instant?,
)

data class FileDownload(val file: StoredFile, val content: InputStream)

/**
 * Files follow the message trust model (design doc §12 + §5): private to the uploader until
 * explicitly shared with a resolved audience; shared files are withdrawable but never deleted;
 * only private files can be deleted (bytes included). Types are allowlisted (no HTML/SVG — they
 * execute in browsers), size ≤ 50MB, stored under randomized keys.
 */
@Service
class FileService(
    private val files: StoredFileRepository,
    private val storage: FileStorage,
    private val scanner: MalwareScanner,
    private val permissions: PermissionsService,
    private val audienceResolver: AudienceResolver,
    private val snapshotWriter: AudienceSnapshotWriter,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
) {
    companion object {
        const val MAX_SIZE_BYTES = 50L * 1024 * 1024

        private val IMAGE_TYPES = mapOf(
            "image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif",
        )
        private val DOCUMENT_TYPES = mapOf(
            "application/pdf" to "pdf",
            "application/msword" to "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.ms-excel" to "xls",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.ms-powerpoint" to "ppt",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
            "text/plain" to "txt",
            "text/csv" to "csv",
        )
        private val hex = HexFormat.of()
    }

    fun upload(roomId: String, actor: AuthenticatedUser, upload: MultipartFile): FileView {
        val participant = permissions.requireRole(
            roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER, ParticipantRole.ADVISOR,
        )
        val contentType = upload.contentType?.lowercase()?.substringBefore(';')?.trim() ?: ""
        val kind = when {
            IMAGE_TYPES.containsKey(contentType) -> FileKind.IMAGE
            DOCUMENT_TYPES.containsKey(contentType) -> FileKind.DOCUMENT
            else -> throw BadRequestException("This file type is not supported (images, PDF, Office, text)")
        }
        if (upload.size <= 0 || upload.size > MAX_SIZE_BYTES) {
            throw BadRequestException("Files can be up to 50MB")
        }
        val extension = IMAGE_TYPES[contentType] ?: DOCUMENT_TYPES.getValue(contentType)
        val key = "rooms/$roomId/${Ids.newId()}.$extension"

        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(upload.inputStream, digest).use { stream ->
            storage.store(key, stream, upload.size, contentType)
        }
        scanner.scan(key, contentType, upload.size)

        val file = files.insert(
            StoredFile(
                id = Ids.newId(),
                roomId = roomId,
                ownerUserId = actor.userId,
                ownerDisplayName = participant.displayName,
                originalFilename = sanitizeFilename(upload.originalFilename ?: "file.$extension"),
                storedKey = key,
                contentType = contentType,
                sizeBytes = upload.size,
                sha256 = hex.formatHex(digest.digest()),
                kind = kind,
                createdAt = Instant.now(),
            ),
        )
        // Private upload: audited without the filename (it can itself be sensitive).
        auditService.append(
            roomId, ActorType.USER, actor.userId, "FILE_UPLOADED", "StoredFile", file.id,
            metadata = mapOf("kind" to kind.name, "size" to upload.size.toString()),
        )
        return file.toView(actor)
    }

    fun list(roomId: String, actor: AuthenticatedUser): List<FileView> {
        permissions.requireParticipant(roomId, actor.userId)
        val mine = files.findByRoomIdAndOwnerUserIdOrderByCreatedAtDesc(roomId, actor.userId)
        val sharedWithMe = files.findByRoomIdAndAudienceUserIdsOrderBySharedAtDesc(roomId, actor.userId)
            .filter { it.ownerUserId != actor.userId && it.status == FileStatus.SHARED }
        return (mine + sharedWithMe).map { it.toView(actor) }
    }

    @Transactional
    fun share(
        roomId: String,
        fileId: String,
        actor: AuthenticatedUser,
        scope: VisibilityScope,
        recipientParticipantIds: List<String>?,
    ): FileView {
        val participant = permissions.requireRole(
            roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER, ParticipantRole.ADVISOR,
        )
        val file = files.findByIdAndRoomId(fileId, roomId) ?: throw NotFoundException("This file was not found")
        if (file.ownerUserId != actor.userId) throw ForbiddenException("Only the uploader can share a file")
        if (file.status != FileStatus.PRIVATE) throw ConflictException("This file was already shared")

        val audience = audienceResolver.resolve(roomId, participant, scope, recipientParticipantIds)
        val snapshot = snapshotWriter.create(roomId, scope, audience)

        file.status = FileStatus.SHARED
        file.scope = scope.name
        file.audienceSnapshotId = snapshot.id
        file.audienceUserIds = audience.userIds
        file.sharedAt = Instant.now()
        files.save(file)

        auditService.append(
            roomId, ActorType.USER, actor.userId, "FILE_SHARED", "StoredFile", fileId,
            metadata = mapOf("scope" to scope.name, "kind" to file.kind.name),
            audienceSnapshotId = snapshot.id,
        )
        outboxService.enqueue(
            roomId, "FILE_SHARED",
            mapOf("resourceId" to fileId, "audienceUserIds" to audience.userIds, "actorUserId" to actor.userId),
        )
        return file.toView(actor)
    }

    @Transactional
    fun withdraw(roomId: String, fileId: String, actor: AuthenticatedUser) {
        permissions.requireParticipant(roomId, actor.userId)
        val file = files.findByIdAndRoomId(fileId, roomId) ?: throw NotFoundException("This file was not found")
        if (file.ownerUserId != actor.userId) throw ForbiddenException("Only the uploader can withdraw a file")
        if (file.status != FileStatus.SHARED) throw ConflictException("Only a shared file can be withdrawn")

        file.status = FileStatus.WITHDRAWN
        file.withdrawnAt = Instant.now()
        files.save(file)
        auditService.append(
            roomId, ActorType.USER, actor.userId, "FILE_WITHDRAWN", "StoredFile", fileId,
            audienceSnapshotId = file.audienceSnapshotId,
        )
        outboxService.enqueue(
            roomId, "FILE_WITHDRAWN",
            mapOf("resourceId" to fileId, "audienceUserIds" to file.audienceUserIds, "actorUserId" to actor.userId),
        )
    }

    /** Hard delete is allowed ONLY while the file is private (never left the owner's space). */
    fun deletePrivate(roomId: String, fileId: String, actor: AuthenticatedUser) {
        permissions.requireParticipant(roomId, actor.userId)
        val file = files.findByIdAndRoomId(fileId, roomId) ?: throw NotFoundException("This file was not found")
        if (file.ownerUserId != actor.userId) throw ForbiddenException("Only the uploader can delete a file")
        if (file.status != FileStatus.PRIVATE) {
            throw ConflictException("A file that was shared stays in the record — it can only be withdrawn")
        }
        files.deleteById(file.id)
        storage.delete(file.storedKey)
        auditService.append(roomId, ActorType.USER, actor.userId, "FILE_DELETED", "StoredFile", fileId)
    }

    fun download(roomId: String, fileId: String, actor: AuthenticatedUser): FileDownload {
        permissions.requireParticipant(roomId, actor.userId)
        val file = files.findByIdAndRoomId(fileId, roomId) ?: throw NotFoundException("This file was not found")
        val allowed = file.ownerUserId == actor.userId ||
            (file.status == FileStatus.SHARED && actor.userId in file.audienceUserIds)
        if (!allowed) throw NotFoundException("This file was not found")
        return FileDownload(file, storage.open(file.storedKey))
    }

    private fun sanitizeFilename(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').take(160).ifBlank { "file" }

    private fun StoredFile.toView(actor: AuthenticatedUser) = FileView(
        id = id,
        filename = originalFilename,
        contentType = contentType,
        sizeBytes = sizeBytes,
        kind = kind,
        status = status,
        scope = scope,
        ownerUserId = ownerUserId,
        ownerDisplayName = ownerDisplayName,
        mine = ownerUserId == actor.userId,
        createdAt = createdAt,
        sharedAt = sharedAt,
    )
}

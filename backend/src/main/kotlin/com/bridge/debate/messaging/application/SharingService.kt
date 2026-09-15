package com.bridge.debate.messaging.application

import com.bridge.debate.audit.application.AuditService
import com.bridge.debate.audit.domain.ActorType
import com.bridge.debate.discussion.application.RoomDirectory
import com.bridge.debate.discussion.application.RoomLifecycleService
import com.bridge.debate.discussion.domain.RoomStatus
import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.messaging.domain.AudienceSnapshot
import com.bridge.debate.messaging.domain.ContentOrigin
import com.bridge.debate.messaging.domain.ContentType
import com.bridge.debate.messaging.domain.PrivateSender
import com.bridge.debate.messaging.domain.ShareContentHash
import com.bridge.debate.messaging.domain.SharePreview
import com.bridge.debate.messaging.domain.SharedItem
import com.bridge.debate.messaging.domain.SharedItemStatus
import com.bridge.debate.messaging.domain.SharedItemVersion
import com.bridge.debate.messaging.domain.VisibilityScope
import com.bridge.debate.messaging.infrastructure.AudienceSnapshotRepository
import com.bridge.debate.messaging.infrastructure.SharePreviewRepository
import com.bridge.debate.messaging.infrastructure.SharedItemRepository
import com.bridge.debate.messaging.infrastructure.SharedItemVersionRepository
import com.bridge.debate.participants.application.ParticipantDirectory
import com.bridge.debate.participants.domain.Participant
import com.bridge.debate.participants.domain.ParticipantRole
import com.bridge.debate.permissions.application.PermissionsService
import com.bridge.debate.shared.Ids
import com.bridge.debate.shared.errors.BadRequestException
import com.bridge.debate.shared.errors.ConflictException
import com.bridge.debate.shared.errors.ForbiddenException
import com.bridge.debate.shared.errors.NotFoundException
import com.bridge.debate.shared.idempotency.IdempotencyService
import com.bridge.debate.shared.outbox.OutboxService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

data class PreviewResult(
    val previewId: String,
    val text: String,
    val contentType: ContentType,
    val scope: VisibilityScope,
    val origin: ContentOrigin,
    val recipients: List<RecipientView>,
    val expiresAt: Instant,
)

data class RecipientView(val participantId: String, val displayName: String, val roles: Set<ParticipantRole>)

data class SharedItemView(
    val id: String,
    val version: Int,
    val text: String?,
    val origin: ContentOrigin,
    val authorUserId: String,
    val authorDisplayName: String,
    val scope: VisibilityScope,
    val status: SharedItemStatus,
    val mine: Boolean,
    val createdAt: Instant,
    val versionCreatedAt: Instant,
)

data class SharedItemVersionView(
    val version: Int,
    val text: String,
    val origin: ContentOrigin,
    val scope: VisibilityScope,
    val authorDisplayName: String,
    val superseded: Boolean,
    val createdAt: Instant,
)

/**
 * The controlled sharing flow (design doc §5.2, trust-model invariants 2/3/9): preview binds the
 * exact content+scope+recipients by hash; publish revalidates everything against current server
 * state; versions and audience snapshots are immutable; withdrawal never erases history.
 */
@Service
class SharingService(
    private val previews: SharePreviewRepository,
    private val items: SharedItemRepository,
    private val versions: SharedItemVersionRepository,
    private val snapshots: AudienceSnapshotRepository,
    private val permissions: PermissionsService,
    private val directory: ParticipantDirectory,
    private val audienceResolver: AudienceResolver,
    private val privateMessages: PrivateMessageService,
    private val rooms: RoomDirectory,
    private val lifecycle: RoomLifecycleService,
    private val auditService: AuditService,
    private val outboxService: OutboxService,
    private val idempotencyService: IdempotencyService,
    private val transactionTemplate: TransactionTemplate,
) {
    companion object {
        private val PREVIEW_TTL: Duration = Duration.ofMinutes(15)
        private const val MAX_LENGTH = 8000
        private val SHAREABLE_STATUSES = setOf(RoomStatus.INTAKE, RoomStatus.ACTIVE, RoomStatus.WAITING_FOR_USER)
        private const val SYSTEM_AUTHOR_ID = "system"
        private const val SYSTEM_AUTHOR_NAME = "Bridge AI"
    }

    // ---------------- preview ----------------

    fun preview(
        roomId: String,
        actor: AuthenticatedUser,
        text: String,
        scope: VisibilityScope,
        requestedParticipantIds: List<String>?,
        origin: ContentOrigin,
        sourceDraftId: String?,
    ): PreviewResult {
        val author = requireSharer(roomId, actor)
        // Same gate as publish — a preview that can never be confirmed is just confusing.
        val room = rooms.find(roomId) ?: throw NotFoundException("This discussion was not found")
        if (room.status !in SHAREABLE_STATUSES) {
            throw ConflictException("Messages cannot be shared while the discussion is ${room.status}")
        }
        val trimmed = validateText(text)
        val effectiveOrigin = validateOrigin(roomId, actor, author, origin, sourceDraftId, trimmed)
        val audience = audienceResolver.resolve(roomId, author, scope, requestedParticipantIds)

        val preview = SharePreview(
            id = Ids.newId(),
            roomId = roomId,
            authorUserId = actor.userId,
            contentHash = ShareContentHash.compute(trimmed, ContentType.TEXT, scope, audience.userIds, effectiveOrigin),
            scope = scope,
            recipientUserIds = audience.userIds,
            recipientParticipantIds = audience.participantIds,
            expiresAt = Instant.now().plus(PREVIEW_TTL),
            createdAt = Instant.now(),
        )
        previews.insert(preview)

        val byId = directory.activeParticipants(roomId).associateBy { it.id }
        return PreviewResult(
            previewId = preview.id,
            text = trimmed,
            contentType = ContentType.TEXT,
            scope = scope,
            origin = effectiveOrigin,
            recipients = audience.participantIds.mapNotNull { byId[it] }
                .map { RecipientView(it.id, it.displayName, it.roles) },
            expiresAt = preview.expiresAt,
        )
    }

    // ---------------- publish ----------------

    fun publish(
        roomId: String,
        actor: AuthenticatedUser,
        previewId: String,
        text: String,
        scope: VisibilityScope,
        requestedParticipantIds: List<String>?,
        origin: ContentOrigin,
        sourceDraftId: String?,
        existingItemId: String?,
        idempotencyKey: String?,
    ): SharedItemView =
        idempotencyService.execute(roomId, "SHARED_ITEM_PUBLISH", idempotencyKey, SharedItemView::class.java) {
            // Validation and preview consumption happen OUTSIDE the transaction: a failed publish
            // must still consume the preview (single-use), which a rollback would otherwise undo.
            val author = requireSharer(roomId, actor)
            val room = rooms.find(roomId) ?: throw NotFoundException("This discussion was not found")
            if (room.status !in SHAREABLE_STATUSES) {
                throw ConflictException("Messages cannot be shared while the discussion is ${room.status}")
            }
            val trimmed = validateText(text)
            val effectiveOrigin = validateOrigin(roomId, actor, author, origin, sourceDraftId, trimmed)

            val preview = previews.findByIdAndRoomIdAndAuthorUserId(previewId, roomId, actor.userId)
                ?: throw ConflictException("The preview is no longer valid. Review the message again.")
            previews.deleteById(preview.id) // consumed regardless of outcome
            if (preview.expiresAt.isBefore(Instant.now())) {
                throw ConflictException("The preview expired. Review the message again.")
            }

            // Recipients are resolved AGAIN from current server state — never taken from the client
            // or the stale preview (invariant 11); any drift from the previewed content breaks the hash.
            val audience = audienceResolver.resolve(roomId, author, scope, requestedParticipantIds)
            val currentHash = ShareContentHash.compute(trimmed, ContentType.TEXT, scope, audience.userIds, effectiveOrigin)
            if (currentHash != preview.contentHash) {
                throw ConflictException("The message or its recipients changed after the preview. Review it again.")
            }

            transactionTemplate.execute {
                publishInternal(roomId, actor, author, room.status, trimmed, scope, audience, effectiveOrigin, existingItemId)
            }!!
        }

    private fun publishInternal(
        roomId: String,
        actor: AuthenticatedUser,
        author: Participant,
        roomStatus: RoomStatus,
        trimmed: String,
        scope: VisibilityScope,
        audience: Audience,
        effectiveOrigin: ContentOrigin,
        existingItemId: String?,
    ): SharedItemView {
        val now = Instant.now()
        val item = if (existingItemId == null) {
            items.insert(
                SharedItem(
                    id = Ids.newId(),
                    roomId = roomId,
                    authorUserId = actor.userId,
                    contentType = ContentType.TEXT,
                    currentVersion = 1,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else {
            val existing = items.findByIdAndRoomId(existingItemId, roomId)
                ?: throw NotFoundException("This shared message was not found")
            if (existing.authorUserId != actor.userId) {
                throw ForbiddenException("Only the author can update a shared message")
            }
            if (existing.status == SharedItemStatus.WITHDRAWN) {
                throw ConflictException("A withdrawn message cannot be updated")
            }
            existing.currentVersion += 1
            existing.updatedAt = now
            items.save(existing)
        }

        val snapshot = snapshots.insert(
            AudienceSnapshot(
                id = Ids.newId(),
                roomId = roomId,
                scope = scope,
                participantIds = audience.participantIds,
                userIds = audience.userIds,
                createdAt = now,
            ),
        )

        val version = versions.insert(
            SharedItemVersion(
                id = Ids.newId(),
                sharedItemId = item.id,
                roomId = roomId,
                version = item.currentVersion,
                text = trimmed,
                origin = effectiveOrigin,
                authorUserId = actor.userId,
                authorDisplayName = author.displayName,
                audienceSnapshotId = snapshot.id,
                audienceUserIds = audience.userIds,
                scope = scope,
                createdAt = now,
            ),
        )

        auditService.append(
            roomId = roomId,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = "SHARED_ITEM_PUBLISHED",
            targetType = "SharedItem",
            targetId = item.id,
            metadata = mapOf(
                "version" to version.version.toString(),
                "origin" to effectiveOrigin.name,
                "scope" to scope.name,
            ),
            audienceSnapshotId = snapshot.id,
        )
        outboxService.enqueue(
            roomId = roomId,
            type = "SHARED_ITEM_PUBLISHED",
            payload = mapOf(
                "resourceId" to item.id,
                "resourceVersion" to version.version,
                "audienceUserIds" to audience.userIds,
                "actorUserId" to actor.userId,
            ),
        )

        if (roomStatus == RoomStatus.INTAKE) {
            lifecycle.transition(roomId, RoomStatus.ACTIVE, ActorType.SYSTEM, null, reason = "first shared message")
        }

        return SharedItemView(
            id = item.id,
            version = version.version,
            text = trimmed,
            origin = effectiveOrigin,
            authorUserId = actor.userId,
            authorDisplayName = author.displayName,
            scope = scope,
            status = item.status,
            mine = true,
            createdAt = item.createdAt,
            versionCreatedAt = version.createdAt,
        )
    }

    // ---------------- system notes ----------------

    /**
     * Posts a SYSTEM_GENERATED note to the common chat — no preview/approval, because callers may
     * only pass content derived from material every room participant already sees (e.g. the
     * assistants' cycle summary, which is distilled from the shared negotiation transcript).
     * Server-internal: no controller exposes this, so SYSTEM_GENERATED stays non-client-settable.
     */
    fun publishSystemNote(roomId: String, text: String) {
        val trimmed = text.trim().take(MAX_LENGTH)
        if (trimmed.isEmpty()) return
        val audienceParticipants = directory.activeParticipants(roomId)
        if (audienceParticipants.isEmpty()) return
        val now = Instant.now()
        transactionTemplate.execute {
            val item = items.insert(
                SharedItem(
                    id = Ids.newId(),
                    roomId = roomId,
                    authorUserId = SYSTEM_AUTHOR_ID,
                    contentType = ContentType.TEXT,
                    currentVersion = 1,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val snapshot = snapshots.insert(
                AudienceSnapshot(
                    id = Ids.newId(),
                    roomId = roomId,
                    scope = VisibilityScope.ALL_ROOM_PARTICIPANTS,
                    participantIds = audienceParticipants.map { it.id },
                    userIds = audienceParticipants.map { it.userId },
                    createdAt = now,
                ),
            )
            val version = versions.insert(
                SharedItemVersion(
                    id = Ids.newId(),
                    sharedItemId = item.id,
                    roomId = roomId,
                    version = 1,
                    text = trimmed,
                    origin = ContentOrigin.SYSTEM_GENERATED,
                    authorUserId = SYSTEM_AUTHOR_ID,
                    authorDisplayName = SYSTEM_AUTHOR_NAME,
                    audienceSnapshotId = snapshot.id,
                    audienceUserIds = audienceParticipants.map { it.userId },
                    scope = VisibilityScope.ALL_ROOM_PARTICIPANTS,
                    createdAt = now,
                ),
            )
            auditService.append(
                roomId = roomId,
                actorType = ActorType.SYSTEM,
                actorId = null,
                action = "SHARED_ITEM_PUBLISHED",
                targetType = "SharedItem",
                targetId = item.id,
                metadata = mapOf("version" to "1", "origin" to ContentOrigin.SYSTEM_GENERATED.name),
                audienceSnapshotId = snapshot.id,
            )
            outboxService.enqueue(
                roomId = roomId,
                type = "SHARED_ITEM_PUBLISHED",
                payload = mapOf(
                    "resourceId" to item.id,
                    "resourceVersion" to version.version,
                    "audienceUserIds" to audienceParticipants.map { it.userId },
                    "actorUserId" to null,
                ),
            )
        }
    }

    // ---------------- reads ----------------

    fun list(roomId: String, actor: AuthenticatedUser, limit: Int = 200): List<SharedItemView> {
        permissions.requireParticipant(roomId, actor.userId)
        val visible = versions.findByRoomIdAndAudienceUserIdsOrderByCreatedAtAsc(roomId, actor.userId)
        if (visible.isEmpty()) return emptyList()

        val itemsById = items.findAllById(visible.map { it.sharedItemId }.distinct()).associateBy { it.id }
        return visible
            .groupBy { it.sharedItemId }
            .mapNotNull { (itemId, itemVersions) ->
                val item = itemsById[itemId] ?: return@mapNotNull null
                // The latest version this user is authorized to see (audience may differ per version).
                val latest = itemVersions.maxBy { it.version }
                SharedItemView(
                    id = item.id,
                    version = latest.version,
                    // Withdrawn content stays in history but is not re-displayed in the live list.
                    text = if (item.status == SharedItemStatus.WITHDRAWN) null else latest.text,
                    origin = latest.origin,
                    authorUserId = latest.authorUserId,
                    authorDisplayName = latest.authorDisplayName,
                    scope = latest.scope,
                    status = item.status,
                    mine = latest.authorUserId == actor.userId,
                    createdAt = item.createdAt,
                    versionCreatedAt = latest.createdAt,
                )
            }
            .sortedBy { it.createdAt }
            .takeLast(limit.coerceIn(1, 500))
    }

    fun versionsOf(roomId: String, itemId: String, actor: AuthenticatedUser): List<SharedItemVersionView> {
        permissions.requireParticipant(roomId, actor.userId)
        val item = items.findByIdAndRoomId(itemId, roomId) ?: throw NotFoundException("This shared message was not found")
        val authorized = versions.findBySharedItemIdAndRoomIdOrderByVersionAsc(itemId, roomId)
            .filter { actor.userId in it.audienceUserIds }
        if (authorized.isEmpty()) throw NotFoundException("This shared message was not found")
        return authorized.map {
            SharedItemVersionView(
                version = it.version,
                text = it.text,
                origin = it.origin,
                scope = it.scope,
                authorDisplayName = it.authorDisplayName,
                superseded = it.version < item.currentVersion,
                createdAt = it.createdAt,
            )
        }
    }

    // ---------------- withdraw ----------------

    @Transactional
    fun withdraw(roomId: String, itemId: String, actor: AuthenticatedUser) {
        permissions.requireParticipant(roomId, actor.userId)
        val item = items.findByIdAndRoomId(itemId, roomId) ?: throw NotFoundException("This shared message was not found")
        if (item.authorUserId != actor.userId) {
            throw ForbiddenException("Only the author can withdraw a shared message")
        }
        if (item.status == SharedItemStatus.WITHDRAWN) return // idempotent

        item.status = SharedItemStatus.WITHDRAWN
        item.withdrawnAt = Instant.now()
        item.updatedAt = Instant.now()
        items.save(item)

        val latest = versions.findBySharedItemIdAndRoomIdAndVersion(itemId, roomId, item.currentVersion)
        auditService.append(
            roomId = roomId,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = "SHARED_ITEM_WITHDRAWN",
            targetType = "SharedItem",
            targetId = itemId,
            audienceSnapshotId = latest?.audienceSnapshotId,
        )
        outboxService.enqueue(
            roomId = roomId,
            type = "SHARED_ITEM_WITHDRAWN",
            payload = mapOf(
                "resourceId" to itemId,
                "audienceUserIds" to (latest?.audienceUserIds ?: emptyList<String>()),
                "actorUserId" to actor.userId,
            ),
        )
    }

    // ---------------- validation helpers ----------------

    private fun requireSharer(roomId: String, actor: AuthenticatedUser): Participant =
        permissions.requireRole(
            roomId, actor.userId,
            ParticipantRole.PARTY, ParticipantRole.OWNER, ParticipantRole.ADVISOR,
        )

    private fun validateText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) {
            throw BadRequestException("The message must be between 1 and $MAX_LENGTH characters")
        }
        return trimmed
    }

    /**
     * Provenance rules (trust-model invariant 6): AI-origin claims must reference a real assistant
     * draft belonging to the author; ACCEPTED means the exact draft text; advisors always publish
     * as ADVISOR_AUTHORED; SYSTEM_GENERATED is never client-settable.
     */
    private fun validateOrigin(
        roomId: String,
        actor: AuthenticatedUser,
        author: Participant,
        origin: ContentOrigin,
        sourceDraftId: String?,
        text: String,
    ): ContentOrigin {
        if (origin == ContentOrigin.SYSTEM_GENERATED) {
            throw BadRequestException("This wording origin is reserved for the system")
        }
        val isAdvisorOnly = ParticipantRole.ADVISOR in author.roles &&
            ParticipantRole.PARTY !in author.roles && ParticipantRole.OWNER !in author.roles
        if (isAdvisorOnly) {
            if (sourceDraftId != null) throw BadRequestException("Advisors publish their own wording")
            return ContentOrigin.ADVISOR_AUTHORED
        }
        return when (origin) {
            ContentOrigin.USER_AUTHORED -> {
                if (sourceDraftId != null) throw BadRequestException("Own wording cannot reference a suggestion")
                origin
            }
            ContentOrigin.AI_DRAFT_ACCEPTED, ContentOrigin.AI_DRAFT_USER_EDITED -> {
                val draftId = sourceDraftId
                    ?: throw BadRequestException("An AI-assisted wording must reference the suggestion it came from")
                val (draft, draftText) = privateMessages.findOwnMessage(roomId, draftId, actor)
                    ?: throw BadRequestException("The referenced suggestion was not found")
                if (draft.sender != PrivateSender.ASSISTANT) {
                    throw BadRequestException("The referenced message is not an assistant suggestion")
                }
                if (origin == ContentOrigin.AI_DRAFT_ACCEPTED && draftText != text) {
                    throw BadRequestException("The text differs from the suggestion — mark it as edited")
                }
                origin
            }
            ContentOrigin.ADVISOR_AUTHORED ->
                throw BadRequestException("Only advisors publish advisor wording")
            ContentOrigin.SYSTEM_GENERATED -> error("unreachable")
        }
    }
}

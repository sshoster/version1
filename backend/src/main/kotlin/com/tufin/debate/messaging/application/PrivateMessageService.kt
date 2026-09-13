package com.tufin.debate.messaging.application

import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.discussion.application.RoomDirectory
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.llm.application.LlmProvider
import com.tufin.debate.llm.application.LlmRequest
import com.tufin.debate.messaging.domain.PrivateMessage
import com.tufin.debate.messaging.domain.PrivateSender
import com.tufin.debate.messaging.infrastructure.PrivateMessageRepository
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.permissions.application.PermissionsService
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.crypto.FieldCipher
import com.tufin.debate.shared.errors.BadRequestException
import com.tufin.debate.shared.errors.NotFoundException
import kotlinx.coroutines.runBlocking
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.time.Instant

data class PrivateMessageView(
    val id: String,
    val sender: PrivateSender,
    val text: String,
    val sourceMessageId: String?,
    val createdAt: Instant,
)

/**
 * The private space: everything here is visible only to its author and their assistant
 * (trust-model invariant 1). Every read path is keyed by (roomId, authorUserId = caller).
 */
@Service
class PrivateMessageService(
    private val messages: PrivateMessageRepository,
    private val permissions: PermissionsService,
    private val rooms: RoomDirectory,
    private val llmProvider: LlmProvider,
    private val cipher: FieldCipher,
    private val auditService: AuditService,
) {
    companion object {
        const val DRAFT_TEMPLATE_ID = "draft/v1"
        private const val MAX_LENGTH = 8000
    }

    private val draftSystemPrompt: String by lazy {
        ClassPathResource("prompts/draft/v1.md").inputStream.readBytes().toString(Charsets.UTF_8)
    }

    fun write(roomId: String, actor: AuthenticatedUser, text: String): PrivateMessageView {
        requirePartyWithAssistant(roomId, actor)
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) {
            throw BadRequestException("The message must be between 1 and $MAX_LENGTH characters")
        }
        val message = PrivateMessage(
            id = Ids.newId(),
            roomId = roomId,
            authorUserId = actor.userId,
            sender = PrivateSender.USER,
            text = cipher.encrypt(trimmed),
            createdAt = Instant.now(),
        )
        messages.insert(message)
        // Audit records THAT a private message exists — never its content (metadata is id-only).
        auditService.append(
            roomId = roomId,
            actorType = ActorType.USER,
            actorId = actor.userId,
            action = "PRIVATE_MESSAGE_WRITTEN",
            targetType = "PrivateMessage",
            targetId = message.id,
        )
        return message.toView(trimmed)
    }

    fun list(roomId: String, actor: AuthenticatedUser): List<PrivateMessageView> {
        requirePartyWithAssistant(roomId, actor)
        return messages.findByRoomIdAndAuthorUserIdOrderByCreatedAtAsc(roomId, actor.userId)
            .map { it.toView(cipher.decrypt(it.text)) }
    }

    /** Generates a calm alternative draft for the caller's own message (design doc §5.2 step 2). */
    fun aiDraft(roomId: String, messageId: String, actor: AuthenticatedUser): PrivateMessageView {
        requirePartyWithAssistant(roomId, actor)
        val source = messages.findByIdAndRoomIdAndAuthorUserId(messageId, roomId, actor.userId)
            ?: throw NotFoundException("This message was not found")
        if (source.sender != PrivateSender.USER) {
            throw BadRequestException("A suggestion can only be created for your own message")
        }

        val room = rooms.find(roomId) ?: throw NotFoundException("This discussion was not found")
        val sourceText = cipher.decrypt(source.text)
        val response = runBlocking {
            llmProvider.generate(
                LlmRequest(
                    templateId = DRAFT_TEMPLATE_ID,
                    system = draftSystemPrompt
                        .replace("{{roomTitle}}", room.title)
                        .replace("{{objective}}", room.objective ?: ""),
                    user = sourceText,
                ),
            )
        }

        val draft = PrivateMessage(
            id = Ids.newId(),
            roomId = roomId,
            authorUserId = actor.userId,
            sender = PrivateSender.ASSISTANT,
            text = cipher.encrypt(response.text),
            sourceMessageId = source.id,
            llmProvider = response.provider,
            llmModel = response.model,
            promptTemplateId = DRAFT_TEMPLATE_ID,
            createdAt = Instant.now(),
        )
        messages.insert(draft)
        auditService.append(
            roomId = roomId,
            actorType = ActorType.AI,
            actorId = actor.userId,
            action = "AI_DRAFT_CREATED",
            targetType = "PrivateMessage",
            targetId = draft.id,
            metadata = mapOf("provider" to response.provider, "template" to DRAFT_TEMPLATE_ID),
        )
        return draft.toView(response.text)
    }

    /**
     * Internal read for the negotiation context builder: the user's own USER-authored notes,
     * decrypted. Callers must pass only the userId whose agent context is being built.
     */
    fun notesFor(roomId: String, userId: String): List<String> =
        messages.findByRoomIdAndAuthorUserIdOrderByCreatedAtAsc(roomId, userId)
            .filter { it.sender == PrivateSender.USER }
            .map { cipher.decrypt(it.text) }

    /** Internal lookup used by the sharing flow to validate draft-origin claims. */
    fun findOwnMessage(roomId: String, messageId: String, actor: AuthenticatedUser): Pair<PrivateMessage, String>? {
        val message = messages.findByIdAndRoomIdAndAuthorUserId(messageId, roomId, actor.userId) ?: return null
        return message to cipher.decrypt(message.text)
    }

    private fun requirePartyWithAssistant(roomId: String, actor: AuthenticatedUser) {
        // Only primary parties have a private assistant space in the MVP.
        permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
    }

    private fun PrivateMessage.toView(plainText: String) =
        PrivateMessageView(id, sender, plainText, sourceMessageId, createdAt)
}

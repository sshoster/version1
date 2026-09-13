package com.tufin.debate.messaging.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class PrivateSender { USER, ASSISTANT }

/**
 * A message in a party's private space (`PRIVATE_TO_AUTHOR_AND_AI`): either written by the user or
 * an assistant draft. `text` is stored encrypted via FieldCipher. Readable ONLY by
 * `authorUserId` — never by other participants, advisors, observers, or the other assistant.
 *
 * MVP note: the design doc lists a separate PrivateConversation document; with exactly one
 * conversation per user per room in the MVP, messages are keyed by (roomId, authorUserId) instead.
 */
@Document("private_messages")
class PrivateMessage(
    @Id val id: String,
    val roomId: String,
    val authorUserId: String,
    val sender: PrivateSender,
    /** Encrypted at rest (FieldCipher). */
    var text: String,
    /** For ASSISTANT drafts: the user message this draft responds to. */
    val sourceMessageId: String? = null,
    /** Operational LLM metadata for ASSISTANT messages — never chain-of-thought. */
    val llmProvider: String? = null,
    val llmModel: String? = null,
    val promptTemplateId: String? = null,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
)

package com.tufin.debate.negotiation.application

import com.tufin.debate.audit.application.AuditService
import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.messaging.application.PrivateMessageService
import com.tufin.debate.negotiation.domain.AiAgentProfile
import com.tufin.debate.negotiation.domain.Question
import com.tufin.debate.negotiation.infrastructure.AiAgentProfileRepository
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.permissions.application.PermissionsService
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.crypto.FieldCipher
import org.springframework.stereotype.Service
import java.time.Instant

data class AgentProfileView(val goals: String, val boundaries: String, val flexibility: String)

/**
 * A party's private guidance to their assistant. Stored encrypted; readable and writable only by
 * its owner (the API path enforces PARTY/OWNER + self).
 */
@Service
class AgentProfileService(
    private val profiles: AiAgentProfileRepository,
    private val permissions: PermissionsService,
    private val privateMessages: PrivateMessageService,
    private val cipher: FieldCipher,
    private val auditService: AuditService,
) {
    fun get(roomId: String, actor: AuthenticatedUser): AgentProfileView {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        val profile = profiles.findByRoomIdAndUserId(roomId, actor.userId)
            ?: return AgentProfileView("", "", "")
        return AgentProfileView(
            goals = cipher.decrypt(profile.goals),
            boundaries = cipher.decrypt(profile.boundaries),
            flexibility = cipher.decrypt(profile.flexibility),
        )
    }

    fun put(roomId: String, actor: AuthenticatedUser, goals: String, boundaries: String, flexibility: String): AgentProfileView {
        permissions.requireRole(roomId, actor.userId, ParticipantRole.PARTY, ParticipantRole.OWNER)
        val now = Instant.now()
        val profile = profiles.findByRoomIdAndUserId(roomId, actor.userId)
            ?: AiAgentProfile(id = Ids.newId(), roomId = roomId, userId = actor.userId, createdAt = now, updatedAt = now)
        profile.goals = cipher.encrypt(goals.trim())
        profile.boundaries = cipher.encrypt(boundaries.trim())
        profile.flexibility = cipher.encrypt(flexibility.trim())
        profile.updatedAt = now
        profiles.save(profile)
        // Content never enters the audit trail — only the fact that guidance changed.
        auditService.append(roomId, ActorType.USER, actor.userId, "AGENT_PROFILE_UPDATED", "AiAgentProfile", profile.id)
        return AgentProfileView(goals.trim(), boundaries.trim(), flexibility.trim())
    }

    // ---- internal reads for the orchestrator (caller passes only the context owner's userId) ----

    fun findDecrypted(roomId: String, userId: String): AiAgentProfile? =
        profiles.findByRoomIdAndUserId(roomId, userId)?.also {
            it.goals = cipher.decrypt(it.goals)
            it.boundaries = cipher.decrypt(it.boundaries)
            it.flexibility = cipher.decrypt(it.flexibility)
        }

    fun privateNotes(roomId: String, userId: String): List<String> =
        privateMessages.notesFor(roomId, userId)

    fun decryptQuestion(question: Question): Question =
        question.also { it.answerText = it.answerText?.let(cipher::decrypt) }

    fun encryptAnswer(answer: String): String = cipher.encrypt(answer)
}

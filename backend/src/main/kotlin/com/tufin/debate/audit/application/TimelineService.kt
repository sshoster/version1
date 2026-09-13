package com.tufin.debate.audit.application

import com.tufin.debate.audit.domain.ActorType
import com.tufin.debate.messaging.infrastructure.AudienceSnapshotRepository
import com.tufin.debate.participants.application.ParticipantDirectory
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.permissions.application.PermissionsService
import org.springframework.stereotype.Service
import java.time.Instant

data class TimelineEntry(
    val id: String,
    val action: String,
    val actorType: ActorType,
    val actorDisplayName: String?,
    val targetType: String?,
    val targetId: String?,
    val metadata: Map<String, String>,
    val occurredAt: Instant,
)

/**
 * The human-readable timeline (trust-model invariant 13): audit events filtered by the caller's
 * authorization — audience-scoped events appear only for their audience; private-space events
 * (private messages, AI drafts, questions/answers, agent profiles) appear only for their owner.
 */
@Service
class TimelineService(
    private val auditService: AuditService,
    private val snapshots: AudienceSnapshotRepository,
    private val permissions: PermissionsService,
    private val directory: ParticipantDirectory,
) {
    companion object {
        /** Events belonging to someone's private space: visible on the timeline only to the owner. */
        private val PRIVATE_ACTIONS = setOf(
            "PRIVATE_MESSAGE_WRITTEN", "AI_DRAFT_CREATED", "AGENT_PROFILE_UPDATED", "QUESTION_ANSWERED",
            "FILE_UPLOADED", "FILE_DELETED",
        )

        /** Non-sensitive metadata keys allowed onto the timeline. */
        private val METADATA_ALLOWLIST = setOf("from", "to", "role", "version", "decision", "stopReason", "turns", "scope", "origin")
    }

    fun timeline(roomId: String, userId: String, limit: Int = 200): List<TimelineEntry> {
        val me = permissions.requireParticipant(roomId, userId)
        val isParty = ParticipantRole.PARTY in me.roles || ParticipantRole.OWNER in me.roles
        val names = directory.activeParticipants(roomId).associate { it.userId to it.displayName }

        return auditService.roomEvents(roomId, limit)
            .filter { event ->
                when {
                    event.action in PRIVATE_ACTIONS -> event.actorId == userId
                    // Negotiation and proposal machinery concerns the parties.
                    event.action.startsWith("NEGOTIATION_") || event.action.startsWith("PROPOSAL_") ||
                        event.action.startsWith("APPROVAL_") -> isParty
                    // Audience-scoped content events: only for people who could see the content.
                    event.audienceSnapshotId != null ->
                        snapshots.findById(event.audienceSnapshotId).map { userId in it.userIds }.orElse(false)
                    else -> true
                }
            }
            .map { event ->
                TimelineEntry(
                    id = event.id,
                    action = event.action,
                    actorType = event.actorType,
                    actorDisplayName = event.actorId?.let { names[it] },
                    targetType = event.targetType,
                    targetId = event.targetId,
                    metadata = event.metadata.filterKeys { it in METADATA_ALLOWLIST },
                    occurredAt = event.occurredAt,
                )
            }
    }
}

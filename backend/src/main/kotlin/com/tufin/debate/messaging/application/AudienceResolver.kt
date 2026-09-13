package com.tufin.debate.messaging.application

import com.tufin.debate.messaging.domain.AudienceSnapshot
import com.tufin.debate.messaging.domain.VisibilityScope
import com.tufin.debate.messaging.infrastructure.AudienceSnapshotRepository
import com.tufin.debate.participants.application.ParticipantDirectory
import com.tufin.debate.participants.domain.Participant
import com.tufin.debate.participants.domain.ParticipantRole
import com.tufin.debate.shared.Ids
import com.tufin.debate.shared.errors.BadRequestException
import org.springframework.stereotype.Service
import java.time.Instant

data class Audience(val participantIds: List<String>, val userIds: List<String>)

/** Application-level snapshot writer, reusable by other modules (files) without touching infra. */
@Service
class AudienceSnapshotWriter(private val snapshots: AudienceSnapshotRepository) {
    fun create(roomId: String, scope: VisibilityScope, audience: Audience): AudienceSnapshot =
        snapshots.insert(
            AudienceSnapshot(
                id = Ids.newId(),
                roomId = roomId,
                scope = scope,
                participantIds = audience.participantIds,
                userIds = audience.userIds,
                createdAt = Instant.now(),
            ),
        )
}

/**
 * Resolves a visibility scope into concrete recipients from SERVER state (trust-model invariant 4:
 * client- or model-provided recipient lists are never trusted). The author is always included.
 *
 * MVP simplification (documented): advisors are room-wide, so MY_ADVISORS = author + all active
 * ADVISOR participants; per-party advisor assignment arrives with the permissions matrix phase.
 */
@Service
class AudienceResolver(private val directory: ParticipantDirectory) {

    fun resolve(
        roomId: String,
        author: Participant,
        scope: VisibilityScope,
        requestedParticipantIds: List<String>?,
    ): Audience {
        val active = directory.activeParticipants(roomId)
        val selected: List<Participant> = when (scope) {
            VisibilityScope.PRIVATE_TO_AUTHOR_AND_AI ->
                throw BadRequestException("Private content is not published — remove it from sharing")

            VisibilityScope.MY_ADVISORS ->
                active.filter { ParticipantRole.ADVISOR in it.roles }

            VisibilityScope.SELECTED_PARTICIPANTS -> {
                val requested = requestedParticipantIds.orEmpty().toSet()
                if (requested.isEmpty()) throw BadRequestException("Choose at least one recipient")
                val byId = active.associateBy { it.id }
                requested.map {
                    byId[it] ?: throw BadRequestException("One of the selected recipients is not in this discussion")
                }
            }

            VisibilityScope.ALL_PARTIES ->
                active.filter { ParticipantRole.PARTY in it.roles || ParticipantRole.OWNER in it.roles }

            VisibilityScope.ALL_ROOM_PARTICIPANTS -> active
        }

        val all = (selected + author).distinctBy { it.id }
        return Audience(
            participantIds = all.map { it.id }.sorted(),
            userIds = all.map { it.userId }.distinct().sorted(),
        )
    }
}

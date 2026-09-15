package com.bridge.debate.notifications.api

import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.notifications.infrastructure.PresenceEntry
import com.bridge.debate.notifications.infrastructure.PresenceTracker
import com.bridge.debate.participants.application.ParticipantDirectory
import com.bridge.debate.permissions.application.PermissionsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class PresenceController(
    private val presence: PresenceTracker,
    private val permissions: PermissionsService,
    private val directory: ParticipantDirectory,
) {
    /** Presence of this room's participants — members only. */
    @GetMapping("/api/v1/rooms/{roomId}/presence")
    fun roomPresence(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<PresenceEntry> {
        permissions.requireParticipant(roomId, user.userId)
        val memberIds = directory.activeParticipants(roomId).map { it.userId }
        return presence.presenceFor(roomId, memberIds)
    }
}

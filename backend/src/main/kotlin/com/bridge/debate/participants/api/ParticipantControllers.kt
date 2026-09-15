package com.bridge.debate.participants.api

import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.participants.application.InvitationCreated
import com.bridge.debate.participants.application.InvitationPublicInfo
import com.bridge.debate.participants.application.InvitationService
import com.bridge.debate.participants.application.ParticipantService
import com.bridge.debate.participants.domain.InvitationStatus
import com.bridge.debate.participants.domain.ParticipantRole
import com.bridge.debate.participants.domain.ParticipantStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class CreateInvitationRequest(
    @field:NotNull val role: ParticipantRole = ParticipantRole.PARTY,
    @field:Email val email: String? = null,
    @field:jakarta.validation.constraints.Size(max = 60) val firstName: String? = null,
    @field:jakarta.validation.constraints.Size(max = 60) val lastName: String? = null,
)

data class InvitationSummaryResponse(
    val id: String,
    val role: ParticipantRole,
    val email: String?,
    val invitedName: String?,
    val status: InvitationStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
)

data class ParticipantResponse(
    val id: String,
    val userId: String,
    val displayName: String,
    val roles: Set<ParticipantRole>,
    val status: ParticipantStatus,
    val joinedAt: Instant,
)

data class UpdateParticipantRequest(
    @field:NotEmpty val roles: Set<ParticipantRole> = emptySet(),
)

data class AcceptInvitationResponse(val roomId: String)

@RestController
class InvitationController(private val invitationService: InvitationService) {

    @PostMapping("/api/v1/rooms/{roomId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable roomId: String,
        @RequestBody @Valid request: CreateInvitationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): InvitationCreated = invitationService.create(
        roomId, user, request.role, request.email, request.firstName, request.lastName, idempotencyKey,
    )

    @GetMapping("/api/v1/rooms/{roomId}/invitations")
    fun list(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<InvitationSummaryResponse> =
        invitationService.listForRoom(roomId, user).map {
            InvitationSummaryResponse(
                it.id, it.role, it.email,
                listOfNotNull(it.invitedFirstName, it.invitedLastName).joinToString(" ").takeIf { name -> name.isNotBlank() },
                it.status, it.expiresAt, it.createdAt,
            )
        }

    @GetMapping("/api/v1/invitations/{token}")
    fun publicInfo(@PathVariable token: String): InvitationPublicInfo =
        invitationService.publicInfo(token)

    @PostMapping("/api/v1/invitations/{token}/accept")
    fun accept(
        @PathVariable token: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): AcceptInvitationResponse = AcceptInvitationResponse(invitationService.accept(token, user))

    @PostMapping("/api/v1/rooms/{roomId}/invitations/{invitationId}/revoke")
    fun revoke(
        @PathVariable roomId: String,
        @PathVariable invitationId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = invitationService.revoke(roomId, invitationId, user)
}

data class JoinByCodeRequest(
    @field:jakarta.validation.constraints.NotBlank @field:jakarta.validation.constraints.Size(max = 20)
    val code: String = "",
)

data class DecideJoinRequest(
    @field:NotNull val role: ParticipantRole = ParticipantRole.PARTY,
)

@RestController
class JoinRequestController(private val joinRequests: com.bridge.debate.participants.application.JoinRequestService) {

    /** Ask to join a discussion by its join code — creates a PENDING request for admin review. */
    @PostMapping("/api/v1/join-requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun request(
        @RequestBody @Valid request: JoinByCodeRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = joinRequests.request(user, request.code)

    @GetMapping("/api/v1/join-requests/mine")
    fun mine(@AuthenticationPrincipal user: AuthenticatedUser) = joinRequests.myPending(user)

    @GetMapping("/api/v1/rooms/{roomId}/join-requests")
    fun pending(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = joinRequests.pendingForRoom(roomId, user)

    @PostMapping("/api/v1/rooms/{roomId}/join-requests/{requestId}/approve")
    fun approve(
        @PathVariable roomId: String,
        @PathVariable requestId: String,
        @RequestBody @Valid request: DecideJoinRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = joinRequests.approve(roomId, requestId, user, request.role)

    @PostMapping("/api/v1/rooms/{roomId}/join-requests/{requestId}/reject")
    fun reject(
        @PathVariable roomId: String,
        @PathVariable requestId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = joinRequests.reject(roomId, requestId, user)
}

@RestController
class ParticipantController(private val participantService: ParticipantService) {

    @GetMapping("/api/v1/rooms/{roomId}/participants")
    fun list(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<ParticipantResponse> =
        participantService.list(roomId, user).map {
            ParticipantResponse(it.id, it.userId, it.displayName, it.roles, it.status, it.joinedAt)
        }

    @PatchMapping("/api/v1/rooms/{roomId}/participants/{participantId}")
    fun updateRoles(
        @PathVariable roomId: String,
        @PathVariable participantId: String,
        @RequestBody @Valid request: UpdateParticipantRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ParticipantResponse {
        val updated = participantService.updateRoles(roomId, participantId, user, request.roles)
        return ParticipantResponse(
            updated.id, updated.userId, updated.displayName, updated.roles, updated.status, updated.joinedAt,
        )
    }
}

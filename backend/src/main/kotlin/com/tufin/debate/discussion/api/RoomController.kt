package com.tufin.debate.discussion.api

import com.tufin.debate.discussion.application.RoomService
import com.tufin.debate.discussion.application.RoomView
import com.tufin.debate.discussion.domain.RoomStatus
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.participants.domain.ParticipantRole
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class CreateRoomRequest(
    @field:NotBlank @field:Size(max = 200) val title: String = "",
    @field:Size(max = 4000) val objective: String? = null,
)

data class UpdateRoomRequest(
    @field:Size(max = 200) val title: String? = null,
    @field:Size(max = 4000) val objective: String? = null,
)

data class RoomResponse(
    val id: String,
    val title: String,
    val objective: String?,
    val status: RoomStatus,
    val ownerUserId: String,
    val myRoles: Set<ParticipantRole>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

private fun RoomView.toResponse() = RoomResponse(
    id = room.id,
    title = room.title,
    objective = room.objective,
    status = room.status,
    ownerUserId = room.ownerUserId,
    myRoles = myRoles,
    createdAt = room.createdAt,
    updatedAt = room.updatedAt,
)

@RestController
@RequestMapping("/api/v1/rooms")
class RoomController(private val roomService: RoomService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody @Valid request: CreateRoomRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): RoomResponse = roomService.create(user, request.title, request.objective).toResponse()

    @GetMapping
    fun list(@AuthenticationPrincipal user: AuthenticatedUser): List<RoomResponse> =
        roomService.listForUser(user).map { it.toResponse() }

    @GetMapping("/{roomId}")
    fun get(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): RoomResponse = roomService.get(roomId, user).toResponse()

    @PatchMapping("/{roomId}")
    fun update(
        @PathVariable roomId: String,
        @RequestBody @Valid request: UpdateRoomRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): RoomResponse = roomService.update(roomId, user, request.title, request.objective).toResponse()
}

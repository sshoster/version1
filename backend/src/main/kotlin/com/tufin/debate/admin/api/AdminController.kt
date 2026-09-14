package com.tufin.debate.admin.api

import com.tufin.debate.admin.application.AdminRoomDoc
import com.tufin.debate.admin.application.RoomPurgeService
import com.tufin.debate.identity.application.AdminUserView
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.identity.application.UserAdminService
import com.tufin.debate.shared.errors.NotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

data class AdminCreateUserRequest(
    @field:NotBlank @field:Email val email: String = "",
    @field:NotBlank @field:Size(min = 1, max = 80) val displayName: String = "",
    @field:NotBlank @field:Size(min = 5, max = 200, message = "Password must be at least 5 characters")
    val password: String = "",
)

data class AdminUpdateUserRequest(
    @field:Size(max = 80) val displayName: String? = null,
    @field:Size(min = 5, max = 200, message = "Password must be at least 5 characters")
    val newPassword: String? = null,
)

/**
 * Super-admin dashboard API. Access is an env-configured email allowlist checked per request;
 * everyone else gets 404 so the area's existence is not advertised (same convention as rooms).
 */
@RestController
@Validated
@RequestMapping("/api/v1/admin")
class AdminController(
    private val userAdmin: UserAdminService,
    private val roomPurge: RoomPurgeService,
) {

    private fun gate(user: AuthenticatedUser) {
        if (!userAdmin.isSuperAdmin(user.email)) throw NotFoundException("Not found")
    }

    @GetMapping("/users")
    fun users(
        @RequestParam(required = false) query: String?,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<AdminUserView> {
        gate(user)
        return userAdmin.list(query)
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUser(
        @RequestBody @Valid request: AdminCreateUserRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): AdminUserView {
        gate(user)
        return userAdmin.create(request.email, request.displayName, request.password)
    }

    @PatchMapping("/users/{userId}")
    fun updateUser(
        @PathVariable userId: String,
        @RequestBody @Valid request: AdminUpdateUserRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): AdminUserView {
        gate(user)
        return userAdmin.update(userId, request.displayName, request.newPassword)
    }

    @PostMapping("/users/{userId}/suspend")
    fun suspendUser(@PathVariable userId: String, @AuthenticationPrincipal user: AuthenticatedUser): AdminUserView {
        gate(user)
        return userAdmin.suspend(userId, user.userId)
    }

    @PostMapping("/users/{userId}/reactivate")
    fun reactivateUser(@PathVariable userId: String, @AuthenticationPrincipal user: AuthenticatedUser): AdminUserView {
        gate(user)
        return userAdmin.reactivate(userId)
    }

    @DeleteMapping("/users/{userId}")
    fun deleteUser(@PathVariable userId: String, @AuthenticationPrincipal user: AuthenticatedUser) {
        gate(user)
        userAdmin.delete(userId, user.userId)
    }

    @GetMapping("/rooms")
    fun rooms(@AuthenticationPrincipal user: AuthenticatedUser): List<AdminRoomDoc> {
        gate(user)
        return roomPurge.listRooms()
    }

    @DeleteMapping("/rooms/{roomId}")
    fun deleteRoom(@PathVariable roomId: String, @AuthenticationPrincipal user: AuthenticatedUser) {
        gate(user)
        if (!roomPurge.purgeRoom(roomId)) throw NotFoundException("This discussion was not found")
    }
}

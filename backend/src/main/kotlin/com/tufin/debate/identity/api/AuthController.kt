package com.tufin.debate.identity.api

import com.tufin.debate.identity.application.AuthService
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.identity.application.TokenPair
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.validation.annotation.Validated

data class RegisterRequest(
    @field:NotBlank @field:Email val email: String = "",
    @field:NotBlank @field:Size(min = 1, max = 80) val displayName: String = "",
    @field:NotBlank @field:Size(min = 10, max = 200, message = "Password must be at least 10 characters")
    val password: String = "",
)

data class LoginRequest(
    @field:NotBlank @field:Email val email: String = "",
    @field:NotBlank val password: String = "",
)

data class RefreshRequest(
    @field:NotBlank val refreshToken: String = "",
)

data class UserResponse(val id: String, val email: String, val displayName: String)

data class AuthResponse(val accessToken: String, val refreshToken: String, val user: UserResponse)

private fun TokenPair.toResponse() = AuthResponse(
    accessToken = accessToken,
    refreshToken = refreshToken,
    user = UserResponse(user.userId, user.email, user.displayName),
)

@RestController
@Validated
@RequestMapping("/api/v1/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid request: RegisterRequest): AuthResponse =
        authService.register(request.email, request.displayName, request.password).toResponse()

    @PostMapping("/login")
    fun login(@RequestBody @jakarta.validation.Valid request: LoginRequest): AuthResponse =
        authService.login(request.email, request.password).toResponse()

    @PostMapping("/refresh")
    fun refresh(@RequestBody @jakarta.validation.Valid request: RefreshRequest): AuthResponse =
        authService.refresh(request.refreshToken).toResponse()
}

data class UpdateProfileRequest(
    @field:NotBlank @field:Size(min = 1, max = 80) val displayName: String = "",
)

@RestController
@Validated
@RequestMapping("/api/v1/users")
class UserController(private val authService: AuthService) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal user: AuthenticatedUser): UserResponse {
        val stored = authService.findMe(user.userId)
        return UserResponse(user.userId, stored?.email ?: user.email, stored?.displayName ?: user.displayName)
    }

    @PatchMapping("/me")
    fun updateMe(
        @RequestBody @jakarta.validation.Valid request: UpdateProfileRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): UserResponse {
        val updated = authService.updateProfile(user.userId, request.displayName)
        return UserResponse(updated.id, updated.email, updated.displayName)
    }
}

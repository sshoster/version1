package com.bridge.debate.identity.api

import com.bridge.debate.identity.application.AuthService
import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.identity.application.TokenPair
import com.bridge.debate.identity.application.UserAdminService
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
    @field:NotBlank @field:Size(min = 5, max = 200, message = "Password must be at least 5 characters")
    val password: String = "",
)

data class LoginRequest(
    @field:NotBlank @field:Email val email: String = "",
    @field:NotBlank val password: String = "",
)

data class RefreshRequest(
    @field:NotBlank val refreshToken: String = "",
)

data class GoogleLoginRequest(
    @field:NotBlank val idToken: String = "",
)

/** Public, non-secret configuration the SPA needs before anyone is signed in. */
data class AuthConfigResponse(val googleClientId: String)

data class UserResponse(
    val id: String,
    val email: String,
    val displayName: String,
    /** True only for env-allowlisted super admins; populated on /users/me. */
    val superAdmin: Boolean = false,
)

data class AuthResponse(val accessToken: String, val refreshToken: String, val user: UserResponse)

private fun TokenPair.toResponse() = AuthResponse(
    accessToken = accessToken,
    refreshToken = refreshToken,
    user = UserResponse(user.userId, user.email, user.displayName),
)

@RestController
@Validated
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val securityProperties: com.bridge.debate.identity.application.SecurityProperties,
) {

    /** Public config for the SPA: which Google OAuth client to render the button for (blank = hidden). */
    @GetMapping("/config")
    fun config(): AuthConfigResponse = AuthConfigResponse(securityProperties.googleClientId.trim())

    @PostMapping("/google")
    fun google(@RequestBody @jakarta.validation.Valid request: GoogleLoginRequest): AuthResponse =
        authService.loginWithGoogle(request.idToken).toResponse()

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

data class ChangePasswordRequest(
    @field:NotBlank val currentPassword: String = "",
    @field:NotBlank @field:Size(min = 5, max = 200, message = "Password must be at least 5 characters")
    val newPassword: String = "",
)

@RestController
@Validated
@RequestMapping("/api/v1/users")
class UserController(
    private val authService: AuthService,
    private val userAdminService: UserAdminService,
) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal user: AuthenticatedUser): UserResponse {
        val stored = authService.findMe(user.userId)
        val email = stored?.email ?: user.email
        return UserResponse(
            user.userId, email, stored?.displayName ?: user.displayName,
            superAdmin = userAdminService.isSuperAdmin(email),
        )
    }

    @PostMapping("/me/password")
    fun changePassword(
        @RequestBody @jakarta.validation.Valid request: ChangePasswordRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) {
        authService.changePassword(user.userId, request.currentPassword, request.newPassword)
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

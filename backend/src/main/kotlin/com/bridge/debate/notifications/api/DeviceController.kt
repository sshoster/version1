package com.bridge.debate.notifications.api

import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.notifications.domain.UserDevice
import com.bridge.debate.notifications.domain.UserDeviceRepository
import com.bridge.debate.shared.Ids
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class RegisterDeviceRequest(
    @field:NotBlank @field:Size(max = 4096) val token: String,
    @field:NotBlank @field:Pattern(regexp = "ANDROID|IOS") val platform: String,
)

/** Push-device registry: the app registers its FCM token after sign-in. */
@RestController
@RequestMapping("/api/v1/notifications/devices")
class DeviceController(private val devices: UserDeviceRepository) {

    @PutMapping
    fun register(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: RegisterDeviceRequest,
    ) {
        val existing = devices.findByToken(request.token)
        if (existing != null) {
            // Same physical device, possibly a different signed-in user — re-bind it.
            existing.userId = user.userId
            existing.updatedAt = Instant.now()
            devices.save(existing)
            return
        }
        try {
            devices.insert(UserDevice(Ids.newId(), user.userId, request.token, request.platform, Instant.now()))
        } catch (_: DuplicateKeyException) {
            // Concurrent registration of the same token — the other write wins, nothing to do.
        }
    }

    /** Sign-out: stop pushing to this device. */
    @DeleteMapping
    fun unregister(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam token: String,
    ) {
        val device = devices.findByToken(token) ?: return
        if (device.userId == user.userId) devices.deleteByToken(token)
    }
}

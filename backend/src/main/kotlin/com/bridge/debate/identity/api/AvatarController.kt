package com.bridge.debate.identity.api

import com.bridge.debate.files.application.FileStorage
import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.identity.infrastructure.UserRepository
import com.bridge.debate.shared.errors.BadRequestException
import com.bridge.debate.shared.errors.NotFoundException
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Profile pictures: small images through the same storage port. The UI falls back to a generated
 * initial when none is set. Avatars are visible to any authenticated user (they accompany the
 * display name everywhere).
 */
@RestController
class AvatarController(
    private val users: UserRepository,
    private val storage: FileStorage,
) {
    companion object {
        private const val MAX_AVATAR_BYTES = 2L * 1024 * 1024
        private val TYPES = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp")
    }

    @PostMapping("/api/v1/users/me/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAvatar(
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal principal: AuthenticatedUser,
    ) {
        val contentType = file.contentType?.lowercase()?.substringBefore(';')?.trim() ?: ""
        val extension = TYPES[contentType]
            ?: throw BadRequestException("A profile picture must be a PNG, JPG, or WebP image")
        if (file.size <= 0 || file.size > MAX_AVATAR_BYTES) {
            throw BadRequestException("A profile picture can be up to 2MB")
        }
        val user = users.findById(principal.userId).orElseThrow { NotFoundException("User not found") }

        val key = "avatars/${principal.userId}.$extension"
        file.inputStream.use { storage.store(key, it, file.size, contentType) }
        if (user.avatarKey != null && user.avatarKey != key) {
            runCatching { storage.delete(user.avatarKey!!) }
        }
        user.avatarKey = key
        user.avatarContentType = contentType
        users.save(user)
    }

    @GetMapping("/api/v1/users/{userId}/avatar")
    fun avatar(@PathVariable userId: String): ResponseEntity<InputStreamResource> {
        val user = users.findById(userId).orElseThrow { NotFoundException("No picture") }
        val key = user.avatarKey ?: throw NotFoundException("No picture")
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.parseMediaType(user.avatarContentType ?: "image/png"))
            .body(InputStreamResource(storage.open(key)))
    }
}

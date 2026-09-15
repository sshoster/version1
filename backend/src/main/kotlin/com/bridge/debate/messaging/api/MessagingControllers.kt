package com.bridge.debate.messaging.api

import com.bridge.debate.identity.application.AuthenticatedUser
import com.bridge.debate.messaging.application.PreviewResult
import com.bridge.debate.messaging.application.PrivateMessageService
import com.bridge.debate.messaging.application.PrivateMessageView
import com.bridge.debate.messaging.application.SharedItemVersionView
import com.bridge.debate.messaging.application.SharedItemView
import com.bridge.debate.messaging.application.SharingService
import com.bridge.debate.messaging.domain.ContentOrigin
import com.bridge.debate.messaging.domain.VisibilityScope
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class WritePrivateMessageRequest(
    @field:NotBlank @field:Size(max = 8000) val text: String = "",
)

data class SharePreviewRequest(
    @field:NotBlank @field:Size(max = 8000) val text: String = "",
    @field:NotNull val scope: VisibilityScope = VisibilityScope.ALL_PARTIES,
    val recipientParticipantIds: List<String>? = null,
    @field:NotNull val origin: ContentOrigin = ContentOrigin.USER_AUTHORED,
    val sourceDraftId: String? = null,
)

data class PublishSharedItemRequest(
    @field:NotBlank val previewId: String = "",
    @field:NotBlank @field:Size(max = 8000) val text: String = "",
    @field:NotNull val scope: VisibilityScope = VisibilityScope.ALL_PARTIES,
    val recipientParticipantIds: List<String>? = null,
    @field:NotNull val origin: ContentOrigin = ContentOrigin.USER_AUTHORED,
    val sourceDraftId: String? = null,
    /** Present when publishing a new version of an existing shared item. */
    val itemId: String? = null,
)

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/private-messages")
class PrivateMessageController(private val privateMessages: PrivateMessageService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun write(
        @PathVariable roomId: String,
        @RequestBody @Valid request: WritePrivateMessageRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): PrivateMessageView = privateMessages.write(roomId, user, request.text)

    @GetMapping
    fun list(
        @PathVariable roomId: String,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "200") limit: Int,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<PrivateMessageView> = privateMessages.list(roomId, user, limit)

    @PostMapping("/{messageId}/ai-draft")
    @ResponseStatus(HttpStatus.CREATED)
    fun aiDraft(
        @PathVariable roomId: String,
        @PathVariable messageId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): PrivateMessageView = privateMessages.aiDraft(roomId, messageId, user)
}

@RestController
class SharingController(private val sharing: SharingService) {

    @PostMapping("/api/v1/rooms/{roomId}/share-previews")
    @ResponseStatus(HttpStatus.CREATED)
    fun preview(
        @PathVariable roomId: String,
        @RequestBody @Valid request: SharePreviewRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): PreviewResult = sharing.preview(
        roomId, user, request.text, request.scope, request.recipientParticipantIds,
        request.origin, request.sourceDraftId,
    )

    @PostMapping("/api/v1/rooms/{roomId}/shared-items")
    @ResponseStatus(HttpStatus.CREATED)
    fun publish(
        @PathVariable roomId: String,
        @RequestBody @Valid request: PublishSharedItemRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): SharedItemView = sharing.publish(
        roomId, user, request.previewId, request.text, request.scope, request.recipientParticipantIds,
        request.origin, request.sourceDraftId, request.itemId, idempotencyKey,
    )

    @GetMapping("/api/v1/rooms/{roomId}/shared-items")
    fun list(
        @PathVariable roomId: String,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "200") limit: Int,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<SharedItemView> = sharing.list(roomId, user, limit)

    @GetMapping("/api/v1/rooms/{roomId}/shared-items/{itemId}/versions")
    fun versions(
        @PathVariable roomId: String,
        @PathVariable itemId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<SharedItemVersionView> = sharing.versionsOf(roomId, itemId, user)

    @PostMapping("/api/v1/rooms/{roomId}/shared-items/{itemId}/withdraw")
    fun withdraw(
        @PathVariable roomId: String,
        @PathVariable itemId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = sharing.withdraw(roomId, itemId, user)
}

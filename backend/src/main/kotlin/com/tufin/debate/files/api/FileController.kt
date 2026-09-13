package com.tufin.debate.files.api

import com.tufin.debate.files.application.FileService
import com.tufin.debate.files.application.FileView
import com.tufin.debate.files.domain.FileKind
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.messaging.domain.VisibilityScope
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

data class ShareFileRequest(
    @field:NotNull val scope: VisibilityScope = VisibilityScope.ALL_PARTIES,
    val recipientParticipantIds: List<String>? = null,
)

@RestController
class FileController(private val fileService: FileService) {

    @PostMapping("/api/v1/rooms/{roomId}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @PathVariable roomId: String,
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): FileView = fileService.upload(roomId, user, file)

    @GetMapping("/api/v1/rooms/{roomId}/files")
    fun list(
        @PathVariable roomId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<FileView> = fileService.list(roomId, user)

    @PostMapping("/api/v1/rooms/{roomId}/files/{fileId}/share")
    fun share(
        @PathVariable roomId: String,
        @PathVariable fileId: String,
        @RequestBody @Valid request: ShareFileRequest,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): FileView = fileService.share(roomId, fileId, user, request.scope, request.recipientParticipantIds)

    @PostMapping("/api/v1/rooms/{roomId}/files/{fileId}/withdraw")
    fun withdraw(
        @PathVariable roomId: String,
        @PathVariable fileId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = fileService.withdraw(roomId, fileId, user)

    @DeleteMapping("/api/v1/rooms/{roomId}/files/{fileId}")
    fun deletePrivate(
        @PathVariable roomId: String,
        @PathVariable fileId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ) = fileService.deletePrivate(roomId, fileId, user)

    @GetMapping("/api/v1/rooms/{roomId}/files/{fileId}/content")
    fun download(
        @PathVariable roomId: String,
        @PathVariable fileId: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ResponseEntity<InputStreamResource> {
        val download = fileService.download(roomId, fileId, user)
        val file = download.file
        val disposition = if (file.kind == FileKind.IMAGE) {
            ContentDisposition.inline()
        } else {
            ContentDisposition.attachment()
        }.filename(file.originalFilename, Charsets.UTF_8).build()

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .contentLength(file.sizeBytes)
            .contentType(MediaType.parseMediaType(file.contentType))
            .body(InputStreamResource(download.content))
    }
}

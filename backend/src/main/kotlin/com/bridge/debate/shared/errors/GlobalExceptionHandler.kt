package com.bridge.debate.shared.errors

import com.bridge.debate.shared.web.CorrelationIdFilter
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

data class ApiError(
    val code: String,
    val message: String,
    val correlationId: String? = null,
    val errorId: String? = null,
    val fieldErrors: Map<String, String>? = null,
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun correlationId(): String? = MDC.get(CorrelationIdFilter.MDC_KEY)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException): ResponseEntity<ApiError> =
        ResponseEntity.status(ex.status)
            .body(ApiError(ex.code, ex.message ?: ex.code, correlationId()))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val fields = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return ResponseEntity.badRequest()
            .body(ApiError("VALIDATION_FAILED", "Some fields are invalid", correlationId(), fieldErrors = fields))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest()
            .body(ApiError("BAD_REQUEST", "Request body could not be read", correlationId()))

    @ExceptionHandler(DuplicateKeyException::class)
    fun handleDuplicateKey(ex: DuplicateKeyException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError("CONFLICT", "The resource already exists", correlationId()))

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(ex: OptimisticLockingFailureException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError("CONFLICT", "The resource was changed by someone else. Reload and try again.", correlationId()))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiError> {
        val errorId = UUID.randomUUID().toString()
        // Full details stay server-side; the client only receives an opaque error id.
        log.error("Unexpected error [errorId={}]", errorId, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError("INTERNAL_ERROR", "Something went wrong on our side", correlationId(), errorId))
    }
}

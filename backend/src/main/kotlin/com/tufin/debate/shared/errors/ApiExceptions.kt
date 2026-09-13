package com.tufin.debate.shared.errors

open class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

class BadRequestException(message: String) : ApiException(400, "BAD_REQUEST", message)

class UnauthorizedException(message: String = "Authentication required") :
    ApiException(401, "UNAUTHORIZED", message)

class ForbiddenException(message: String = "You are not allowed to do that") :
    ApiException(403, "FORBIDDEN", message)

/**
 * Also used when a caller is not a member of a room: room-scoped resources answer 404 rather than
 * 403 to avoid leaking the existence of rooms the caller does not belong to (docs/security.md T1).
 */
class NotFoundException(message: String = "Resource not found") : ApiException(404, "NOT_FOUND", message)

class ConflictException(message: String) : ApiException(409, "CONFLICT", message)

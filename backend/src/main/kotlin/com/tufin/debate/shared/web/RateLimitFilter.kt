package com.tufin.debate.shared.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-window in-memory rate limiting (docs/security.md T13): a strict per-IP budget on the
 * authentication endpoints (brute-force guard) and a per-user budget on writes. Single-instance
 * MVP by design — a shared store (e.g. Redis) replaces the map when scaling out.
 * Runs after the security chain so the authenticated principal is available.
 */
@Component
@Order(10)
class RateLimitFilter(
    @param:Value("\${app.rate-limit.enabled:true}") private val enabled: Boolean,
    @param:Value("\${app.rate-limit.auth-per-minute:20}") private val authPerMinute: Int,
    @param:Value("\${app.rate-limit.writes-per-minute:120}") private val writesPerMinute: Int,
) : OncePerRequestFilter() {

    private class Window(val startEpochMinute: Long) {
        val count = AtomicInteger(0)
    }

    private val windows = ConcurrentHashMap<String, Window>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!enabled) return filterChain.doFilter(request, response)

        val path = request.requestURI
        val isAuth = path.startsWith("/api/v1/auth/")
        val isWrite = request.method in setOf("POST", "PUT", "PATCH", "DELETE") && path.startsWith("/api/")

        val (key, limit) = when {
            isAuth -> "auth:${clientIp(request)}" to authPerMinute
            isWrite -> "write:${principalOrIp(request)}" to writesPerMinute
            else -> return filterChain.doFilter(request, response)
        }

        val minute = Instant.now().epochSecond / 60
        val window = windows.compute(key) { _, existing ->
            if (existing == null || existing.startEpochMinute != minute) Window(minute) else existing
        }!!
        if (window.count.incrementAndGet() > limit) {
            response.status = 429
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"code":"RATE_LIMITED","message":"Too many requests. Wait a moment and try again."}""")
            return
        }

        // Lazy cleanup: drop stale windows once the map grows.
        if (windows.size > 10_000) {
            windows.entries.removeIf { it.value.startEpochMinute < minute }
        }

        filterChain.doFilter(request, response)
    }

    private fun principalOrIp(request: HttpServletRequest): String =
        SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it.principal !is String }
            ?.let { "u:${(it.principal as? com.tufin.debate.identity.application.AuthenticatedUser)?.userId ?: it.name}" }
            ?: "ip:${clientIp(request)}"

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
}

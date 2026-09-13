package com.tufin.debate.notifications

import com.tufin.debate.notifications.application.InvitationEmailComposer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InvitationEmailComposerTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val expiry = now.plus(Duration.ofDays(7))

    @Test
    fun `email contains the essentials`() {
        val message = InvitationEmailComposer.compose(
            inviterName = "Dana",
            roomTitle = "Rent discussion",
            role = "PARTY",
            acceptUrl = "http://localhost:4200/invite/tok123",
            expiresAt = expiry,
            now = now,
        )
        assertTrue(message.subject.contains("Rent discussion"))
        assertTrue(message.subject.contains("Dana"))
        assertTrue(message.html.contains("http://localhost:4200/invite/tok123"))
        assertTrue(message.text.contains("http://localhost:4200/invite/tok123"))
        assertTrue(message.html.contains("7"), "expiry days should appear")
        assertTrue(message.html.contains("משתתף/ת בדיון"), "Hebrew role label")
        assertTrue(message.html.contains("a participant in the discussion"), "English role label")
    }

    @Test
    fun `user-provided values are HTML-escaped`() {
        val message = InvitationEmailComposer.compose(
            inviterName = "<script>alert(1)</script>",
            roomTitle = "Tom & Jerry \"deal\"",
            role = "OBSERVER",
            acceptUrl = "http://localhost:4200/invite/tok123",
            expiresAt = expiry,
            now = now,
        )
        assertFalse(message.html.contains("<script>"))
        assertTrue(message.html.contains("&lt;script&gt;"))
        assertTrue(message.html.contains("Tom &amp; Jerry &quot;deal&quot;"))
    }

    @Test
    fun `expiry is never shown as zero days`() {
        val message = InvitationEmailComposer.compose(
            inviterName = "Dana",
            roomTitle = "T",
            role = "PARTY",
            acceptUrl = "http://x",
            expiresAt = now.plus(Duration.ofHours(2)),
            now = now,
        )
        assertTrue(message.text.contains("1 ימים") || message.html.contains("1"), "rounds up to at least one day")
    }

    @Test
    fun `unknown role falls back to the raw name`() {
        val message = InvitationEmailComposer.compose(
            inviterName = "Dana",
            roomTitle = "T",
            role = "SOMETHING_NEW",
            acceptUrl = "http://x",
            expiresAt = expiry,
            now = now,
        )
        assertEquals(true, message.html.contains("SOMETHING_NEW"))
    }
}

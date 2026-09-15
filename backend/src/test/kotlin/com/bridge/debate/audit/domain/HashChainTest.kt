package com.bridge.debate.audit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class HashChainTest {

    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun canonical(seq: Long, action: String = "ROOM_CREATED", meta: Map<String, String> = emptyMap()) =
        HashChain.canonical("room-1", seq, ActorType.USER, "user-1", action, "DiscussionRoom", "room-1", at, meta)

    @Test
    fun `hash is deterministic`() {
        assertEquals(
            HashChain.compute(null, canonical(1)),
            HashChain.compute(null, canonical(1)),
        )
    }

    @Test
    fun `hash changes when any field changes`() {
        val base = HashChain.compute(null, canonical(1))
        assertNotEquals(base, HashChain.compute(null, canonical(2)))
        assertNotEquals(base, HashChain.compute(null, canonical(1, action = "ROOM_UPDATED")))
        assertNotEquals(base, HashChain.compute("someprevhash", canonical(1)))
    }

    @Test
    fun `metadata order does not affect the hash`() {
        val a = canonical(1, meta = mapOf("x" to "1", "y" to "2"))
        val b = canonical(1, meta = mapOf("y" to "2", "x" to "1"))
        assertEquals(HashChain.compute(null, a), HashChain.compute(null, b))
    }
}

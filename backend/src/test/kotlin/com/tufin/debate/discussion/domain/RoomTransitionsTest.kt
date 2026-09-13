package com.tufin.debate.discussion.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomTransitionsTest {

    @Test
    fun `happy path through the full lifecycle is allowed`() {
        val path = listOf(
            RoomStatus.DRAFT, RoomStatus.INVITING, RoomStatus.INTAKE, RoomStatus.ACTIVE,
            RoomStatus.PROPOSAL_READY, RoomStatus.AGREEMENT_PENDING_APPROVAL, RoomStatus.AGREED,
            RoomStatus.CLOSED, RoomStatus.ARCHIVED,
        )
        path.zipWithNext().forEach { (from, to) ->
            assertTrue(RoomTransitions.isAllowed(from, to), "$from -> $to should be allowed")
        }
    }

    @Test
    fun `pause and resume work from active states`() {
        assertTrue(RoomTransitions.isAllowed(RoomStatus.ACTIVE, RoomStatus.PAUSED))
        assertTrue(RoomTransitions.isAllowed(RoomStatus.WAITING_FOR_USER, RoomStatus.PAUSED))
        assertTrue(RoomTransitions.isAllowed(RoomStatus.PAUSED, RoomStatus.ACTIVE))
    }

    @Test
    fun `closed rooms can be reopened or archived, archived rooms are final`() {
        assertTrue(RoomTransitions.isAllowed(RoomStatus.CLOSED, RoomStatus.ACTIVE))
        assertTrue(RoomTransitions.isAllowed(RoomStatus.CLOSED, RoomStatus.ARCHIVED))
        RoomStatus.entries.forEach { target ->
            assertFalse(RoomTransitions.isAllowed(RoomStatus.ARCHIVED, target), "ARCHIVED -> $target must be rejected")
        }
    }

    @Test
    fun `skipping the state machine is rejected`() {
        assertFalse(RoomTransitions.isAllowed(RoomStatus.DRAFT, RoomStatus.ACTIVE))
        assertFalse(RoomTransitions.isAllowed(RoomStatus.DRAFT, RoomStatus.AGREED))
        assertFalse(RoomTransitions.isAllowed(RoomStatus.ACTIVE, RoomStatus.AGREED))
        assertFalse(RoomTransitions.isAllowed(RoomStatus.AGREED, RoomStatus.ACTIVE))
        assertFalse(RoomTransitions.isAllowed(RoomStatus.INTAKE, RoomStatus.PROPOSAL_READY))
    }
}

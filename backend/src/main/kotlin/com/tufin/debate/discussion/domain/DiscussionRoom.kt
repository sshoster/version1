package com.tufin.debate.discussion.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class RoomStatus {
    DRAFT,
    INVITING,
    INTAKE,
    ACTIVE,
    WAITING_FOR_USER,
    PROPOSAL_READY,
    AGREEMENT_PENDING_APPROVAL,
    AGREED,
    PAUSED,
    CLOSED,
    ARCHIVED,
}

/** Explicit transition table (docs/architecture.md §4). Anything not listed is rejected. */
object RoomTransitions {
    private val allowed: Map<RoomStatus, Set<RoomStatus>> = mapOf(
        RoomStatus.DRAFT to setOf(RoomStatus.INVITING, RoomStatus.CLOSED),
        RoomStatus.INVITING to setOf(RoomStatus.INTAKE, RoomStatus.CLOSED),
        RoomStatus.INTAKE to setOf(RoomStatus.ACTIVE, RoomStatus.CLOSED),
        RoomStatus.ACTIVE to setOf(
            RoomStatus.WAITING_FOR_USER, RoomStatus.PROPOSAL_READY, RoomStatus.PAUSED, RoomStatus.CLOSED,
        ),
        RoomStatus.WAITING_FOR_USER to setOf(RoomStatus.ACTIVE, RoomStatus.PAUSED, RoomStatus.CLOSED),
        RoomStatus.PROPOSAL_READY to setOf(
            RoomStatus.AGREEMENT_PENDING_APPROVAL, RoomStatus.ACTIVE, RoomStatus.PAUSED, RoomStatus.CLOSED,
        ),
        RoomStatus.AGREEMENT_PENDING_APPROVAL to setOf(RoomStatus.AGREED, RoomStatus.ACTIVE, RoomStatus.CLOSED),
        RoomStatus.AGREED to setOf(RoomStatus.CLOSED),
        RoomStatus.PAUSED to setOf(RoomStatus.ACTIVE, RoomStatus.CLOSED),
        RoomStatus.CLOSED to setOf(RoomStatus.ACTIVE, RoomStatus.ARCHIVED),
        RoomStatus.ARCHIVED to emptySet(),
    )

    fun isAllowed(from: RoomStatus, to: RoomStatus): Boolean = allowed[from]?.contains(to) == true
}

@Document("discussion_rooms")
class DiscussionRoom(
    @Id val id: String,
    var title: String,
    var objective: String?,
    var status: RoomStatus,
    val ownerUserId: String,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
    var updatedAt: Instant,
    @Version var version: Long? = null,
)

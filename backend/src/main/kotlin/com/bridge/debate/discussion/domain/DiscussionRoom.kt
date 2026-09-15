package com.bridge.debate.discussion.domain

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
            RoomStatus.AGREED, // owner explicitly finishes the discussion in agreement
        ),
        RoomStatus.WAITING_FOR_USER to setOf(RoomStatus.ACTIVE, RoomStatus.PAUSED, RoomStatus.CLOSED, RoomStatus.AGREED),
        RoomStatus.PROPOSAL_READY to setOf(
            RoomStatus.AGREEMENT_PENDING_APPROVAL, RoomStatus.ACTIVE, RoomStatus.PAUSED, RoomStatus.CLOSED,
            RoomStatus.AGREED,
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
    /** The creator — the one admin whose admin role can never be removed. */
    val ownerUserId: String,
    /** Short join code; anyone with it may REQUEST to join, an admin approves (nullable only for pre-migration docs). */
    var joinCode: String? = null,
    val schemaVersion: Int = 1,
    val createdAt: Instant,
    var updatedAt: Instant,
    @Version var version: Long? = null,
)

object JoinCodes {
    /** Unambiguous alphabet (no 0/O/1/I/L). */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private val random = java.security.SecureRandom()

    fun generate(): String = (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun normalize(code: String): String = code.trim().uppercase().replace("-", "").replace(" ", "")
}

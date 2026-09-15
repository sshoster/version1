package com.bridge.debate.messaging.application

import com.bridge.debate.messaging.domain.SharedItemStatus
import com.bridge.debate.messaging.infrastructure.SharedItemRepository
import com.bridge.debate.messaging.infrastructure.SharedItemVersionRepository
import org.springframework.stereotype.Service

/** One shared statement a given user is authorized to see, for use in an agent context. */
data class SharedFact(
    val versionId: String,
    val itemId: String,
    val authorDisplayName: String,
    val text: String,
)

/**
 * Read-side used by the negotiation orchestrator: exactly the shared content a specific user may
 * see (trust-model invariant 10 — an assistant's read set equals its user's read set), excluding
 * withdrawn items.
 */
@Service
class SharedContextReader(
    private val versions: SharedItemVersionRepository,
    private val items: SharedItemRepository,
) {
    /** Facts every one of the given users can see — the safe basis for a shared artifact. */
    fun factsVisibleToAll(roomId: String, userIds: Collection<String>): List<SharedFact> {
        val first = userIds.firstOrNull() ?: return emptyList()
        val rest = userIds.drop(1).toSet()
        return visibleFacts(roomId, first).filter { fact ->
            rest.all { userId ->
                versions.findByRoomIdAndAudienceUserIdsOrderByCreatedAtAsc(roomId, userId)
                    .any { it.id == fact.versionId }
            }
        }
    }

    fun visibleFacts(roomId: String, userId: String): List<SharedFact> {
        val visible = versions.findByRoomIdAndAudienceUserIdsOrderByCreatedAtAsc(roomId, userId)
        if (visible.isEmpty()) return emptyList()
        val itemsById = items.findAllById(visible.map { it.sharedItemId }.distinct()).associateBy { it.id }
        return visible
            .groupBy { it.sharedItemId }
            .mapNotNull { (itemId, itemVersions) ->
                val item = itemsById[itemId] ?: return@mapNotNull null
                if (item.status == SharedItemStatus.WITHDRAWN) return@mapNotNull null
                val latest = itemVersions.maxBy { it.version }
                SharedFact(latest.id, itemId, latest.authorDisplayName, latest.text)
            }
    }
}

package com.tufin.debate.messaging.application

import com.tufin.debate.messaging.domain.SharedItemStatus
import com.tufin.debate.messaging.infrastructure.SharedItemRepository
import com.tufin.debate.messaging.infrastructure.SharedItemVersionRepository
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

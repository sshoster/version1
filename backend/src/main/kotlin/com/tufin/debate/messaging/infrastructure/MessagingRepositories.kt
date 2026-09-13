package com.tufin.debate.messaging.infrastructure

import com.tufin.debate.messaging.domain.AudienceSnapshot
import com.tufin.debate.messaging.domain.PrivateMessage
import com.tufin.debate.messaging.domain.SharePreview
import com.tufin.debate.messaging.domain.SharedItem
import com.tufin.debate.messaging.domain.SharedItemVersion
import org.springframework.data.mongodb.repository.MongoRepository

interface PrivateMessageRepository : MongoRepository<PrivateMessage, String> {
    /** Always keyed by room AND author — there is no query path across users' private spaces. */
    fun findByRoomIdAndAuthorUserIdOrderByCreatedAtAsc(roomId: String, authorUserId: String): List<PrivateMessage>
    fun findByIdAndRoomIdAndAuthorUserId(id: String, roomId: String, authorUserId: String): PrivateMessage?
}

interface SharePreviewRepository : MongoRepository<SharePreview, String> {
    fun findByIdAndRoomIdAndAuthorUserId(id: String, roomId: String, authorUserId: String): SharePreview?
}

interface SharedItemRepository : MongoRepository<SharedItem, String> {
    fun findByIdAndRoomId(id: String, roomId: String): SharedItem?
}

interface SharedItemVersionRepository : MongoRepository<SharedItemVersion, String> {
    fun findByRoomIdAndAudienceUserIdsOrderByCreatedAtAsc(roomId: String, userId: String): List<SharedItemVersion>
    fun findBySharedItemIdAndRoomIdOrderByVersionAsc(sharedItemId: String, roomId: String): List<SharedItemVersion>
    fun findBySharedItemIdAndRoomIdAndVersion(sharedItemId: String, roomId: String, version: Int): SharedItemVersion?
}

interface AudienceSnapshotRepository : MongoRepository<AudienceSnapshot, String>

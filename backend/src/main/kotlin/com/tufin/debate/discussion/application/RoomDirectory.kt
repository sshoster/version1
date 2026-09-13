package com.tufin.debate.discussion.application

import com.tufin.debate.discussion.domain.DiscussionRoom
import com.tufin.debate.discussion.infrastructure.DiscussionRoomRepository
import org.springframework.stereotype.Service

/** Read-side room lookup for other modules (keeps cross-module access out of infrastructure). */
@Service
class RoomDirectory(private val rooms: DiscussionRoomRepository) {
    fun find(roomId: String): DiscussionRoom? = rooms.findById(roomId).orElse(null)
    fun findByJoinCode(joinCode: String): DiscussionRoom? = rooms.findByJoinCode(joinCode)
}

package com.tufin.debate.discussion.infrastructure

import com.tufin.debate.discussion.domain.DiscussionRoom
import org.springframework.data.mongodb.repository.MongoRepository

interface DiscussionRoomRepository : MongoRepository<DiscussionRoom, String> {
    fun findByIdIn(ids: Collection<String>): List<DiscussionRoom>
    fun findByJoinCode(joinCode: String): DiscussionRoom?
}

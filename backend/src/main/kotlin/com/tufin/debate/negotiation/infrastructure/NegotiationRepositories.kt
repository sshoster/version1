package com.tufin.debate.negotiation.infrastructure

import com.tufin.debate.negotiation.domain.AiAgentProfile
import com.tufin.debate.negotiation.domain.NegotiationRun
import com.tufin.debate.negotiation.domain.NegotiationTurn
import com.tufin.debate.negotiation.domain.Question
import com.tufin.debate.negotiation.domain.QuestionStatus
import org.springframework.data.mongodb.repository.MongoRepository

interface AiAgentProfileRepository : MongoRepository<AiAgentProfile, String> {
    fun findByRoomIdAndUserId(roomId: String, userId: String): AiAgentProfile?
}

interface NegotiationRunRepository : MongoRepository<NegotiationRun, String> {
    fun findByIdAndRoomId(id: String, roomId: String): NegotiationRun?
    fun findByRoomIdOrderByCreatedAtDesc(roomId: String): List<NegotiationRun>
}

interface NegotiationTurnRepository : MongoRepository<NegotiationTurn, String> {
    fun findByRunIdAndRoomIdOrderByTurnNumberAsc(runId: String, roomId: String): List<NegotiationTurn>
}

interface QuestionRepository : MongoRepository<Question, String> {
    fun findByIdAndRoomId(id: String, roomId: String): Question?
    fun findByRoomIdAndToUserIdOrderByCreatedAtDesc(roomId: String, toUserId: String): List<Question>
    fun countByRunIdAndStatus(runId: String, status: QuestionStatus): Long
    fun findByRunIdAndToUserIdAndStatus(runId: String, toUserId: String, status: QuestionStatus): List<Question>
    fun findByRunIdAndStatusOrderByCreatedAtAsc(runId: String, status: QuestionStatus): List<Question>
}

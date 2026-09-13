package com.tufin.debate.agreements.infrastructure

import com.tufin.debate.agreements.domain.Approval
import com.tufin.debate.agreements.domain.ApprovalRequest
import com.tufin.debate.agreements.domain.ApprovalRequestStatus
import com.tufin.debate.agreements.domain.Proposal
import com.tufin.debate.agreements.domain.ProposalVersion
import org.springframework.data.mongodb.repository.MongoRepository

interface ProposalRepository : MongoRepository<Proposal, String> {
    fun findByIdAndRoomId(id: String, roomId: String): Proposal?
    fun findByRoomIdOrderByCreatedAtDesc(roomId: String): List<Proposal>
}

interface ProposalVersionRepository : MongoRepository<ProposalVersion, String> {
    fun findByProposalIdAndRoomIdOrderByVersionAsc(proposalId: String, roomId: String): List<ProposalVersion>
    fun findByProposalIdAndRoomIdAndVersion(proposalId: String, roomId: String, version: Int): ProposalVersion?
}

interface ApprovalRequestRepository : MongoRepository<ApprovalRequest, String> {
    fun findByIdAndRoomId(id: String, roomId: String): ApprovalRequest?
    fun findByProposalIdAndStatus(proposalId: String, status: ApprovalRequestStatus): List<ApprovalRequest>
    fun findByRoomIdAndStatus(roomId: String, status: ApprovalRequestStatus): List<ApprovalRequest>
}

interface ApprovalRepository : MongoRepository<Approval, String> {
    fun findByApprovalRequestId(approvalRequestId: String): List<Approval>
    fun findByApprovalRequestIdAndUserId(approvalRequestId: String, userId: String): Approval?
}

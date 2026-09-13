package com.tufin.debate.identity.infrastructure

import com.tufin.debate.identity.domain.RefreshToken
import com.tufin.debate.identity.domain.User
import org.springframework.data.mongodb.repository.MongoRepository

interface UserRepository : MongoRepository<User, String> {
    fun findByEmail(email: String): User?
}

interface RefreshTokenRepository : MongoRepository<RefreshToken, String> {
    fun findByTokenHash(tokenHash: String): RefreshToken?
    fun findByFamilyId(familyId: String): List<RefreshToken>
}

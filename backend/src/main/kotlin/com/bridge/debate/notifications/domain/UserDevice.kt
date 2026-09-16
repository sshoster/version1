package com.bridge.debate.notifications.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

/**
 * A device that can receive push notifications. The FCM token is unique — re-registering an
 * existing token just re-binds it to the signing-in user (shared devices switch owners cleanly).
 */
@Document("user_devices")
class UserDevice(
    @Id val id: String,
    var userId: String,
    val token: String,
    val platform: String,
    var updatedAt: Instant,
)

interface UserDeviceRepository : MongoRepository<UserDevice, String> {
    fun findByUserId(userId: String): List<UserDevice>
    fun findByToken(token: String): UserDevice?
    fun deleteByToken(token: String)
}

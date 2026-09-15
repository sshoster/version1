package com.bridge.debate.identity.application

import com.bridge.debate.identity.infrastructure.UserRepository
import org.springframework.stereotype.Service

/**
 * Read-side lookup for other modules (e.g. emailing room documents) so nobody reaches into this
 * module's infrastructure. Emails are handed only to server-side senders — never to API views.
 */
@Service
class UserDirectory(private val users: UserRepository) {

    fun emailsByIds(userIds: Collection<String>): Map<String, String> =
        users.findAllById(userIds).associate { it.id to it.email }
}

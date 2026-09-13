package com.tufin.debate.notifications.infrastructure

import com.tufin.debate.identity.application.JwtService
import com.tufin.debate.shared.config.CorsProperties
import com.tufin.debate.permissions.application.PermissionsService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import java.security.Principal

class StompPrincipal(private val userId: String) : Principal {
    override fun getName(): String = userId
}

/**
 * Real-time events over STOMP. Authentication happens on the CONNECT frame (Bearer token header);
 * every SUBSCRIBE is authorized server-side (design doc §10, security.md T9):
 *  - `/topic/rooms/{roomId}` — room-wide events; requires active room membership.
 *  - `/user/queue/room-events` — per-user scoped events (audience-filtered on the publish side).
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val authInterceptor: WsAuthChannelInterceptor,
    private val corsProperties: CorsProperties,
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws").setAllowedOrigins(*corsProperties.allowedOrigins.toTypedArray())
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setUserDestinationPrefix("/user")
        registry.setApplicationDestinationPrefixes("/app")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(authInterceptor)
    }
}

@Component
class WsAuthChannelInterceptor(
    private val jwtService: JwtService,
    private val permissions: PermissionsService,
) : ChannelInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val ROOM_TOPIC = Regex("^/topic/rooms/([^/]+)$")
        private const val USER_QUEUE = "/user/queue/room-events"
    }

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: return message

        when (accessor.command) {
            StompCommand.CONNECT -> {
                val header = accessor.getFirstNativeHeader("Authorization") ?: ""
                val principal = header.takeIf { it.startsWith("Bearer ") }
                    ?.let { jwtService.parse(it.removePrefix("Bearer ").trim()) }
                    ?: throw org.springframework.messaging.MessagingException("Authentication required")
                accessor.user = StompPrincipal(principal.userId)
            }

            StompCommand.SUBSCRIBE -> {
                val userId = accessor.user?.name
                    ?: throw org.springframework.messaging.MessagingException("Authentication required")
                val destination = accessor.destination ?: ""
                val roomMatch = ROOM_TOPIC.find(destination)
                when {
                    roomMatch != null -> {
                        // NotFoundException if not a member — subscription is rejected.
                        permissions.requireParticipant(roomMatch.groupValues[1], userId)
                    }
                    destination == USER_QUEUE -> Unit // own queue only; broker resolves per session
                    else -> {
                        log.warn("Rejected subscription to unexpected destination {}", destination)
                        throw org.springframework.messaging.MessagingException("Unknown destination")
                    }
                }
            }

            else -> Unit
        }
        return message
    }
}

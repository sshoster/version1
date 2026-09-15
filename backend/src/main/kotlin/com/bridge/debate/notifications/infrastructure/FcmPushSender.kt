package com.bridge.debate.notifications.infrastructure

import com.bridge.debate.notifications.application.PushSender
import com.bridge.debate.notifications.domain.UserDeviceRepository
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * FCM delivery via the Firebase Admin SDK. Configured with the service-account JSON in
 * FCM_SERVICE_ACCOUNT_JSON (Render env / local .env); when absent the sender is a no-op,
 * so deployments without push simply skip it. Log lines never include tokens or content.
 */
@Component
class FcmPushSender(
    private val devices: UserDeviceRepository,
    @param:Value("\${app.fcm-service-account-json:}") private val serviceAccountJson: String,
) : PushSender {

    private val log = LoggerFactory.getLogger(javaClass)

    private val messaging: FirebaseMessaging? by lazy {
        if (serviceAccountJson.isBlank()) {
            log.info("FCM not configured - push notifications disabled")
            return@lazy null
        }
        try {
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccountJson.byteInputStream()))
                .build()
            val app = FirebaseApp.getApps().firstOrNull { it.name == APP_NAME }
                ?: FirebaseApp.initializeApp(options, APP_NAME)
            FirebaseMessaging.getInstance(app)
        } catch (e: Exception) {
            log.error("FCM initialization failed: {}", e.javaClass.simpleName)
            null
        }
    }

    override fun sendToUser(userId: String, title: String, body: String, data: Map<String, String>) {
        val fcm = messaging ?: return
        devices.findByUserId(userId).forEach { device ->
            try {
                fcm.send(
                    Message.builder()
                        .setToken(device.token)
                        .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                        .putAllData(data)
                        .build(),
                )
            } catch (e: FirebaseMessagingException) {
                // A token goes stale when the app is uninstalled or its data cleared — drop it.
                if (e.messagingErrorCode in STALE_TOKEN_CODES) {
                    devices.deleteByToken(device.token)
                } else {
                    log.warn("FCM send failed: {}", e.messagingErrorCode)
                }
            }
        }
    }

    companion object {
        private const val APP_NAME = "bridge-ai"
        private val STALE_TOKEN_CODES = setOf(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT)
    }
}

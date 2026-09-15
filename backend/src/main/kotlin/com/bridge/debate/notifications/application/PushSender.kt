package com.bridge.debate.notifications.application

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Port for mobile push delivery. Implementations send to every registered device of the user;
 * a no-op implementation backs deployments without push configured.
 */
interface PushSender {
    fun sendToUser(userId: String, title: String, body: String, data: Map<String, String>)
}

/**
 * Composes and sends the push for an in-app notification, off the outbox worker thread.
 * Bodies are generic by design — notifications never carry content (design doc §13), so the
 * push can't leak anything either.
 */
@Component
class PushDispatcher(private val pushSender: PushSender) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Async("notificationExecutor")
    fun dispatch(userId: String, roomId: String, type: String) {
        try {
            pushSender.sendToUser(
                userId = userId,
                title = "Bridge AI",
                body = BODIES[type] ?: DEFAULT_BODY,
                data = mapOf("roomId" to roomId, "type" to type),
            )
        } catch (e: Exception) {
            log.warn("Push dispatch failed: {}", e.javaClass.simpleName)
        }
    }

    companion object {
        private const val DEFAULT_BODY = "עדכון חדש בדיון"

        /** Content-free, per-event-type wording (Hebrew — the product's default locale). */
        private val BODIES = mapOf(
            "SHARED_ITEM_PUBLISHED" to "הודעה חדשה בדיון",
            "QUESTION_CREATED" to "העוזר האישי ממתין לתשובה שלך",
            "APPROVAL_REQUESTED" to "הצעה ממתינה לאישור שלך",
            "APPROVAL_RECORDED" to "אחד הצדדים הגיב להצעה",
            "PROPOSAL_CREATED" to "הצעת הסכמה חדשה בדיון",
            "PROPOSAL_REVISED" to "הצעת ההסכמה עודכנה",
            "PARTICIPANT_JOINED" to "משתתף חדש הצטרף לדיון",
            "FILE_SHARED" to "קובץ חדש שותף בדיון",
            "OUTCOME_CREATED" to "מסמך חדש מוכן בדיון",
            "JOIN_REQUESTED" to "בקשת הצטרפות חדשה לדיון",
            "JOIN_REQUEST_DECIDED" to "התקבלה החלטה על בקשת ההצטרפות שלך",
            "NEGOTIATION_WAITING_FOR_USER" to "העוזרים ממתינים לך בדיון",
        )
    }
}

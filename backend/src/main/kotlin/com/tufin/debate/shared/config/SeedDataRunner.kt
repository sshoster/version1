package com.tufin.debate.shared.config

import com.tufin.debate.discussion.application.RoomService
import com.tufin.debate.identity.application.AuthService
import com.tufin.debate.identity.application.AuthenticatedUser
import com.tufin.debate.messaging.application.PrivateMessageService
import com.tufin.debate.messaging.application.SharingService
import com.tufin.debate.messaging.domain.ContentOrigin
import com.tufin.debate.messaging.domain.VisibilityScope
import com.tufin.debate.negotiation.application.AgentProfileService
import com.tufin.debate.participants.application.InvitationService
import com.tufin.debate.participants.domain.ParticipantRole
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Local demo data (design doc §15): two parties, an advisor, an observer, and a ready discussion.
 * Runs only in the local profile with SEED_DEMO=true, and only once (skips if the users exist).
 * Demo logins: dana@demo.test / avi@demo.test / yael@demo.test / omer@demo.test — password for
 * all: demo-password-123
 */
@Component
@Profile("local")
class SeedDataRunner(
    private val authService: AuthService,
    private val roomService: RoomService,
    private val invitationService: InvitationService,
    private val privateMessages: PrivateMessageService,
    private val sharing: SharingService,
    private val profiles: AgentProfileService,
    @param:Value("\${app.seed.enabled:false}") private val enabled: Boolean,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val PASSWORD = "demo-password-123"
    }

    override fun run(args: ApplicationArguments) {
        if (!enabled) return
        try {
            seed()
        } catch (e: Exception) {
            log.warn("Demo seed skipped: {}", e.message)
        }
    }

    private fun seed() {
        val dana = register("dana@demo.test", "דנה לוי") ?: run {
            log.info("Demo data already present — seed skipped")
            return
        }
        val avi = register("avi@demo.test", "אבי כהן")!!
        val yael = register("yael@demo.test", "יעל עו\"ד")!!
        val omer = register("omer@demo.test", "עומר צופה")!!

        val room = roomService.create(dana, "חלוקת הוצאות הדירה", "להסכים על חלוקת שכר הדירה והחשבונות")
        val roomId = room.room.id

        accept(roomId, dana, ParticipantRole.PARTY, avi, "אבי", "כהן")
        accept(roomId, dana, ParticipantRole.ADVISOR, yael, "יעל", null)
        accept(roomId, dana, ParticipantRole.OBSERVER, omer, "עומר", null)

        profiles.put(roomId, dana, "חלוקה הוגנת לפי הכנסות", "לא יותר מ-60% מההוצאות", "גמישות בתאריכי תשלום")
        profiles.put(roomId, avi, "ודאות לאורך זמן", "לא פחות מחצי מהחשבונות על דנה", "גמיש בגובה שכר הדירה")

        privateMessages.write(roomId, dana, "אני מרגישה שאני משלמת יותר מדי ביחס להכנסות שלנו")
        privateMessages.write(roomId, avi, "חשוב לי שנקבע חלוקה קבועה ולא נריב כל חודש")

        publish(roomId, dana, "שכר הדירה שלנו הוא 4500 ש\"ח בחודש וחשבונות ממוצעים של 800 ש\"ח")
        publish(roomId, avi, "אני מציע שנקבע חלוקה קבועה שנסכם עליה כאן ונעדכן פעם בשנה")

        log.info("Demo discussion seeded: room {} (logins: dana@demo.test / avi@demo.test, password {})", roomId, PASSWORD)
    }

    private fun register(email: String, name: String): AuthenticatedUser? =
        try {
            authService.register(email, name, PASSWORD).user
        } catch (e: com.tufin.debate.shared.errors.ConflictException) {
            null
        }

    private fun accept(
        roomId: String,
        owner: AuthenticatedUser,
        role: ParticipantRole,
        joiner: AuthenticatedUser,
        firstName: String?,
        lastName: String?,
    ) {
        val invitation = invitationService.create(roomId, owner, role, null, firstName, lastName, null)
        invitationService.accept(invitation.token, joiner)
    }

    private fun publish(roomId: String, author: AuthenticatedUser, text: String) {
        val preview = sharing.preview(roomId, author, text, VisibilityScope.ALL_PARTIES, null, ContentOrigin.USER_AUTHORED, null)
        sharing.publish(roomId, author, preview.previewId, text, VisibilityScope.ALL_PARTIES, null, ContentOrigin.USER_AUTHORED, null, null, null)
    }
}

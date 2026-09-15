package com.bridge.debate.notifications.application

import java.time.Duration
import java.time.Instant

data class EmailMessage(val subject: String, val html: String, val text: String)

/**
 * Composes the invitation email: Hebrew first (the product default), English below.
 * All user-provided values (names, titles) are HTML-escaped — an invitation must never become an
 * HTML-injection vector (docs/security.md T10).
 */
object InvitationEmailComposer {

    private val ROLE_LABELS = mapOf(
        "PARTY" to ("משתתף/ת בדיון" to "a participant in the discussion"),
        "ADVISOR" to ("יועץ/ת" to "an advisor"),
        "OBSERVER" to ("צופה בלבד" to "a viewer (read-only)"),
        "FACILITATOR" to ("מגשר/ת" to "a facilitator"),
    )

    fun compose(
        inviterName: String,
        roomTitle: String,
        role: String,
        acceptUrl: String,
        expiresAt: Instant,
        now: Instant = Instant.now(),
        recipientName: String? = null,
    ): EmailMessage {
        val inviter = escape(inviterName.ifBlank { "משתתף/ת" })
        val greetingHe = recipientName?.takeIf { it.isNotBlank() }?.let { "היי ${escape(it)}," } ?: "היי,"
        val greetingEn = recipientName?.takeIf { it.isNotBlank() }?.let { "Hi ${escape(it)}," } ?: "Hi,"
        val title = escape(roomTitle)
        val (roleHe, roleEn) = ROLE_LABELS[role] ?: (role to role)
        val days = Duration.between(now, expiresAt).toDays().coerceAtLeast(1)

        val subject = "$inviterName הזמין/ה אותך לדיון: $roomTitle"

        val html = """
            <div style="background:#f7f6f3;padding:24px 8px;font-family:'Segoe UI',Arial,sans-serif;color:#1f2933">
              <div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #d9dee3;border-radius:12px;padding:32px" dir="rtl">
                <h1 style="font-size:20px;margin:0 0 4px;color:#1f6f5c">Bridge AI</h1>
                <p style="margin:0 0 24px;color:#5f6b76;font-size:13px">מקום בטוח לשוחח, להבין ולהגיע להסכמה</p>

                <h2 style="font-size:17px;margin:0 0 12px">הוזמנת לדיון 💬</h2>
                <p style="margin:0 0 8px">$greetingHe</p>
                <p style="margin:0 0 8px"><strong>$inviter</strong> הזמין/ה אותך להצטרף לדיון:</p>
                <p style="margin:0 0 16px;font-size:16px"><strong>„$title"</strong></p>
                <p style="margin:0 0 16px">התפקיד שלך בדיון: <strong>$roleHe</strong>.</p>
                <p style="margin:0 0 24px;color:#5f6b76">
                  בדיון, כל מה שתכתבו לעוזר האישי שלכם נשאר פרטי — שום דבר לא משותף בלי אישור מפורש שלכם,
                  וכל צעד מתועד בצורה שקופה.
                </p>
                <p style="text-align:center;margin:0 0 24px">
                  <a href="$acceptUrl"
                     style="display:inline-block;background:#1f6f5c;color:#ffffff;text-decoration:none;padding:14px 32px;border-radius:12px;font-size:16px">
                    הצטרפות לדיון
                  </a>
                </p>
                <p style="margin:0 0 4px;color:#5f6b76;font-size:13px">
                  הקישור אישי וחד-פעמי, ותקף ל־$days ימים. אם הכפתור לא עובד, אפשר להעתיק את הקישור:
                </p>
                <p style="margin:0 0 24px;font-size:12px;direction:ltr;text-align:left;overflow-wrap:anywhere">
                  <a href="$acceptUrl" style="color:#1f6f5c">$acceptUrl</a>
                </p>
                <p style="margin:0;color:#5f6b76;font-size:12px">
                  לא ציפית להזמנה הזו? אפשר פשוט להתעלם מהמייל — בלי הקישור אף אחד לא יכול להצטרף בשמך.
                </p>

                <hr style="border:none;border-top:1px solid #d9dee3;margin:24px 0" />

                <div dir="ltr" style="color:#5f6b76;font-size:13px">
                  <p style="margin:0 0 8px">$greetingEn</p>
                  <p style="margin:0 0 8px"><strong>$inviter</strong> invited you to join the discussion
                    <strong>"$title"</strong> as <strong>$roleEn</strong> on Bridge AI —
                    a safe place to talk, understand each other, and reach agreement.</p>
                  <p style="margin:0 0 8px">Anything you write to your personal assistant stays private; nothing is
                    shared without your explicit approval, and every step is transparently recorded.</p>
                  <p style="margin:0">The link above is personal, single-use, and valid for $days days.
                    Not expecting this? Just ignore this email.</p>
                </div>
              </div>
            </div>
        """.trimIndent()

        val text = """
            Bridge AI — הוזמנת לדיון

            ${recipientName?.let { "היי $it," } ?: "היי,"}
            $inviterName הזמין/ה אותך להצטרף לדיון: "$roomTitle"
            התפקיד שלך: $roleHe.

            כל מה שתכתבו לעוזר האישי שלכם נשאר פרטי — שום דבר לא משותף בלי אישור מפורש שלכם.

            להצטרפות (קישור אישי וחד-פעמי, תקף ל־$days ימים):
            $acceptUrl

            לא ציפית להזמנה הזו? אפשר להתעלם מהמייל.

            ---

            Bridge AI — you are invited to a discussion

            $inviterName invited you to join "$roomTitle" as $roleEn.
            Whatever you write to your personal assistant stays private; nothing is shared without
            your explicit approval. The link is personal, single-use, and valid for $days days:
            $acceptUrl

            Not expecting this? Just ignore this email.
        """.trimIndent()

        return EmailMessage(subject, html, text)
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

import { Injectable, signal } from '@angular/core';

export type Lang = 'he' | 'en';

/**
 * Lightweight dictionary-based i18n. Hebrew is the default language and the document is RTL;
 * English is prepared with the same key set (design doc §11: plain language, no internal jargon).
 */
const DICT: Record<Lang, Record<string, string>> = {
  he: {
    'app.title': 'מכנה משותף',
    'app.tagline': 'מקום בטוח לשוחח, להבין ולהגיע להסכמה',
    'app.signOut': 'התנתקות',

    'auth.welcome': 'ברוכים הבאים',
    'auth.intro': 'כאן אפשר לנהל שיחה מוגנת ושקופה ולהגיע יחד להסכמה, בעזרת עוזר אישי חכם.',
    'auth.email': 'כתובת אימייל',
    'auth.displayName': 'איך קוראים לך?',
    'auth.password': 'סיסמה',
    'auth.passwordHint': 'לפחות 10 תווים',
    'auth.signInAction': 'כניסה',
    'auth.registerAction': 'יצירת חשבון',
    'auth.switchToRegister': 'עדיין אין לך חשבון? יצירת חשבון חדש',
    'auth.switchToLogin': 'כבר יש לך חשבון? כניסה',
    'auth.genericError': 'משהו לא הסתדר. אפשר לנסות שוב.',

    'home.title': 'הדיונים שלי',
    'home.empty.title': 'עדיין אין כאן דיונים',
    'home.empty.hint': 'כשרוצים לפתור משהו יחד עם מישהו — מתחילים כאן.',
    'home.start': 'התחלת דיון חדש',
    'home.updated': 'עודכן',

    'status.DRAFT': 'עוד לא הזמנת אף אחד לדיון',
    'status.INVITING': 'ממתינים שהצד השני יצטרף',
    'status.INTAKE': 'אפשר להתחיל — ספרו לעוזר האישי מה חשוב לכם',
    'status.ACTIVE': 'הדיון פעיל',
    'status.WAITING_FOR_USER': 'ממתינים לתשובה שלך',
    'status.PROPOSAL_READY': 'יש הצעה שמחכה לעיון',
    'status.AGREEMENT_PENDING_APPROVAL': 'ממתינים לאישור של כל הצדדים',
    'status.AGREED': 'הגעתם להסכמה 🎉',
    'status.PAUSED': 'הדיון מושהה',
    'status.CLOSED': 'הדיון נסגר',
    'status.ARCHIVED': 'הדיון הועבר לארכיון',

    'create.title': 'התחלת דיון',
    'create.stepOf': 'שלב {0} מתוך {1}',
    'create.q1': 'מה תרצו לפתור או לסכם?',
    'create.q1.hint': 'למשל: "חלוקת הוצאות הדירה" או "תנאי סיום ההתקשרות"',
    'create.q2': 'יש משהו חשוב שכדאי שהצד השני יידע כבר עכשיו?',
    'create.q2.hint': 'אפשר לדלג — תמיד אפשר להוסיף אחר כך',
    'create.q3': 'את מי להזמין לדיון?',
    'create.q3.hint': 'אפשר להזין אימייל, או לדלג ולשתף קישור הזמנה אחר כך',
    'create.next': 'המשך',
    'create.back': 'חזרה',
    'create.skip': 'דילוג בינתיים',
    'create.finish': 'יצירת הדיון',

    'room.participants': 'מי משתתף',
    'room.invite': 'הזמנת אדם נוסף',
    'room.invite.explain': 'ההזמנה נשלחת כקישור חד-פעמי. רק מי שמקבל את הקישור יכול להצטרף.',
    'room.invite.role': 'באיזה תפקיד?',
    'room.role.PARTY': 'משתתף/ה בדיון',
    'room.role.ADVISOR': 'יועץ/ת',
    'room.role.OBSERVER': 'צופה בלבד',
    'room.role.OWNER': 'יוצר/ת הדיון',
    'room.role.FACILITATOR': 'מגשר/ת',
    'room.invite.email': 'כתובת אימייל (לא חובה)',
    'room.invite.emailHint': 'אם מזינים אימייל — ההזמנה נשלחת גם ישירות למייל',
    'room.invite.emailSentTo': 'ההזמנה נשלחה במייל אל {0} ✓',
    'room.invite.emailNotSent': 'המייל לא נשלח — אפשר לשתף את הקישור ידנית',
    'room.invite.create': 'יצירת קישור הזמנה',
    'room.invite.copy': 'העתקת הקישור',
    'room.invite.copied': 'הקישור הועתק ✓',
    'room.invite.shareHint': 'שלחו את הקישור לאדם שרוצים להזמין. הקישור תקף לשבעה ימים.',
    'room.whatNext': 'מה הצעד הבא?',
    'room.next.DRAFT': 'הזמינו את האדם שאיתו רוצים לדבר.',
    'room.next.INVITING': 'שלחו את קישור ההזמנה. ברגע שהצד השני יצטרף — נמשיך.',
    'room.next.INTAKE': 'בקרוב: שיחה פרטית עם העוזר האישי שלכם.',
    'room.objective': 'על מה הדיון',
    'room.joined': 'הצטרפת לדיון 🎉',

    'invite.title': 'הוזמנת לדיון',
    'invite.roomLabel': 'נושא הדיון',
    'invite.invitedBy': 'הוזמנת על ידי',
    'invite.asRole': 'בתפקיד',
    'invite.accept': 'הצטרפות לדיון',
    'invite.needAccount': 'כדי להצטרף צריך קודם להיכנס או ליצור חשבון.',
    'invite.expired': 'ההזמנה הזו כבר אינה בתוקף. בקשו קישור חדש ממי שהזמין אתכם.',
    'invite.alreadyMember': 'את/ה כבר חלק מהדיון הזה 🙂 הקישור מיועד לאדם אחר — פשוט שלחו לו אותו. הדיון עצמו מחכה ברשימת הדיונים שלך.',
    'invite.used': 'ההזמנה הזו כבר נוצלה או בוטלה. בקשו קישור חדש ממי שהזמין אתכם.',

    'common.loading': 'רק רגע…',
    'common.retry': 'ניסיון נוסף',
    'common.cancel': 'ביטול',
    'common.privateBadge': 'פרטי — רק אתם והעוזר שלכם',

    'tab.shared': 'השיחה המשותפת',
    'tab.assistant': 'העוזר שלי',
    'trust.shared': 'מה שכאן גלוי למשתתפים לפי בחירתכם בכל שיתוף',
    'trust.private': 'פרטי — רק אתם והעוזר שלכם רואים את מה שכאן',
    'more.title': 'עוד — משתתפים והזמנות',

    'assistant.empty': 'כאן אפשר לחשוב בקול רם. מה שנכתב כאן נשאר ביניכם ובין העוזר בלבד.',
    'assistant.placeholder': 'מה חשוב לך? מה מטריד אותך?',
    'assistant.send': 'שליחה לעוזר',
    'assistant.suggest': 'בקשת ניסוח מומלץ',
    'assistant.share': 'שיתוף הנוסח הזה',
    'assistant.shareOwn': 'שיתוף ההודעה הזו',
    'assistant.draftLabel': 'הצעת ניסוח מהעוזר',
    'assistant.you': 'את/ה',

    'shared.empty': 'עוד לא שותף כאן דבר. ההודעה הראשונה יכולה להיות שלך.',
    'shared.waitingForOther': 'אפשר יהיה לשתף הודעות אחרי שהצד השני יצטרף לדיון.',
    'shared.notShareable': 'אי אפשר לשתף הודעות במצב הנוכחי של הדיון.',
    'shared.composerPlaceholder': 'כתבו הודעה לשיתוף…',
    'shared.writeMyself': 'שיתוף כמו שכתבתי',
    'shared.helpPhrase': 'עזרו לי לנסח',
    'shared.withdraw': 'משיכת ההודעה',
    'shared.withdrawn': 'ההודעה נמשכה על ידי מי שכתב אותה',
    'shared.updated': 'עודכן — גרסה {0}',

    'origin.USER_AUTHORED': 'נכתב על ידי {0}',
    'origin.AI_DRAFT_ACCEPTED': 'נוסח בעזרת AI — אושר על ידי {0}',
    'origin.AI_DRAFT_USER_EDITED': 'נוסח בעזרת AI ונערך — אושר על ידי {0}',
    'origin.ADVISOR_AUTHORED': 'נכתב על ידי היועץ/ת {0}',
    'origin.SYSTEM_GENERATED': 'נוצר על ידי המערכת',

    'scope.MY_ADVISORS': 'רק אני והיועצים שלי',
    'scope.SELECTED_PARTICIPANTS': 'אנשים מסוימים שאבחר',
    'scope.ALL_PARTIES': 'המשתתפים בדיון',
    'scope.ALL_ROOM_PARTICIPANTS': 'כל מי שבדיון, כולל צופים ויועצים',

    'share.title': 'שיתוף הודעה',
    'share.whoSees': 'מי יראה את זה?',
    'share.chooseRecipients': 'בחרו את האנשים:',
    'share.toPreview': 'לבדיקה לפני שיתוף',
    'share.previewTitle': 'זה בדיוק מה שישותף',
    'share.previewNote': 'שום דבר לא נשלח עדיין. בדקו את הנוסח ואת הרשימה, ואשרו.',
    'share.previewRecipients': 'מי יראה את זה:',
    'share.confirm': 'אישור ושיתוף',
    'share.back': 'חזרה לעריכה',
    'share.conflict': 'משהו השתנה מאז הבדיקה. עברו שוב על ההודעה ואשרו מחדש.',
  },
  en: {
    'app.title': 'Common Ground',
    'app.tagline': 'A safe place to talk, understand, and reach agreement',
    'app.signOut': 'Sign out',

    'auth.welcome': 'Welcome',
    'auth.intro': 'Hold a protected, transparent discussion and reach agreement together, with the help of a personal assistant.',
    'auth.email': 'Email address',
    'auth.displayName': 'What should we call you?',
    'auth.password': 'Password',
    'auth.passwordHint': 'At least 10 characters',
    'auth.signInAction': 'Sign in',
    'auth.registerAction': 'Create account',
    'auth.switchToRegister': "Don't have an account yet? Create one",
    'auth.switchToLogin': 'Already have an account? Sign in',
    'auth.genericError': "Something didn't work. Please try again.",

    'home.title': 'My discussions',
    'home.empty.title': 'No discussions here yet',
    'home.empty.hint': 'When you want to resolve something together with someone — start here.',
    'home.start': 'Start a discussion',
    'home.updated': 'Updated',

    'status.DRAFT': "You haven't invited anyone yet",
    'status.INVITING': 'Waiting for the other person to join',
    'status.INTAKE': 'You can begin — tell your assistant what matters to you',
    'status.ACTIVE': 'The discussion is active',
    'status.WAITING_FOR_USER': 'Waiting for your answer',
    'status.PROPOSAL_READY': 'A proposal is waiting for review',
    'status.AGREEMENT_PENDING_APPROVAL': 'Waiting for everyone to approve',
    'status.AGREED': 'You reached an agreement 🎉',
    'status.PAUSED': 'The discussion is paused',
    'status.CLOSED': 'The discussion is closed',
    'status.ARCHIVED': 'The discussion is archived',

    'create.title': 'Start a discussion',
    'create.stepOf': 'Step {0} of {1}',
    'create.q1': 'What would you like to resolve?',
    'create.q1.hint': 'For example: "Splitting apartment expenses" or "Ending our contract"',
    'create.q2': 'Anything important the other person should know now?',
    'create.q2.hint': 'You can skip — you can always add it later',
    'create.q3': 'Who would you like to talk with?',
    'create.q3.hint': 'Enter an email, or skip and share an invitation link later',
    'create.next': 'Continue',
    'create.back': 'Back',
    'create.skip': 'Skip for now',
    'create.finish': 'Create the discussion',

    'room.participants': 'Who is taking part',
    'room.invite': 'Invite someone',
    'room.invite.explain': 'The invitation is a single-use link. Only the person who receives it can join.',
    'room.invite.role': 'In what role?',
    'room.role.PARTY': 'Participant in the discussion',
    'room.role.ADVISOR': 'Advisor',
    'room.role.OBSERVER': 'Viewer only',
    'room.role.OWNER': 'Discussion creator',
    'room.role.FACILITATOR': 'Facilitator',
    'room.invite.email': 'Email address (optional)',
    'room.invite.emailHint': 'If you enter an email, the invitation is also sent straight to their inbox',
    'room.invite.emailSentTo': 'Invitation emailed to {0} ✓',
    'room.invite.emailNotSent': "The email could not be sent — share the link manually",
    'room.invite.create': 'Create invitation link',
    'room.invite.copy': 'Copy the link',
    'room.invite.copied': 'Link copied ✓',
    'room.invite.shareHint': 'Send the link to the person you want to invite. It is valid for seven days.',
    'room.whatNext': 'What happens next?',
    'room.next.DRAFT': 'Invite the person you want to talk with.',
    'room.next.INVITING': 'Send the invitation link. As soon as they join, we continue.',
    'room.next.INTAKE': 'Coming soon: a private conversation with your assistant.',
    'room.objective': 'What this discussion is about',
    'room.joined': 'You joined the discussion 🎉',

    'invite.title': 'You are invited to a discussion',
    'invite.roomLabel': 'Discussion topic',
    'invite.invitedBy': 'Invited by',
    'invite.asRole': 'As',
    'invite.accept': 'Join the discussion',
    'invite.needAccount': 'To join, first sign in or create an account.',
    'invite.expired': 'This invitation is no longer valid. Ask the person who invited you for a new link.',
    'invite.alreadyMember': "You are already part of this discussion 🙂 The link is meant for someone else — just send it to them. The discussion itself is waiting in your discussions list.",
    'invite.used': 'This invitation was already used or cancelled. Ask for a new link.',

    'common.loading': 'One moment…',
    'common.retry': 'Try again',
    'common.cancel': 'Cancel',
    'common.privateBadge': 'Private — only you and your assistant',

    'tab.shared': 'Shared discussion',
    'tab.assistant': 'My assistant',
    'trust.shared': 'What is here is visible to participants according to your choice on each share',
    'trust.private': 'Private — only you and your assistant can see this',
    'more.title': 'More — participants and invitations',

    'assistant.empty': 'Think out loud here. What you write stays between you and your assistant.',
    'assistant.placeholder': "What matters to you? What's bothering you?",
    'assistant.send': 'Send to assistant',
    'assistant.suggest': 'Suggest a wording',
    'assistant.share': 'Share this wording',
    'assistant.shareOwn': 'Share this message',
    'assistant.draftLabel': 'Suggested wording from your assistant',
    'assistant.you': 'You',

    'shared.empty': 'Nothing has been shared yet. The first message can be yours.',
    'shared.waitingForOther': 'You can share messages after the other person joins the discussion.',
    'shared.notShareable': 'Messages cannot be shared in the current state of the discussion.',
    'shared.composerPlaceholder': 'Write a message to share…',
    'shared.writeMyself': 'Share as I wrote it',
    'shared.helpPhrase': 'Help me phrase it',
    'shared.withdraw': 'Withdraw this message',
    'shared.withdrawn': 'This message was withdrawn by its author',
    'shared.updated': 'Updated — version {0}',

    'origin.USER_AUTHORED': 'Written by {0}',
    'origin.AI_DRAFT_ACCEPTED': 'AI-assisted wording, approved by {0}',
    'origin.AI_DRAFT_USER_EDITED': 'AI-assisted wording, edited and approved by {0}',
    'origin.ADVISOR_AUTHORED': 'Written by advisor {0}',
    'origin.SYSTEM_GENERATED': 'Generated by the system',

    'scope.MY_ADVISORS': 'Only me and my advisors',
    'scope.SELECTED_PARTICIPANTS': 'Specific people I choose',
    'scope.ALL_PARTIES': 'The discussion participants',
    'scope.ALL_ROOM_PARTICIPANTS': 'Everyone in the discussion, including viewers and advisors',

    'share.title': 'Share a message',
    'share.whoSees': 'Who can see this?',
    'share.chooseRecipients': 'Choose the people:',
    'share.toPreview': 'Review before sharing',
    'share.previewTitle': 'This is exactly what will be shared',
    'share.previewNote': 'Nothing has been sent yet. Check the wording and the list, then confirm.',
    'share.previewRecipients': 'Who will see this:',
    'share.confirm': 'Confirm and share',
    'share.back': 'Back to editing',
    'share.conflict': 'Something changed since the review. Go over the message again and re-confirm.',
  },
};

@Injectable({ providedIn: 'root' })
export class I18nService {
  readonly lang = signal<Lang>('he');

  constructor() {
    this.apply(this.lang());
  }

  t(key: string, ...args: (string | number)[]): string {
    let value = DICT[this.lang()][key] ?? DICT.en[key] ?? key;
    args.forEach((arg, i) => {
      value = value.replace(`{${i}}`, String(arg));
    });
    return value;
  }

  setLang(lang: Lang): void {
    this.lang.set(lang);
    this.apply(lang);
  }

  private apply(lang: Lang): void {
    document.documentElement.lang = lang;
    document.documentElement.dir = lang === 'he' ? 'rtl' : 'ltr';
    document.title = this.t('app.title');
  }
}

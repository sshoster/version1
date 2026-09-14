/**
 * Seeds realistic Hebrew content into the LOCAL dev backend for the marketing screenshots
 * (npm run demo:shots). Idempotent enough: re-running creates a fresh pair of users + room.
 * Prints the login credentials the screenshot script needs.
 */
const API = process.env.API_URL ?? 'http://localhost:8080';

async function api(path, { method = 'POST', token, body } = {}) {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`${method} ${path} -> ${response.status}: ${await response.text()}`);
  }
  return response.status === 204 ? null : response.json();
}

const stamp = Date.now();
const password = 'demo-shots-pass-1';

const dana = await api('/api/v1/auth/register', {
  body: { email: `dana-shots-${stamp}@example.test`, displayName: 'דנה כהן', password },
});
const avi = await api('/api/v1/auth/register', {
  body: { email: `avi-shots-${stamp}@example.test`, displayName: 'אבי לוי', password },
});

const room = await api('/api/v1/rooms', {
  token: dana.accessToken,
  body: {
    title: 'חלוקת הוצאות הדירה',
    objective: 'להגיע להסכמה הוגנת על חלוקת שכר הדירה והחשבונות לקראת השנה הקרובה.',
  },
});

const invite = await api(`/api/v1/rooms/${room.id}/invitations`, {
  token: dana.accessToken,
  body: { role: 'PARTY', firstName: 'אבי', lastName: 'לוי' },
});
await api(`/api/v1/invitations/${invite.token}/accept`, { token: avi.accessToken });

async function share(token, text) {
  const body = { text, scope: 'ALL_PARTIES', origin: 'USER_AUTHORED' };
  const preview = await api(`/api/v1/rooms/${room.id}/share-previews`, { token, body });
  await api(`/api/v1/rooms/${room.id}/shared-items`, { token, body: { ...body, previewId: preview.previewId } });
}

await share(
  dana.accessToken,
  'חשוב לי שנחלק את ההוצאות באופן שמתחשב בהבדלי ההכנסות בינינו, ושנקבע מנגנון קבוע לחשבונות משתנים.',
);
await share(
  avi.accessToken,
  'מסכים שצריך מנגנון קבוע. מבחינתי אפשר לחלק את שכר הדירה שווה בשווה, ואת החשבונות לפי שימוש בפועל.',
);
await share(
  dana.accessToken,
  'מקובל עליי כבסיס. נשאר לסכם מה קורה בחודשים שבהם אחד מאיתנו נוסע לתקופה ארוכה.',
);

console.log(JSON.stringify({ email: dana.email ?? `dana-shots-${stamp}@example.test`, password, roomId: room.id }));

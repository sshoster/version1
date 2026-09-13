import { Browser, Page, expect, test } from '@playwright/test';

/**
 * The §17 end-to-end flow on two isolated browser contexts (Alice and Bob), deterministic on the
 * fake LLM provider: register → create → invite → join → private assistant draft → exact-preview
 * share → AI-to-AI round → formal proposal → independent approvals → outcome documents.
 */

async function register(browser: Browser, name: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/welcome');
  await page.getByRole('button', { name: /יצירת חשבון|Create/ }).first().click();
  await page.locator('#displayName').fill(name);
  await page.locator('#email').fill(`${name.toLowerCase()}-${Date.now()}@e2e.test`);
  await page.locator('#password').fill('e2e-password-12345');
  await page.getByRole('button', { name: /יצירת חשבון/ }).click();
  await expect(page).toHaveURL(/\/$/);
  return page;
}

test('MVP definition-of-done flow', async ({ browser }) => {
  // --- Alice registers and creates a discussion (3-step wizard).
  const alice = await register(browser, 'Alice');
  await alice.getByRole('link', { name: /התחלת דיון/ }).click();
  await alice.locator('#title').fill('חלוקת הוצאות E2E');
  await alice.getByRole('button', { name: /המשך/ }).click();
  await alice.getByRole('button', { name: /דילוג/ }).click();
  await alice.getByRole('button', { name: /יצירת הדיון/ }).click();
  await expect(alice).toHaveURL(/\/rooms\//);

  // --- Alice invites Bob (name becomes his display name) and copies the link.
  await alice.locator('#inviteFirstName').fill('בוב');
  await alice.getByRole('button', { name: /יצירת קישור הזמנה/ }).click();
  const inviteUrl = await alice.locator('.invite-link').innerText();

  // --- Bob registers and joins through the single-use link.
  const bob = await register(browser, 'Bob');
  await bob.goto(inviteUrl.trim());
  await bob.getByRole('button', { name: /הצטרפות לדיון/ }).click();
  await expect(bob).toHaveURL(/\/rooms\//);

  // --- Alice writes privately; the assistant suggests a wording automatically.
  await alice.getByRole('tab', { name: /העוזר שלי/ }).click();
  await alice.locator('textarea[name="privateText"]').fill('אני רוצה לשלם פחות!!');
  await alice.getByRole('button', { name: /שליחה לעוזר/ }).click();
  await expect(alice.getByText('נוסח מוצע:').first()).toBeVisible({ timeout: 20_000 });

  // --- She shares the suggestion through the exact-preview flow.
  await alice.getByRole('button', { name: /שיתוף הנוסח הזה/ }).first().click();
  await alice.getByRole('button', { name: /לבדיקה לפני שיתוף/ }).click();
  await expect(alice.getByText('זה בדיוק מה שישותף')).toBeVisible();
  await alice.getByRole('button', { name: /אישור ושיתוף/ }).click();

  // --- Bob sees the shared message with its provenance label, live.
  await expect(bob.getByText(/נוסח בעזרת AI/).first()).toBeVisible({ timeout: 20_000 });

  // --- The assistants negotiate (fake provider converges in two turns).
  await alice.getByRole('button', { name: /תנו לעוזרים לחפש פתרון/ }).click();
  await expect(alice.getByText(/סיכום הסבב/)).toBeVisible({ timeout: 30_000 });

  // --- Alice turns the recommendation into a formal proposal and requests approval.
  await alice.getByRole('button', { name: /הפיכת ההמלצה להצעה רשמית/ }).click();
  await alice.getByRole('button', { name: /בקשת אישור מכל הצדדים/ }).click();

  // --- Both parties approve independently (equal-weight buttons).
  await alice.getByRole('button', { name: 'אישור', exact: true }).click();
  await expect(bob.getByText(/האישור שלך נדרש/)).toBeVisible({ timeout: 20_000 });
  await bob.getByRole('button', { name: 'אישור', exact: true }).click();
  await expect(alice.getByText(/הגעתם להסכמה/)).toBeVisible({ timeout: 20_000 });

  // --- The Result surface produces all three labeled documents.
  await alice.getByRole('link', { name: /מסמכי הדיון/ }).click();
  await alice.getByRole('button', { name: 'יצירה', exact: true }).first().click(); // summary
  await expect(alice.getByText(/סיכום שנוצר על ידי AI/)).toBeVisible();
  const generateButtons = alice.getByRole('button', { name: 'יצירה', exact: true });
  await generateButtons.first().click(); // understandings
  await expect(alice.getByText(/אושר על ידי/).first()).toBeVisible({ timeout: 20_000 });
  await generateButtons.first().click(); // agreement draft
  await expect(alice.getByText(/טיוטת הסכם/).first()).toBeVisible({ timeout: 20_000 });
});

import { defineConfig } from '@playwright/test';

/**
 * E2E for the MVP definition-of-done flow (design doc §17), deterministic on FakeLlmProvider.
 * Prerequisites (see README): MongoDB up, backend running with LLM_PROVIDER=fake, `npm start`.
 * Run: npm run e2e   (first time: npx playwright install chromium)
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  retries: 0,
  use: {
    baseURL: 'http://localhost:4200',
    trace: 'retain-on-failure',
  },
});

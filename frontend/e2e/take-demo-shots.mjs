/**
 * Takes the "peek inside the app" screenshots used on the create-discussion page.
 * Usage: node e2e/seed-demo-shots.mjs  →  node e2e/take-demo-shots.mjs '<seed JSON output>'
 * Requires the local backend (8080) + frontend dev server (4200) to be running.
 */
import { chromium } from 'playwright-core';
import { mkdirSync } from 'node:fs';

const seed = JSON.parse(process.argv[2] ?? '{}');
if (!seed.email) throw new Error('Pass the seed script JSON output as the first argument');

const APP = process.env.APP_URL ?? 'http://localhost:4200';
mkdirSync('public/demo', { recursive: true });

const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });

await page.goto(`${APP}/welcome`);
await page.fill('#email', seed.email);
await page.fill('#password', seed.password);
await page.click('button[type=submit]');
await page.waitForURL(`${APP}/`);
await page.waitForSelector('.room-card');
await page.waitForTimeout(700);
await page.screenshot({ path: 'public/demo/home.png' });

await page.goto(`${APP}/rooms/${seed.roomId}`);
await page.waitForSelector('.head');
await page.waitForTimeout(1500); // panels + presence settle
await page.screenshot({ path: 'public/demo/room.png' });

// The private assistant tab.
await page.click('text=העוזר שלי');
await page.waitForTimeout(1200);
await page.screenshot({ path: 'public/demo/assistant.png' });

await browser.close();
console.log('Saved public/demo/{home,room,assistant}.png');

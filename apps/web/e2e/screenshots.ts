import { existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';

import type { Browser, BrowserContext, Locator, Page } from '@playwright/test';

import {
  GOLD_A,
  GOLD_B,
  PASSWORD,
  chooseMarket,
  header,
  main,
  open,
  productId,
  useLanguage,
} from './support/app';
import { e2eEnv } from './support/env';
import { type PageMonitor, expect, test, watchPage } from './support/fixtures';
import { FRAME_WIDTH, frameTo } from './support/frame';
import { linkIn, messagesTo, messageText } from './support/mailpit';

/**
 * README screenshots (`npm run e2e:screenshots`, Playwright project `screenshots`, not part of
 * the e2e or adversarial suites). Runs against the running, seeded stack in headless Chromium,
 * light theme, scale 1, and overwrites six files in `docs/screenshots/` at the repository root,
 * one per README image and language. Every image is 1280 px wide; its height is composed so the
 * frame ends 16 px below the last block it shows and no card, row or control is cut at the
 * bottom edge (see `support/frame.ts`):
 *
 * - `overview-<lang>.png`: the query builder as the landing view (visitor), from the header
 *   down to the end of the "What matters to you" card, with the query summary beside it;
 * - `results-breakdown-<lang>.png`: signed-in ranked results (five per page), framed to the end
 *   of the table and its pagination, with the breakdown drawer of the first row open over them;
 * - `compare-<lang>.png`: the golden pair GOLD-A vs GOLD-B, from the header down to the end of
 *   the per-nutrient delta table, with the verdict card above it.
 *
 * Each language's overview and signed-in pair are separate tests, so one failure does not stop
 * the others; the last test checks that all six files exist at 1280 px and prints their sizes.
 *
 * Only the synthetic TR fixture products appear (fictional brands, demo prices). The signed-in
 * shots use a fixed demo account on example.com: registered and verified through Mailpit on the
 * first run, reused afterwards. The UI language is switched with the header language control.
 */

const OUT_DIR = resolve(__dirname, '..', '..', '..', 'docs', 'screenshots');
/** Viewport while a page loads and is measured; `frameTo` then sets the capture height. */
const VIEWPORT = { width: FRAME_WIDTH, height: 800 } as const;
const LANGUAGES = ['en', 'tr'] as const;
const DEMO_EMAIL = 'readme-demo@example.com';
const CONFIRM_SUBJECT = /Confirm your VitaDelta e-mail address/;
const IMAGES = LANGUAGES.flatMap((lang) => [
  `overview-${lang}`,
  `results-breakdown-${lang}`,
  `compare-${lang}`,
]);

/** File names an earlier layout of this script wrote; removed so only the six images remain. */
const RETIRED_FILES = [
  '01-query.png',
  '02-results-visitor.png',
  '03-results-breakdown.png',
  '04-compare-verdict.png',
  '05-product-detail.png',
  '06-account.png',
  '07-results-dark.png',
];

/** Hides scrollbars and the caret; applied to the captured frame only. */
const CAPTURE_CSS = `
  html, body, * { scrollbar-width: none !important; }
  ::-webkit-scrollbar { display: none !important; }
`;

async function api(path: string, init: RequestInit = {}, token?: string): Promise<Response> {
  return fetch(`${e2eEnv().apiBase}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
}

async function login(): Promise<string | null> {
  const response = await api('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: DEMO_EMAIL, password: PASSWORD }),
  });
  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`POST /auth/login answered ${response.status}`);
  }
  return ((await response.json()) as { accessToken: string }).accessToken;
}

async function isVerified(token: string): Promise<boolean> {
  const response = await api('/me', {}, token);
  if (!response.ok) {
    throw new Error(`GET /me answered ${response.status}`);
  }
  return ((await response.json()) as { emailVerified: boolean }).emailVerified;
}

/** Verifies the demo account with the first confirmation e-mail that arrived after `since`. */
async function verifyFromMail(since: number): Promise<void> {
  let messageId = '';
  await expect
    .poll(
      async () => {
        const fresh = (await messagesTo(DEMO_EMAIL)).find(
          (message) =>
            CONFIRM_SUBJECT.test(message.Subject) && Date.parse(message.Created) >= since - 1_000,
        );
        messageId = fresh?.ID ?? '';
        return messageId !== '';
      },
      { timeout: 60_000, intervals: [500, 1_000, 2_000], message: 'confirmation e-mail' },
    )
    .toBe(true);
  const link = linkIn(await messageText(messageId), '/auth/verify');
  const token = new URL(link).hash.replace(/^#token=/, '');
  const response = await api('/auth/verify-email', {
    method: 'POST',
    body: JSON.stringify({ token }),
  });
  if (!response.ok) {
    throw new Error(`POST /auth/verify-email answered ${response.status}`);
  }
}

/**
 * Makes sure the verified demo account exists: an existing account is reused, a missing one is
 * registered, and an unverified one gets a new verification e-mail. A verified account shows no
 * "verify your e-mail" notice in the frame.
 */
async function prepareDemoAccount(): Promise<void> {
  const token = await login();
  if (token && (await isVerified(token))) {
    return;
  }
  const since = Date.now();
  if (token) {
    const resent = await api('/auth/resend-verification', {
      method: 'POST',
      body: JSON.stringify({ email: DEMO_EMAIL }),
    });
    if (!resent.ok) {
      throw new Error(`POST /auth/resend-verification answered ${resent.status}`);
    }
  } else {
    const registered = await api('/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        email: DEMO_EMAIL,
        password: PASSWORD,
        locale: 'en',
        countryCode: 'TR',
      }),
    });
    if (registered.status !== 202) {
      throw new Error(`POST /auth/register answered ${registered.status}`);
    }
  }
  await verifyFromMail(since);
  const verified = await login();
  if (!verified || !(await isVerified(verified))) {
    throw new Error(`The demo account ${DEMO_EMAIL} could not be verified`);
  }
}

async function newPage(
  browser: Browser,
): Promise<{ context: BrowserContext; page: Page; monitor: PageMonitor }> {
  const context = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: 1,
    colorScheme: 'light',
    reducedMotion: 'reduce',
    locale: 'en-US',
    timezoneId: 'UTC',
  });
  const page = await context.newPage();
  const monitor = await watchPage(page);
  return { context, page, monitor };
}

/**
 * Writes the viewport, framed beforehand with `frameTo`, to `docs/screenshots/<name>.png` once
 * transitions have ended, with no focus ring, hover state, caret or scrollbar in the frame.
 */
async function shoot(page: Page, name: string): Promise<void> {
  await page.addStyleTag({ content: CAPTURE_CSS });
  await page.mouse.move(1, 1);
  await page.evaluate(async () => {
    const active = document.activeElement;
    if (active instanceof HTMLElement && active !== document.body) {
      active.blur();
    }
    await Promise.all(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getComputedTiming().iterations !== Infinity)
        .map((animation) => animation.finished.catch(() => undefined)),
    );
  });
  await page.screenshot({
    path: resolve(OUT_DIR, `${name}.png`),
    animations: 'disabled',
    caret: 'hide',
    scale: 'css',
  });
}

async function signIn(page: Page): Promise<void> {
  await open(page, '/auth/login');
  await useLanguage(page, 'en');
  await main(page).getByLabel('E-mail').fill(DEMO_EMAIL);
  await main(page).getByLabel('Password').fill(PASSWORD);
  await main(page).getByRole('button', { name: 'Log in' }).click();
  // The account may come back with its stored language, so both menu names are accepted.
  await expect(
    header(page).getByRole('button', { name: /^(Account menu|Hesap menüsü)/ }),
  ).toBeVisible();
  await chooseMarket(page, 'TR');
}

/** Width and height of a PNG file, read from its IHDR chunk. */
function pngSize(file: string): { width: number; height: number } {
  const header = readFileSync(file).subarray(0, 24);
  return { width: header.readUInt32BE(16), height: header.readUInt32BE(20) };
}

test.describe('README screenshots', () => {
  test.beforeAll(async () => {
    mkdirSync(OUT_DIR, { recursive: true });
    for (const file of RETIRED_FILES) {
      rmSync(resolve(OUT_DIR, file), { force: true });
    }
    await prepareDemoAccount();
  });

  for (const lang of LANGUAGES) {
    test(`overview-${lang}: the query builder as a visitor`, async ({ browser }) => {
      const { context, page, monitor } = await newPage(browser);
      await open(page, '/');
      await chooseMarket(page, 'TR');
      await open(page, '/?country=TR');
      await useLanguage(page, lang);
      const form = main(page).locator('form').first();
      await expect(form).toBeVisible();
      await expect(main(page).getByRole('heading', { level: 1 })).toBeVisible();
      const weights = form.locator('details.weights');
      await expect(weights).toBeVisible();
      await frameTo(page, [weights, main(page).locator('aside .summary-card')]);
      await shoot(page, `overview-${lang}`);
      monitor.expectClean();
      await context.close();
    });

    test(`results-breakdown-${lang} and compare-${lang}: signed in`, async ({ browser }) => {
      const { context, page, monitor } = await newPage(browser);
      await signIn(page);
      const a = await productId(GOLD_A.code);
      const b = await productId(GOLD_B.code);

      // Five rows per page keep the whole table card and its pagination in the frame.
      await open(page, '/results?country=TR&size=5');
      await useLanguage(page, lang);
      // Language-neutral: the table's accessible name is translated.
      const rows = main(page).locator('vd-results-table tbody tr[data-product]');
      await expect(rows).toHaveCount(5);
      const blocks: Locator[] = [
        main(page).locator('vd-results-table'),
        main(page).locator('vd-pagination'),
      ];
      await frameTo(page, blocks);
      await rows.first().locator('button[data-action="breakdown"]').click();
      await expect(page.getByRole('dialog')).toBeVisible();
      await shoot(page, `results-breakdown-${lang}`);

      // Loading the next page replaces the results page and its drawer: nothing is closed first.
      await page.setViewportSize(VIEWPORT);
      await open(page, `/compare/${a}/${b}?country=TR`);
      await useLanguage(page, lang);
      const verdict = main(page).locator('vd-verdict-card section[data-verdict]');
      await expect(verdict).toHaveAttribute('data-verdict', 'NOT_WORTH_IT');
      const deltas = main(page).locator('vd-delta-table');
      await expect(deltas.locator('tbody tr').first()).toBeVisible();
      await frameTo(page, [main(page).locator('vd-verdict-card'), deltas]);
      await shoot(page, `compare-${lang}`);
      monitor.expectClean();
      await context.close();
    });
  }

  test('all six images are written at 1280 px', () => {
    const sizes = IMAGES.map((name) => {
      const file = resolve(OUT_DIR, `${name}.png`);
      expect(existsSync(file), `${name}.png`).toBe(true);
      const size = pngSize(file);
      expect(size.width, `${name}.png width`).toBe(FRAME_WIDTH);
      return `${name}.png ${size.width}x${size.height}`;
    });
    console.log(sizes.join('\n'));
  });
});

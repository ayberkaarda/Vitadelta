import { mkdirSync } from 'node:fs';
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
  rankProducts,
  resultRows,
  useLanguage,
} from './support/app';
import { e2eEnv } from './support/env';
import { type PageMonitor, expect, test, watchPage } from './support/fixtures';
import { linkIn, messagesTo, messageText } from './support/mailpit';

/**
 * README screenshots (`npm run e2e:screenshots`, Playwright project `screenshots`, not part of
 * the e2e or adversarial suites). Runs against the running, seeded stack in headless Chromium at
 * 1280 x 800 and writes stable file names to `docs/screenshots/` at the repository root.
 *
 * Only the synthetic TR fixture products appear (fictional brands, demo prices); the signed-in
 * shots use a fixed demo account on example.com that the script registers and verifies through
 * Mailpit on its first run and reuses afterwards.
 */

const OUT_DIR = resolve(__dirname, '..', '..', '..', 'docs', 'screenshots');
const VIEWPORT = { width: 1280, height: 800 } as const;
const DEMO_EMAIL = 'readme-demo@example.com';
const CONFIRM_SUBJECT = /Confirm your VitaDelta e-mail address/;

interface TokenResponse {
  readonly accessToken: string;
}

interface MeResponse {
  readonly emailVerified: boolean;
}

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
  return ((await response.json()) as TokenResponse).accessToken;
}

async function me(token: string): Promise<MeResponse> {
  const response = await api('/me', {}, token);
  if (!response.ok) {
    throw new Error(`GET /me answered ${response.status}`);
  }
  return (await response.json()) as MeResponse;
}

/** Verifies the demo account with the first confirmation e-mail that arrives after `since`. */
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
 * Makes sure the demo account exists, is verified, has two saved products and one alert, so the
 * usage meters of the account page show real server values. The alert needs a verified e-mail
 * and an access token issued after the verification, hence the second login.
 */
async function prepareDemoAccount(): Promise<void> {
  let token = await login();
  if (!token) {
    const since = Date.now();
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
    await verifyFromMail(since);
    token = await login();
  } else if (!(await me(token)).emailVerified) {
    const since = Date.now();
    await api('/auth/resend-verification', {
      method: 'POST',
      body: JSON.stringify({ email: DEMO_EMAIL }),
    });
    await verifyFromMail(since);
    token = await login();
  }
  if (!token) {
    throw new Error(`The demo account ${DEMO_EMAIL} cannot log in`);
  }

  // Both calls are idempotent: a second run leaves the same two saved products and one alert.
  const goldA = await productId(GOLD_A.code);
  const goldB = await productId(GOLD_B.code);
  for (const id of [goldA, goldB]) {
    const saved = await api(
      '/me/saved-products',
      { method: 'POST', body: JSON.stringify({ productId: id, countryCode: 'TR' }) },
      token,
    );
    if (!saved.ok) {
      throw new Error(`POST /me/saved-products answered ${saved.status}`);
    }
  }
  const alert = await api(
    '/me/alerts',
    {
      method: 'POST',
      body: JSON.stringify({ productId: goldB, countryCode: 'TR', trigger: 'BACK_IN_STOCK' }),
    },
    token,
  );
  if (!alert.ok) {
    throw new Error(`POST /me/alerts answered ${alert.status}`);
  }
}

async function newPage(
  browser: Browser,
  colorScheme: 'light' | 'dark' = 'light',
): Promise<{ context: BrowserContext; page: Page; monitor: PageMonitor }> {
  const context = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: 1,
    colorScheme,
    reducedMotion: 'reduce',
    locale: 'en-US',
    timezoneId: 'UTC',
  });
  const page = await context.newPage();
  const monitor = await watchPage(page);
  return { context, page, monitor };
}

/** Waits for running transitions, then writes the viewport to `docs/screenshots/<name>.png`. */
async function shoot(page: Page, name: string): Promise<void> {
  await page.evaluate(() =>
    Promise.all(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getComputedTiming().iterations !== Infinity)
        .map((animation) => animation.finished.catch(() => undefined)),
    ),
  );
  await page.screenshot({
    path: resolve(OUT_DIR, `${name}.png`),
    animations: 'disabled',
    caret: 'hide',
    scale: 'css',
  });
}

/** Scrolls `target` to the top of the viewport (below the page header). */
async function scrollToTop(target: Locator): Promise<void> {
  await target.evaluate((element) => {
    const top = element.getBoundingClientRect().top + window.scrollY - 16;
    window.scrollTo({ top, behavior: 'instant' });
  });
}

async function signIn(page: Page): Promise<void> {
  await open(page, '/auth/login');
  await useLanguage(page, 'en');
  await main(page).getByLabel('E-mail').fill(DEMO_EMAIL);
  await main(page).getByLabel('Password').fill(PASSWORD);
  await main(page).getByRole('button', { name: 'Log in' }).click();
  await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeVisible();
  await chooseMarket(page, 'TR');
}

test.describe.serial('README screenshots', () => {
  test.beforeAll(async () => {
    mkdirSync(OUT_DIR, { recursive: true });
    await prepareDemoAccount();
  });

  test('visitor: query page and results with locked rows', async ({ browser }) => {
    const { context, page, monitor } = await newPage(browser);
    await open(page, '/');
    await chooseMarket(page, 'TR');
    await expect(main(page).getByRole('button', { name: 'Rank products' })).toBeVisible();
    await shoot(page, '01-query');

    await rankProducts(page);
    await expect(resultRows(page)).toHaveCount(3);
    const locked = main(page).getByRole('list', { name: 'Locked results' });
    await expect(locked).toBeVisible();
    await shoot(page, '02-results-visitor');
    monitor.expectClean();
    await context.close();
  });

  test('signed in: results with the breakdown drawer, compare, product and account', async ({
    browser,
  }) => {
    const { context, page, monitor } = await newPage(browser);
    await signIn(page);

    await open(page, '/results?country=TR');
    await expect(resultRows(page).first()).toBeVisible();
    await resultRows(page)
      .first()
      .getByRole('button', { name: /^Score breakdown of/ })
      .click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await shoot(page, '03-results-breakdown');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();

    const a = await productId(GOLD_A.code);
    const b = await productId(GOLD_B.code);
    await open(page, `/compare/${a}/${b}?country=TR`);
    const verdict = main(page).getByRole('region', { name: 'Verdict' });
    await expect(verdict.getByText('Not worth it', { exact: true })).toBeVisible();
    await scrollToTop(verdict);
    await shoot(page, '04-compare-verdict');

    await open(page, `/products/${b}?country=TR`);
    const formulation = main(page).locator('vd-formulation-table');
    await expect(formulation.getByRole('columnheader', { name: 'Upper limit' })).toBeVisible();
    await scrollToTop(main(page).getByRole('heading', { name: 'Formulation' }));
    await shoot(page, '05-product-detail');

    await open(page, '/account');
    await expect(main(page).getByRole('region', { name: 'Your plan' })).toBeVisible();
    await expect(main(page).getByRole('meter').first()).toBeVisible();
    await shoot(page, '06-account');
    monitor.expectClean();
    await context.close();
  });

  test('signed in, dark theme: results', async ({ browser }) => {
    const { context, page, monitor } = await newPage(browser, 'dark');
    await signIn(page);
    await open(page, '/results?country=TR');
    await expect(resultRows(page).first()).toBeVisible();
    await shoot(page, '07-results-dark');
    monitor.expectClean();
    await context.close();
  });
});

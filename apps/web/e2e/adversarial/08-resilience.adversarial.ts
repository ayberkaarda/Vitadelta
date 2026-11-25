import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

import { type Page, expect, test } from '@playwright/test';

import { header, main, open, resultRows, useLanguage } from '../support/app';
import { e2eEnv } from '../support/env';
import { adoptSession, apiLogin, sleep, watch } from './support/attack';

/**
 * Failure states (project specification §10.3, §15.4). With the API gone mid-session the app
 * shows its own error states, never a proxy error page, raw response text or a stack trace,
 * and it recovers once the API is back. Rate-limit and quota answers render the designed
 * notices.
 */

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const LEAKS =
  /Bad Gateway|<html|<\/?body|nginx|Exception|Traceback|stack ?trace|\bat [\w$.]+ \(|problem\+json|"type"\s*:/i;

function compose(...args: string[]): void {
  execFileSync(
    'docker',
    [
      'compose',
      '--env-file',
      resolve(ROOT, '.env'),
      '-f',
      resolve(ROOT, 'infra', 'docker-compose.yml'),
      ...args,
    ],
    { cwd: ROOT, stdio: 'pipe', timeout: 240_000 },
  );
}

async function waitForApi(): Promise<void> {
  const deadline = Date.now() + 240_000;
  while (Date.now() < deadline) {
    const status = await fetch(`${e2eEnv().apiBase}/health`)
      .then((response) => response.status)
      .catch(() => 0);
    if (status === 200) {
      return;
    }
    await sleep(2_000);
  }
  throw new Error('The API did not come back within 240 s');
}

/** In-app navigation (no reload) to `url`. */
async function navigateInApp(page: Page, url: string): Promise<void> {
  await page.evaluate((target) => {
    history.pushState(null, '', target);
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }, url);
}

async function expectNoLeak(page: Page, label: string): Promise<void> {
  const text = await page.locator('body').innerText();
  expect(text.match(LEAKS)?.[0] ?? null, `${label}: raw or internal text on screen`).toBeNull();
}

test('with the API stopped mid-session the app shows its error states and recovers', async ({
  browser,
}) => {
  test.setTimeout(600_000);
  const env = e2eEnv();
  const context = await browser.newContext();
  await adoptSession(context, await apiLogin(env.adminEmail, env.adminPassword));
  const page = await context.newPage();
  const probe = await watch(page);
  await open(page, '/results?country=TR');
  await useLanguage(page, 'en');
  await expect(resultRows(page).first()).toBeVisible();

  let stopped = false;
  try {
    compose('stop', 'api');
    stopped = true;

    await navigateInApp(page, '/results?country=TR&mode=PRICE');
    await expect(main(page).getByRole('heading', { level: 2 })).toHaveText(
      /Something went wrong|No connection/,
      { timeout: 30_000 },
    );
    await expect(main(page).getByRole('button', { name: 'Try again' })).toBeVisible();
    await expectNoLeak(page, 'results during the outage');

    await navigateInApp(page, '/account');
    await sleep(2_000);
    await expectNoLeak(page, 'account during the outage');

    await page.reload();
    await expect(header(page)).toBeVisible({ timeout: 30_000 });
    await sleep(2_000);
    await expectNoLeak(page, 'reload during the outage');
    expect(probe.pageErrors, 'uncaught errors during the outage').toEqual([]);

    compose('start', 'api');
    stopped = false;
    await waitForApi();

    await page.reload();
    await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeVisible({
      timeout: 30_000,
    });
    await open(page, '/results?country=TR');
    await expect(resultRows(page).first()).toBeVisible();
    expect(probe.pageErrors, 'uncaught errors after recovery').toEqual([]);
  } finally {
    if (stopped) {
      compose('start', 'api');
      await waitForApi();
    }
    await context.close();
  }
});

test('a 429 on ranking renders the rate-limit notice with its countdown', async ({ page }) => {
  await page.route('**/api/v1/recommendations/query', (route) =>
    route.fulfill({
      status: 429,
      headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '30' },
      body: JSON.stringify({
        type: 'https://vitadelta.dev/problems/rate_limited',
        title: 'Too Many Requests',
        status: 429,
        code: 'rate_limited',
        detail: 'internal limiter key 10.0.0.7 bucket login',
      }),
    }),
  );
  await open(page, '/results?country=TR');
  await useLanguage(page, 'en');
  await expect(main(page).getByRole('heading', { name: 'Too many requests' })).toBeVisible();
  await expect(main(page).getByRole('button', { name: /Try again in \d+ s/ })).toBeDisabled();
  await expect(main(page)).not.toContainText('internal limiter key');
  await expectNoLeak(page, 'rate-limit notice');
});

test('the fourth anonymous query renders the quota notice', async ({ page }) => {
  for (let query = 0; query < 3; query += 1) {
    await open(
      page,
      `/results?country=TR${query === 0 ? '' : `&mode=${['PRICE', 'RICHNESS'][query - 1]}`}`,
    );
    await useLanguage(page, 'en');
    await expect(resultRows(page).first()).toBeVisible();
  }
  await open(page, '/results?country=TR&size=50');
  await expect(
    main(page).getByRole('heading', { name: 'You have used your 3 free queries' }),
  ).toBeVisible();
  await expect(resultRows(page)).toHaveCount(0);
  await expectNoLeak(page, 'quota notice');
});

test('a real login rate limit renders the designed notice', async ({ page }) => {
  test.setTimeout(180_000);
  await open(page, '/auth/login');
  await useLanguage(page, 'en');
  let limited = false;
  for (let attempt = 0; attempt < 15 && !limited; attempt += 1) {
    await main(page).getByLabel('E-mail').fill(`nobody-${attempt}@example.com`);
    await main(page).getByLabel('Password').fill('Wrong-Password-123456');
    const answer = page.waitForResponse((response) =>
      response.url().endsWith('/api/v1/auth/login'),
    );
    await main(page).getByRole('button', { name: 'Log in' }).click();
    limited = (await answer).status() === 429;
  }
  expect(limited, 'login answered 429').toBe(true);
  await expect(main(page).getByRole('alert')).toContainText('Too many requests');
  await expectNoLeak(page, 'login rate limit');
});

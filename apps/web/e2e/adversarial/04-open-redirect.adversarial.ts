import { type Page, expect, test } from '@playwright/test';

import { main, open } from '../support/app';
import { e2eEnv } from '../support/env';
import { type Probe, uiLogin, watch } from './support/attack';

/**
 * Open redirect (project specification §10.4 `returnUrl` allowlist, §15.7 case 17).
 *
 * After a login the app navigates to `returnUrl` only when it is an app-relative path; every
 * other value falls back to `/`. A hostile value must never take the browser off the app
 * origin, open a window or make a request to the attacker host.
 */

const EVIL_HOST = 'evil.example';

/** Raw values of the `returnUrl` query parameter (encoded once when put into the link). */
const HOSTILE_RETURN_URLS: readonly { label: string; value: string }[] = [
  { label: 'absolute https', value: 'https://evil.example' },
  { label: 'protocol-relative', value: '//evil.example' },
  { label: 'slash backslash', value: '/\\evil.example' },
  { label: 'backslash backslash', value: '\\\\evil.example' },
  { label: 'encoded slashes after one slash', value: '/%2F%2Fevil.example' },
  { label: 'encoded protocol-relative', value: '%2F%2Fevil.example' },
  { label: 'double-encoded protocol-relative', value: '%252F%252Fevil.example' },
  { label: 'encoded backslash', value: '/%5Cevil.example' },
  { label: 'scheme without slashes', value: 'https:evil.example' },
  { label: 'javascript scheme', value: 'javascript:alert(1)' },
  { label: 'javascript scheme with slashes', value: 'javascript://evil.example/%0Aalert(1)' },
  { label: 'data URL', value: 'data:text/html,<script>alert(1)</script>' },
  { label: 'tab inside the prefix', value: '/\t/evil.example' },
  { label: 'dot segment before slashes', value: '/.//evil.example' },
  { label: 'leading space', value: ' //evil.example' },
  { label: '5 kB value', value: `//evil.example/${'a'.repeat(5_000)}` },
  { label: '5 kB relative value', value: `/${'a'.repeat(5_000)}` },
];

function appOrigin(): string {
  return new URL(e2eEnv().baseURL).origin;
}

async function guard(page: Page): Promise<{ probe: Probe; evilRequests: string[] }> {
  const probe = await watch(page);
  const evilRequests: string[] = [];
  await page.context().route(`**://${EVIL_HOST}/**`, async (route) => {
    evilRequests.push(route.request().url());
    await route.abort();
  });
  return { probe, evilRequests };
}

async function expectStayed(
  page: Page,
  probe: Probe,
  evilRequests: string[],
  label: string,
): Promise<void> {
  // Give any delayed navigation a chance to happen.
  await page.waitForLoadState('networkidle');
  expect(new URL(page.url()).origin, `${label}: origin after login`).toBe(appOrigin());
  expect(page.url(), `${label}: final URL`).not.toMatch(/^[a-z]+:\/\/[^/]*evil\.example/i);
  expect(evilRequests, `${label}: requests to the attacker host`).toEqual([]);
  expect(page.context().pages(), `${label}: no extra window`).toHaveLength(1);
  expect(probe.dialogs, `${label}: dialogs`).toEqual([]);
  expect(probe.pageErrors, `${label}: uncaught errors`).toEqual([]);
}

for (const { label, value } of HOSTILE_RETURN_URLS) {
  test(`returnUrl ${label} keeps the browser on the app origin after login`, async ({ page }) => {
    test.setTimeout(180_000);
    const { probe, evilRequests } = await guard(page);
    await open(page, `/auth/login?returnUrl=${encodeURIComponent(value)}`);
    const env = e2eEnv();
    await uiLogin(page, { email: env.adminEmail, password: env.adminPassword });
    await expectStayed(page, probe, evilRequests, label);
    const path = new URL(page.url()).pathname;
    expect(path.startsWith('//'), `${label}: path ${path}`).toBe(false);
  });
}

test('a repeated returnUrl parameter falls back to the start page', async ({ page }) => {
  test.setTimeout(180_000);
  const { probe, evilRequests } = await guard(page);
  await open(page, '/auth/login?returnUrl=%2Faccount&returnUrl=%2F%2Fevil.example');
  const env = e2eEnv();
  await uiLogin(page, { email: env.adminEmail, password: env.adminPassword });
  await expectStayed(page, probe, evilRequests, 'repeated returnUrl');
});

test('route parameters and paths cannot point the app at another origin', async ({ page }) => {
  const { probe, evilRequests } = await guard(page);
  const paths = [
    '//evil.example/account',
    '/products/%2F%2Fevil.example?country=TR',
    '/products/..%2F..%2F..%2Fevil.example?country=TR',
    '/compare/https:%2F%2Fevil.example/%2F%2Fevil.example?country=TR',
    '/account?returnUrl=%2F%2Fevil.example',
    '/auth/register?returnUrl=%2F%2Fevil.example',
  ];
  for (const path of paths) {
    await page.goto(`${appOrigin()}${path}`);
    await page.waitForLoadState('networkidle');
    expect(new URL(page.url()).origin, `${path}: origin`).toBe(appOrigin());
    const hrefs = await page
      .locator('a[href]')
      .evaluateAll((anchors) => anchors.map((anchor) => anchor.getAttribute('href') ?? ''));
    const offsite = hrefs.filter((href) => {
      const decoded = decodeURIComponent(href);
      return (
        /^(https?:)?\/\/evil\.example/i.test(href) || /returnUrl=\/\/|returnUrl=\/\\/.test(decoded)
      );
    });
    expect(offsite, `${path}: links pointing off the origin`).toEqual([]);
  }
  await expect(main(page)).toBeVisible();
  expect(evilRequests).toEqual([]);
  expect(probe.pageErrors).toEqual([]);
});

import { type Page, type Response, expect, test } from '@playwright/test';

import { header, main, open } from '../support/app';
import { e2eEnv } from '../support/env';
import {
  type Account,
  adoptSession,
  apiFetch,
  apiLogin,
  bearer,
  marketCatalog,
  registerAccount,
  sleep,
  watch,
} from './support/attack';

/**
 * Client-side entitlement honesty (project specification §8.4, §10.2, §10.4, §15.7 case 3,
 * browser view). The server decides what an anonymous visitor or a normal user may see; the
 * page must not receive, keep or reveal more, whatever the URL says, and a forged client-side
 * role must not unlock any data.
 */

const MARKET = 'TR';

interface Ranked {
  readonly productId: string;
  readonly code: string;
}

interface QueryAnswer {
  readonly request: { page?: number; pageSize?: number };
  readonly items: readonly Ranked[];
  readonly body: string;
}

/** Collects every API answer of `page` with its raw body. */
function recordApi(page: Page): {
  answers: { url: string; body: string }[];
  queries: QueryAnswer[];
} {
  const answers: { url: string; body: string }[] = [];
  const queries: QueryAnswer[] = [];
  page.on('response', (response: Response) => {
    const url = new URL(response.url());
    if (!url.pathname.startsWith('/api/')) {
      return;
    }
    void response
      .text()
      .then((body) => {
        answers.push({ url: url.pathname + url.search, body });
        if (url.pathname === '/api/v1/recommendations/query' && response.ok()) {
          const parsed = JSON.parse(body) as { items: Ranked[] };
          queries.push({
            request: JSON.parse(response.request().postData() ?? '{}') as QueryAnswer['request'],
            items: parsed.items,
            body,
          });
        }
      })
      .catch(() => undefined);
  });
  return { answers, queries };
}

/**
 * Public catalog endpoints are an accepted exposure (§8.4): they reveal catalog data to anyone
 * and are excluded from the locked-data check.
 */
function isAcceptedExposure(url: string): boolean {
  return /^\/api\/v1\/(products|recommendations\/compare)/.test(url);
}

async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  await sleep(500);
}

test('an anonymous visitor never receives or renders locked products', async ({ browser }) => {
  test.setTimeout(180_000);
  const catalog = await marketCatalog(MARKET);
  expect(catalog.length, `${MARKET} catalog size`).toBeGreaterThan(5);

  const urls = [
    [
      `/results?country=${MARKET}`,
      `/results?country=${MARKET}&page=2&size=100`,
      `/results?country=${MARKET}&mode=PRICE&page=3&size=100`,
    ],
    [
      `/results?country=${MARKET}&mode=RICHNESS&size=100`,
      `/results?country=${MARKET}&stock=all&size=1000&page=1`,
      `/results?country=${MARKET}&mode=CUSTOM&lambda=0.5&page=99`,
    ],
  ];
  for (const batch of urls) {
    const context = await browser.newContext();
    const page = await context.newPage();
    const probe = await watch(page);
    const { answers, queries } = recordApi(page);
    for (const url of batch) {
      const before = queries.length;
      await open(page, url);
      await settle(page);
      const answer = queries[before];
      expect(answer, `${url}: one ranking answer`).toBeDefined();
      if (!answer) {
        continue;
      }
      const visible = new Set(answer.items.flatMap((item) => [item.code, item.productId]));
      expect(answer.items.length, `${url}: items sent to an anonymous visitor`).toBeLessThanOrEqual(
        3,
      );
      if ((answer.request.page ?? 1) > 1) {
        expect(answer.items, `${url}: page > 1 is empty`).toEqual([]);
      }
      const locked = catalog.filter((entry) => !visible.has(entry.code));
      const html = await page.content();
      for (const entry of locked) {
        expect(answer.body, `${url}: ranking body carries locked ${entry.code}`).not.toContain(
          entry.id,
        );
        expect(answer.body, `${url}: ranking body carries locked ${entry.code}`).not.toContain(
          `"${entry.code}"`,
        );
        expect(html, `${url}: DOM carries locked ${entry.code}`).not.toContain(entry.id);
        expect(html, `${url}: DOM carries locked ${entry.code}`).not.toMatch(
          new RegExp(`(^|[^A-Z0-9-])${entry.code.replace(/[-]/g, '\\-')}([^A-Z0-9-]|$)`),
        );
      }
    }
    for (const answer of answers.filter((entry) => !isAcceptedExposure(entry.url))) {
      if (answer.url.startsWith('/api/v1/recommendations/query')) {
        continue;
      }
      for (const entry of catalog) {
        expect(answer.body, `${answer.url} carries product ${entry.code}`).not.toContain(entry.id);
      }
    }
    expect(probe.pageErrors).toEqual([]);
    await context.close();
  }
});

test.describe('signed-in normal user', () => {
  let account: Account;

  test.beforeAll(async () => {
    account = await registerAccount('entitlement');
  });

  async function userPage(browser: import('@playwright/test').Browser): Promise<Page> {
    const context = await browser.newContext();
    await adoptSession(context, await apiLogin(account.email, account.password));
    return context.newPage();
  }

  test('tampered URL query state neither crashes the app nor floods the API', async ({
    browser,
  }) => {
    test.setTimeout(240_000);
    const page = await userPage(browser);
    const probe = await watch(page);
    await open(page, `/results?country=${MARKET}`);
    await expect(main(page).getByRole('table', { name: 'Ranked products' })).toBeVisible();

    const hostile = [
      'page=1e308',
      'page=-1&size=-5',
      'size=NaN&page=NaN',
      'size=99999999999999999999',
      'mode=EVIL',
      'mode=CUSTOM&lambda=Infinity',
      'mode=CUSTOM&lambda=1e-400',
      'country=__proto__',
      'country=tr',
      'w=__proto__:1,constructor:1,prototype:1,VITAMIN:1e309',
      'w=VITAMIN:-1,MINERAL:NaN',
      'o=__proto__:5,constructor:5,toString:1',
      'req=__proto__,constructor:1:mg,VIT_C:1e400:mg',
      'stock=%00&ul=%0A',
    ];
    for (const query of hostile) {
      const sent = probe.apiRequests('/api/v1/recommendations/query').length;
      const target = query.startsWith('country=')
        ? `/results?${query}`
        : `/results?country=${MARKET}&${query}`;
      await open(page, target);
      await settle(page);
      const fired = probe.apiRequests('/api/v1/recommendations/query').length - sent;
      expect(fired, `${query}: ranking requests`).toBeLessThanOrEqual(2);
      await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeEnabled();
    }

    // 10 000 weight overrides and required nutrients through an in-app navigation
    // (the URL is far beyond what a server accepts in a request line).
    const overrides = Array.from({ length: 10_000 }, (_, index) => `K${index}:1`).join(',');
    const required = Array.from({ length: 10_000 }, (_, index) => `R${index}`).join(',');
    for (const query of [`o=${overrides}`, `req=${required}`]) {
      const sent = probe.apiRequests('/api/v1/recommendations/query').length;
      await page.evaluate((url) => {
        history.pushState(null, '', url);
        window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
      }, `/results?country=${MARKET}&mode=PRICE&${query}`);
      await settle(page);
      const fired = probe.apiRequests('/api/v1/recommendations/query').length - sent;
      expect(fired, `${query.slice(0, 12)}…: ranking requests`).toBeLessThanOrEqual(2);
      const responsive = await Promise.race([
        page.evaluate(() => true),
        sleep(5_000).then(() => false),
      ]);
      expect(responsive, 'page still answers').toBe(true);
    }
    await open(page, `/results?country=${MARKET}`);
    await expect(main(page).getByRole('table', { name: 'Ranked products' })).toBeVisible();
    expect(probe.pageErrors, 'uncaught errors').toEqual([]);
    await page.context().close();
  });

  test('forcing the admin routes shows no admin data and every admin call is refused', async ({
    browser,
  }) => {
    const page = await userPage(browser);
    const probe = await watch(page);
    const adminAnswers: { path: string; status: number }[] = [];
    page.on('response', (response) => {
      const path = new URL(response.url()).pathname;
      if (path.startsWith('/api/v1/admin')) {
        adminAnswers.push({ path, status: response.status() });
      }
    });
    for (const path of ['/admin', '/admin/imports', '/admin/users', '/admin/promo-codes']) {
      await open(page, path);
      await settle(page);
      expect(new URL(page.url()).pathname, `${path}: guard`).not.toMatch(/^\/admin/);
      await expect(page.locator('body')).not.toContainText(e2eEnv().adminEmail);
    }
    expect(adminAnswers.filter((answer) => answer.status < 400)).toEqual([]);
    expect(probe.pageErrors).toEqual([]);

    const session = await apiLogin(account.email, account.password);
    for (const path of [
      '/admin/users',
      '/admin/promo-codes',
      '/admin/outbox/stats',
      '/admin/health',
    ]) {
      const response = await apiFetch(path, bearer(session.accessToken));
      expect(response.status, `GET ${path} as USER`).toBe(403);
    }
    await page.context().close();
  });

  test('a forged ADMIN role in /me reveals only chrome while the API answers 403', async ({
    browser,
  }) => {
    const page = await userPage(browser);
    const probe = await watch(page);
    const forged = (value: unknown): unknown => {
      if (Array.isArray(value)) {
        return value.map(forged);
      }
      if (value && typeof value === 'object') {
        const copy: Record<string, unknown> = {};
        for (const [key, entry] of Object.entries(value)) {
          copy[key] = key === 'role' ? 'ADMIN' : forged(entry);
        }
        return copy;
      }
      return value;
    };
    await page.route(/\/api\/v1\/(me|auth\/refresh|auth\/login)$/, async (route) => {
      const response = await route.fetch();
      const type = response.headers()['content-type'] ?? '';
      if (!response.ok() || !type.includes('json')) {
        await route.fulfill({ response });
        return;
      }
      await route.fulfill({ response, json: forged(await response.json()) });
    });
    const adminAnswers: { path: string; status: number; body: string }[] = [];
    page.on('response', (response) => {
      const path = new URL(response.url()).pathname;
      if (path.startsWith('/api/v1/admin')) {
        void response
          .text()
          .then((body) => adminAnswers.push({ path, status: response.status(), body }))
          .catch(() => undefined);
      }
    });

    for (const path of ['/admin/users', '/admin/promo-codes', '/admin/imports']) {
      await open(page, path);
      await settle(page);
      await expect(page.locator('body')).not.toContainText(e2eEnv().adminEmail);
    }
    expect(adminAnswers.length, 'admin API calls made by the forged UI').toBeGreaterThan(0);
    for (const answer of adminAnswers) {
      expect(answer.status, `${answer.path} for a forged role`).toBe(403);
      expect(answer.body).not.toContain('@');
    }
    const text = await page.locator('body').innerText();
    const emails = (text.match(/[\w.+-]+@[\w-]+\.[\w.]+/g) ?? []).filter(
      (email) => email !== account.email,
    );
    expect(emails, 'other accounts shown to a forged admin').toEqual([]);
    expect(probe.pageErrors).toEqual([]);
    await page.context().close();
  });
});

import { type Page, type Response, expect, test } from '@playwright/test';

import { header, main, open, useLanguage } from '../support/app';
import { e2eEnv } from '../support/env';
import { linkIn, waitForMail } from '../support/mailpit';
import {
  type Account,
  type Probe,
  apiFetch,
  apiLogin,
  apiPost,
  adoptSession,
  fragmentToken,
  marketCatalog,
  registerAccount,
  uiLogin,
  uiLogout,
  verificationLink,
  watch,
} from './support/attack';

/**
 * Token handling in the browser (project specification §8.1, §8.2, §10.4, §15.7 case 11).
 *
 * The access token lives in memory only, the refresh token only in the HttpOnly
 * `vd_refresh` cookie, and the single-use e-mail tokens only in the URL fragment until the page
 * removes them. Nothing of that may be readable from storage, cookies, the address bar,
 * history, the referrer or any request URL.
 */

const NEW_PASSWORD = 'Copper-Meadow-Lantern-77';

/** Records the address bar at the moment every fetch/XHR is sent. */
async function recordAddressAtSend(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const log: string[] = [];
    (window as unknown as { __sendLog: string[] }).__sendLog = log;
    const originalFetch = window.fetch.bind(window);
    window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
      const target = input instanceof Request ? input.url : String(input);
      log.push(`${location.href} -> fetch ${target}`);
      return originalFetch(input, init);
    };
    const originalOpen = XMLHttpRequest.prototype.open;
    const targets = new WeakMap<XMLHttpRequest, string>();
    XMLHttpRequest.prototype.open = function open(
      this: XMLHttpRequest,
      method: string,
      url: string | URL,
      ...rest: unknown[]
    ) {
      targets.set(this, `${method} ${String(url)}`);
      return (originalOpen as (...args: unknown[]) => void).call(this, method, url, ...rest);
    } as typeof XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function send(
      this: XMLHttpRequest,
      body?: Document | XMLHttpRequestBodyInit | null,
    ) {
      log.push(`${location.href} -> xhr ${targets.get(this) ?? ''}`);
      return originalSend.call(this, body);
    };
  });
}

async function sendLog(page: Page): Promise<string[]> {
  return page.evaluate(() => (window as unknown as { __sendLog?: string[] }).__sendLog ?? []);
}

/** Every place script or the user could read a token from, as one string per source. */
async function readableState(page: Page): Promise<Record<string, string>> {
  return page.evaluate(async () => {
    const dump = (storage: Storage): string =>
      JSON.stringify(
        Array.from({ length: storage.length }, (_, index) => {
          const key = storage.key(index) ?? '';
          return [key, storage.getItem(key)];
        }),
      );
    const databases: string[] = [];
    for (const info of await indexedDB.databases()) {
      if (!info.name) {
        continue;
      }
      const name = info.name;
      databases.push(
        await new Promise<string>((resolve) => {
          const request = indexedDB.open(name);
          request.onerror = () => resolve(`${name}: unreadable`);
          request.onsuccess = () => {
            const db = request.result;
            const stores = Array.from(db.objectStoreNames);
            if (stores.length === 0) {
              db.close();
              resolve(`${name}: empty`);
              return;
            }
            const transaction = db.transaction(stores, 'readonly');
            const values: unknown[] = [];
            for (const store of stores) {
              const all = transaction.objectStore(store).getAll();
              all.onsuccess = () => values.push(all.result);
            }
            transaction.oncomplete = () => {
              db.close();
              resolve(`${name}: ${JSON.stringify(values)}`);
            };
          };
        }),
      );
    }
    const cacheNames = 'caches' in window ? await caches.keys() : [];
    return {
      localStorage: dump(localStorage),
      sessionStorage: dump(sessionStorage),
      indexedDB: databases.join('\n'),
      cacheStorage: cacheNames.join(','),
      cookie: document.cookie,
      href: location.href,
      windowName: window.name,
      historyState: JSON.stringify(history.state),
    };
  });
}

function tokensOf(response: Response, sink: Set<string>): void {
  const url = response.url();
  if (!/\/api\/v1\/auth\/(login|refresh)$/.test(url) || response.status() !== 200) {
    return;
  }
  void response
    .json()
    .then((body: { accessToken?: string }) => {
      if (body.accessToken) {
        sink.add(body.accessToken);
      }
    })
    .catch(() => undefined);
}

/** The bootstrap admin is an ordinary signed-in user for these checks. */
function adminAccount(): Account {
  const env = e2eEnv();
  return { email: env.adminEmail, password: env.adminPassword };
}

/** Opens an e-mail link in a fresh page and checks where its token can be seen. */
async function openTokenLink(
  page: Page,
  link: string,
  heading: string,
): Promise<{ token: string; probe: Probe }> {
  const token = fragmentToken(link);
  await recordAddressAtSend(page);
  const probe = await watch(page);
  await page.goto(link);
  await expect(main(page).getByRole('heading', { name: heading })).toBeVisible();
  expect(page.url(), 'address bar').not.toContain(token);
  for (const [source, value] of Object.entries(await readableState(page))) {
    expect(value, `${source} on the token page`).not.toContain(token);
  }
  return { token, probe };
}

async function expectTokenNeverSent(page: Page, probe: Probe, token: string): Promise<void> {
  const log = await sendLog(page);
  expect(log.length, 'fetch/XHR calls observed').toBeGreaterThan(0);
  expect
    .soft(
      log.filter((entry) => entry.split(' -> ')[0]?.includes(token)),
      'requests sent while the token was still in the address bar',
    )
    .toEqual([]);
  for (const request of probe.requests) {
    expect(request.url(), 'request URL').not.toContain(token);
    expect((await request.allHeaders())['referer'] ?? '', 'Referer').not.toContain(token);
  }
}

test('e-mail tokens stay out of the address bar, history, referrer and request URLs', async ({
  browser,
}) => {
  const account = await registerAccount('tokens');

  const verifyContext = await browser.newContext();
  const verifyPage = await verifyContext.newPage();
  const verify = await openTokenLink(
    verifyPage,
    await verificationLink(account.email),
    'E-mail verified',
  );
  await expectTokenNeverSent(verifyPage, verify.probe, verify.token);
  const posts = verify.probe.apiRequests('/api/v1/auth/verify-email');
  expect(posts, 'the token is posted once, in the body').toHaveLength(1);
  expect(posts[0]?.postData() ?? '').toContain(verify.token);
  await verifyPage.goBack();
  expect(verifyPage.url(), 'history entry before').not.toContain(verify.token);
  await verifyPage.goForward();
  expect(verifyPage.url(), 'history entry of the verify page').not.toContain(verify.token);
  await verifyPage.evaluate(() => location.assign('/privacy'));
  await verifyPage.waitForURL(/\/privacy$/);
  const referrer = await verifyPage.evaluate(() => document.referrer);
  expect(referrer, 'document.referrer of the next page').not.toContain(verify.token);
  expect(referrer, 'no-referrer on /auth/verify').toBe('');
  await verifyContext.close();

  const forgot = await apiFetch('/auth/forgot-password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: account.email }),
  });
  expect(forgot.status).toBe(202);
  const resetContext = await browser.newContext();
  const resetPage = await resetContext.newPage();
  const reset = await openTokenLink(
    resetPage,
    linkIn(await waitForMail(account.email, /password/i), '/auth/reset'),
    'Choose a new password',
  );
  await main(resetPage).getByLabel('New password', { exact: true }).fill(NEW_PASSWORD);
  await main(resetPage).getByLabel('Repeat password', { exact: true }).fill(NEW_PASSWORD);
  await main(resetPage).getByRole('button', { name: 'Save password' }).click();
  await expect(resetPage).toHaveURL(/\/auth\/login/);
  await expectTokenNeverSent(resetPage, reset.probe, reset.token);
  await resetPage.goBack();
  expect(resetPage.url(), 'history entry of the reset page').not.toContain(reset.token);
  await resetContext.close();
});

test('after login no token is readable by script, storage or the address bar', async ({ page }) => {
  test.setTimeout(240_000);
  const accessTokens = new Set<string>();
  page.on('response', (response) => tokensOf(response, accessTokens));
  await open(page, '/auth/login');
  await uiLogin(page, adminAccount());
  await page.reload();
  await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeVisible();
  await open(page, '/account');

  const cookies = await page.context().cookies();
  const refresh = cookies.find((cookie) => cookie.name === 'vd_refresh');
  expect(refresh, 'vd_refresh cookie').toBeDefined();
  expect(refresh?.httpOnly, 'HttpOnly').toBe(true);
  expect(refresh?.sameSite, 'SameSite').toBe('Strict');
  expect(refresh?.path, 'Path').toBe('/api/v1/auth');
  expect(accessTokens.size, 'access tokens seen on the wire').toBeGreaterThan(0);

  const secrets = [...accessTokens, refresh?.value ?? 'missing-refresh'];
  const state = await readableState(page);
  for (const [source, value] of Object.entries(state)) {
    for (const secret of secrets) {
      expect(value, `${source} holds no access or refresh token`).not.toContain(secret);
    }
    expect(value, `${source} holds no JWT`).not.toMatch(/eyJ[\w-]{8,}\.[\w-]{8,}/);
  }
  expect(state['cookie'], 'document.cookie').not.toContain('vd_refresh');
});

/** Saves the first TR product for `account` and returns its name. */
async function saveFirstTrProduct(account: Account): Promise<string> {
  const session = await apiLogin(account.email, account.password);
  const [first] = await marketCatalog('TR');
  if (!first) {
    throw new Error('The TR market has no products: run scripts/seed.sh');
  }
  const saved = await apiPost('/me/saved-products', session.accessToken, {
    productId: first.id,
    countryCode: 'TR',
  });
  expect([200, 201]).toContain(saved.status);
  return first.name;
}

test('after logout a back-navigation shows no private data and calls are anonymous', async ({
  page,
}) => {
  test.setTimeout(240_000);
  const account = adminAccount();
  const savedName = await saveFirstTrProduct(account);
  await open(page, '/auth/login');
  await uiLogin(page, account);
  await open(page, '/?country=TR');
  await useLanguage(page, 'en');
  await open(page, '/account/saved');
  await expect(main(page).getByRole('link', { name: savedName }).first()).toBeVisible();

  await uiLogout(page);
  await page.waitForURL((url) => !url.pathname.startsWith('/account'));
  const afterLogout: { url: string; auth: string | undefined }[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/')) {
      afterLogout.push({ url: request.url(), auth: request.headers()['authorization'] });
    }
  });
  await page.goBack();
  // Back may land on the saved-list URL; the guard must send it to the login page.
  await expect(page).not.toHaveURL(/\/account\/saved/);
  await expect(main(page)).not.toContainText(savedName);
  await expect(header(page).locator('a[href="/auth/login"]')).toBeVisible();
  await page.reload();
  await expect(header(page).locator('a[href="/auth/login"]')).toBeVisible();
  await page.goBack();
  await expect(main(page)).not.toContainText(savedName);
  expect(afterLogout.length, 'API calls after logout').toBeGreaterThan(0);
  expect(afterLogout.filter((entry) => entry.auth !== undefined)).toEqual([]);
});

test('a second user logging in in another tab never shows the first user data', async ({
  browser,
}) => {
  test.setTimeout(240_000);
  const first = adminAccount();
  const other = await registerAccount('tokens-other');
  const savedName = await saveFirstTrProduct(first);
  const context = await browser.newContext();
  await adoptSession(context, await apiLogin(first.email, first.password));

  const tab1 = await context.newPage();
  await open(tab1, '/?country=TR');
  await useLanguage(tab1, 'en');
  await open(tab1, '/account/saved');
  await expect(main(tab1).getByRole('link', { name: savedName }).first()).toBeVisible();

  const tab2 = await context.newPage();
  await open(tab2, '/auth/login');
  if (!(await main(tab2).getByLabel('E-mail').isVisible())) {
    await uiLogout(tab2);
    await open(tab2, '/auth/login');
  }
  await uiLogin(tab2, other);
  await useLanguage(tab1, 'en');

  // Next action in the first tab: move to another account page and back.
  await main(tab1).getByRole('link', { name: 'Alerts' }).first().click();
  await main(tab1).getByRole('link', { name: 'Saved products' }).first().click();
  await expect(tab1).toHaveURL(/\/account\/saved/);
  await expect(main(tab1)).not.toContainText(savedName);
  await main(tab1).getByRole('link', { name: 'Profile and plan' }).first().click();
  await expect(main(tab1)).toContainText(other.email);
  await expect(tab1.locator('body')).not.toContainText(first.email);
  await context.close();
});

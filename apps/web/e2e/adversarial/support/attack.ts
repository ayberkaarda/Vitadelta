import { type BrowserContext, type Page, type Request, expect } from '@playwright/test';

import { PASSWORD, header, main, uniqueEmail, useLanguage } from '../../support/app';
import { e2eEnv } from '../../support/env';
import { linkIn, waitForMail } from '../../support/mailpit';

/**
 * Hostile display strings. Each one sets `window.__xss` if it is ever evaluated as markup,
 * script, a URL or an Angular template expression.
 */
export const PAYLOADS = {
  script: '<script>window.__xss=1</script>',
  img: '<img src=x onerror="window.__xss=1">',
  svg: '"><svg onload=window.__xss=1>',
  jsUrl: 'javascript:window.__xss=1',
  template: "{{constructor.constructor('window.__xss=1')()}}",
} as const;

export const ALL_PAYLOADS: readonly string[] = Object.values(PAYLOADS);

/** Market of the hostile catalog fixtures (no other flow of the e2e suites uses it). */
export const COUNTRY = 'GB';
export const JOINED = ALL_PAYLOADS.join(' ');
export const CHECKED_AT = '2026-11-24T09:00:00Z';

/** A synthetic product of {@link COUNTRY} whose display strings are hostile. */
export function product(code: string, name: string, retailer: string, price: number): object {
  return {
    code,
    brand: JOINED,
    name,
    dosage_form: 'TABLET',
    units_per_container: 100,
    units_per_serving: 1,
    servings_per_day: 1,
    source: 'Synthetic adversarial fixture (fictional brand, hostile display strings)',
    synthetic: true,
    nutrients: [
      { nutrient_code: 'VIT_C', amount_per_serving: 80, unit: 'mg', form_label: JOINED },
      { nutrient_code: 'VIT_D', amount_per_serving: 5, unit: 'mcg', form_label: PAYLOADS.svg },
      { nutrient_code: 'ZINC', amount_per_serving: 10, unit: 'mg', form_label: PAYLOADS.img },
      { nutrient_code: 'MAGNESIUM', amount_per_serving: 100, unit: 'mg' },
      { nutrient_code: 'VIT_B12', amount_per_serving: 2.5, unit: 'mcg' },
      { nutrient_code: 'IRON', amount_per_serving: 8, unit: 'mg', form_label: PAYLOADS.jsUrl },
      { nutrient_code: 'SELENIUM', amount_per_serving: 50, unit: 'mcg' },
      { nutrient_code: 'IODINE', amount_per_serving: 100, unit: 'mcg' },
    ],
    availability: [{ country_code: COUNTRY, status: 'AVAILABLE', note: PAYLOADS.template }],
    offers: [
      {
        country_code: COUNTRY,
        retailer,
        price,
        currency: 'GBP',
        in_stock: true,
        url: `https://adversarial-shop.example/gb/p/${code.toLowerCase()}`,
        checked_at: CHECKED_AT,
      },
    ],
  };
}

export function sleep(ms: number): Promise<void> {
  return new Promise((done) => setTimeout(done, ms));
}

/**
 * `fetch` against the public API through nginx. A `429 rate_limited` answer (the per-IP limits
 * are shared by every test of the run) is waited out with its `Retry-After` and retried.
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  for (let attempt = 0; ; attempt += 1) {
    const response = await fetch(`${e2eEnv().apiBase}${path}`, init);
    if (response.status !== 429 || attempt >= 3) {
      return response;
    }
    const seconds = Number(response.headers.get('retry-after') ?? '10');
    await sleep((Math.min(Number.isFinite(seconds) ? seconds : 10, 90) + 1) * 1000);
  }
}

function json(body: unknown, token?: string): RequestInit {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return { method: 'POST', headers, body: JSON.stringify(body) };
}

export function bearer(token: string): RequestInit {
  return { headers: { Authorization: `Bearer ${token}` } };
}

/** Result of an API login: the access token, the refresh cookie value and the user id. */
export interface ApiSession {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly userId: string;
}

export async function apiLogin(email: string, password: string): Promise<ApiSession> {
  const response = await apiFetch('/auth/login', json({ email, password }));
  if (!response.ok) {
    throw new Error(`POST /auth/login for ${email} answered ${response.status}`);
  }
  const cookie = response.headers.getSetCookie().find((line) => line.startsWith('vd_refresh='));
  if (!cookie) {
    throw new Error('POST /auth/login set no vd_refresh cookie');
  }
  const body = (await response.json()) as { accessToken: string; user: { id: string } };
  return {
    accessToken: body.accessToken,
    refreshToken: (cookie.split(';')[0] ?? '').slice('vd_refresh='.length),
    userId: body.user.id,
  };
}

let adminCache: { token: string; at: number } | null = null;

/** Access token of the bootstrap admin (re-used for ten minutes of its fifteen). */
export async function adminToken(): Promise<string> {
  if (adminCache && Date.now() - adminCache.at < 10 * 60_000) {
    return adminCache.token;
  }
  const env = e2eEnv();
  const session = await apiLogin(env.adminEmail, env.adminPassword);
  adminCache = { token: session.accessToken, at: Date.now() };
  return session.accessToken;
}

/** Gives `context` the refresh cookie of `session`, as a browser that logged in would hold. */
export async function adoptSession(context: BrowserContext, session: ApiSession): Promise<void> {
  await context.addCookies([
    {
      name: 'vd_refresh',
      value: session.refreshToken,
      domain: new URL(e2eEnv().baseURL).hostname,
      path: '/api/v1/auth',
      httpOnly: true,
      sameSite: 'Strict',
      secure: false,
    },
  ]);
}

export interface Account {
  readonly email: string;
  readonly password: string;
}

/** Registers a new account (in English, market GB) without verifying it. */
export async function registerAccount(role: string): Promise<Account> {
  const email = uniqueEmail(`adv-${role}`);
  const response = await apiFetch(
    '/auth/register',
    json({ email, password: PASSWORD, locale: 'en', countryCode: 'GB' }),
  );
  if (response.status !== 202) {
    throw new Error(`POST /auth/register answered ${response.status}`);
  }
  return { email, password: PASSWORD };
}

/** The `/auth/verify#token=…` link of the verification mail sent to `email`. */
export async function verificationLink(email: string): Promise<string> {
  return linkIn(await waitForMail(email, /Confirm your VitaDelta/), '/auth/verify');
}

export function fragmentToken(link: string): string {
  const token = new URLSearchParams(new URL(link).hash.slice(1)).get('token');
  if (!token) {
    throw new Error(`No #token= in ${link}`);
  }
  return token;
}

/** Registers an account and verifies its e-mail through the API (token from Mailpit). */
export async function registerVerifiedAccount(role: string): Promise<Account> {
  const account = await registerAccount(role);
  const token = fragmentToken(await verificationLink(account.email));
  const response = await apiFetch('/auth/verify-email', json({ token }));
  if (response.status !== 204) {
    throw new Error(`POST /auth/verify-email answered ${response.status}`);
  }
  return account;
}

export interface ImportJob {
  readonly id: string;
  readonly status: string;
  readonly errors: readonly { rowNumber: number; code: string; message: string }[];
}

async function finishedJob(token: string, id: string): Promise<ImportJob> {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const response = await apiFetch(`/admin/imports/${id}`, bearer(token));
    const job = (await response.json()) as ImportJob;
    if (job.status === 'SUCCEEDED' || job.status === 'FAILED') {
      return job;
    }
    await sleep(500);
  }
  throw new Error(`Import job ${id} did not finish within 60 s`);
}

export async function importProducts(document: unknown): Promise<ImportJob> {
  const token = await adminToken();
  const response = await apiFetch('/admin/imports/products', json(document, token));
  if (response.status !== 202) {
    throw new Error(`POST /admin/imports/products answered ${response.status}`);
  }
  return finishedJob(token, ((await response.json()) as { id: string }).id);
}

export async function importOffersCsv(csv: string, fileName: string): Promise<ImportJob> {
  const token = await adminToken();
  const form = new FormData();
  form.append('file', new Blob([csv], { type: 'text/csv' }), fileName);
  const response = await apiFetch('/admin/imports/offers', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
  if (response.status !== 202) {
    throw new Error(`POST /admin/imports/offers answered ${response.status}`);
  }
  return finishedJob(token, ((await response.json()) as { id: string }).id);
}

export async function apiPost(path: string, token: string, body: unknown): Promise<Response> {
  return apiFetch(path, json(body, token));
}

/** Product id of `code` in `country` from the public catalog. */
export async function productIdOf(code: string, country: string): Promise<string> {
  const response = await apiFetch(
    `/products?country=${country}&q=${encodeURIComponent(code)}&pageSize=100`,
  );
  const body = (await response.json()) as { items: { id: string; code: string }[] };
  const item = body.items.find((entry) => entry.code === code);
  if (!item) {
    throw new Error(`Product ${code} is not in market ${country}`);
  }
  return item.id;
}

/** Every product (id, code, name) of a market from the public catalog. */
export async function marketCatalog(
  country: string,
): Promise<{ id: string; code: string; name: string }[]> {
  const items: { id: string; code: string; name: string }[] = [];
  for (let page = 1; page < 50; page += 1) {
    const response = await apiFetch(`/products?country=${country}&page=${page}&pageSize=100`);
    const body = (await response.json()) as { items: { id: string; code: string; name: string }[] };
    items.push(...body.items);
    if (body.items.length < 100) {
      break;
    }
  }
  return items;
}

/**
 * Everything a page did that an attacker cares about: dialogs, uncaught errors, CSP violations
 * (collected in the page through a `securitypolicyviolation` listener) and every request.
 */
export class Probe {
  readonly dialogs: string[] = [];
  readonly pageErrors: string[] = [];
  readonly requests: Request[] = [];

  constructor(readonly page: Page) {
    page.on('dialog', (dialog) => {
      this.dialogs.push(`${dialog.type()}: ${dialog.message()}`);
      void dialog.dismiss().catch(() => undefined);
    });
    page.on('pageerror', (error) => this.pageErrors.push(error.message));
    page.on('request', (request) => this.requests.push(request));
  }

  apiRequests(path?: string): Request[] {
    return this.requests.filter((request) => {
      const url = new URL(request.url());
      return url.pathname.startsWith('/api/') && (!path || url.pathname.startsWith(path));
    });
  }

  async cspViolations(): Promise<string[]> {
    return this.page.evaluate(
      () => (window as unknown as { __cspViolations?: string[] }).__cspViolations ?? [],
    );
  }
}

/** Starts watching `page`; call before the first navigation. */
export async function watch(page: Page): Promise<Probe> {
  const probe = new Probe(page);
  await page.addInitScript(() => {
    const target = window as unknown as { __cspViolations: string[] };
    target.__cspViolations = [];
    document.addEventListener('securitypolicyviolation', (event) => {
      target.__cspViolations.push(`${event.violatedDirective} ${event.blockedURI}`);
    });
  });
  return probe;
}

/**
 * Asserts that no hostile string became live: `window.__xss` is unset, no dialog opened, the
 * DOM holds no inline script, no `onerror`/`onload` handler element, no `img[src=x]`, no frame
 * or plugin element, no `javascript:`/`data:`/`vbscript:` link, no CSP violation was needed,
 * and every string of `visible` is shown as text.
 */
export async function expectInert(
  probe: Probe,
  label: string,
  visible: readonly string[] = [],
): Promise<void> {
  const state = await probe.page.evaluate(() => {
    const own = window as unknown as Record<string, unknown>;
    const handlers = [...document.querySelectorAll('*')].filter((element) =>
      element.getAttributeNames().some((name) => /^on/i.test(name)),
    );
    const injected = [
      ...document.querySelectorAll(
        'script:not([src]), img[src="x"], svg[onload], iframe, object, embed, base:not([href="/"])',
      ),
      ...handlers,
    ];
    const navigating = [...document.querySelectorAll('[href], [action], [formaction]')]
      .flatMap((element) =>
        ['href', 'action', 'formaction', 'xlink:href'].map(
          (name) => element.getAttribute(name) ?? '',
        ),
      )
      .filter((value) => /^\s*(javascript|data|vbscript):/i.test(value));
    const loading = [...document.querySelectorAll('[src]')]
      .map((element) => element.getAttribute('src') ?? '')
      .filter((value) => /^\s*(javascript:|vbscript:|data:text\/html)/i.test(value));
    const links = [...navigating, ...loading];
    return {
      xss: own['__xss'] === undefined ? 'undefined' : String(own['__xss']),
      injected: injected.map((element) => element.outerHTML.slice(0, 160)),
      links,
      csp: (own['__cspViolations'] as string[] | undefined) ?? [],
      text: document.body.innerText,
    };
  });
  expect(state.xss, `${label}: window.__xss`).toBe('undefined');
  expect(probe.dialogs, `${label}: dialogs`).toEqual([]);
  expect(state.injected, `${label}: injected elements`).toEqual([]);
  expect(state.links, `${label}: script-capable URLs`).toEqual([]);
  expect(state.csp, `${label}: CSP violations (escaping must not rely on CSP)`).toEqual([]);
  expect(probe.pageErrors, `${label}: uncaught errors`).toEqual([]);
  for (const text of visible) {
    expect.soft(state.text, `${label}: shows the hostile string as text`).toContain(text);
  }
}

/** Logs in through the login form; a `429` is waited out with its `Retry-After`. */
export async function uiLogin(page: Page, account: Account): Promise<void> {
  for (let attempt = 0; attempt < 4; attempt += 1) {
    await useLanguage(page, 'en');
    await main(page).getByLabel('E-mail').fill(account.email);
    await main(page).getByLabel('Password').fill(account.password);
    const answer = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/auth/login') && response.request().method() === 'POST',
    );
    await main(page).getByRole('button', { name: 'Log in' }).click();
    const response = await answer;
    if (response.status() !== 429) {
      expect(response.status(), 'login status').toBe(200);
      await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeVisible();
      return;
    }
    const seconds = Number(response.headers()['retry-after'] ?? '10');
    await sleep((Math.min(Number.isFinite(seconds) ? seconds : 10, 90) + 1) * 1000);
  }
  throw new Error('Login stayed rate limited');
}

/** Opens the account menu and logs out. */
export async function uiLogout(page: Page): Promise<void> {
  await header(page)
    .getByRole('button', { name: /^Account menu/ })
    .click();
  await page
    .getByRole('menuitem', { name: 'Log out' })
    .or(page.getByRole('button', { name: 'Log out' }))
    .click();
  await expect(header(page).locator('a[href="/auth/login"]')).toBeVisible();
}

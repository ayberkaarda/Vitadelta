import { type Server, createServer } from 'node:http';
import type { AddressInfo } from 'node:net';

import { type BrowserContext, type Page, expect, test } from '@playwright/test';

import { header, open } from '../support/app';
import { e2eEnv } from '../support/env';
import { adoptSession, apiLogin } from './support/attack';

/**
 * Cross-site request forgery and framing from an attacker page (project specification §8.1
 * CSRF and same-origin rules, §15.2 `frame-ancestors`/`X-Frame-Options`, §15.7 case 4).
 *
 * A tiny local server plays the attacker. It is reached as `127.0.0.1:<port>` (another site:
 * SameSite cookies stay home) and as `localhost:<port>` (the same site on another port:
 * SameSite does not help, so the `X-Requested-With` requirement, the Origin check and CORS must).
 * The victim browser holds a valid refresh cookie for the app the whole time.
 */

let server: Server;
let port = 0;

const ATTACKER_PAGE = `<!doctype html><html><head><meta charset="utf-8"><title>Prize</title></head>
<body><h1>You won</h1><iframe name="sink" title="sink"></iframe></body></html>`;

function framingPage(target: string): string {
  return `<!doctype html><html><head><meta charset="utf-8"><title>Frame</title></head>
<body><iframe id="victim" title="victim" src="${target}" width="800" height="600"></iframe></body></html>`;
}

test.beforeAll(async () => {
  server = createServer((request, response) => {
    const url = new URL(request.url ?? '/', 'http://attacker.invalid');
    const target = url.searchParams.get('frame');
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    response.end(target ? framingPage(target) : ATTACKER_PAGE);
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  port = (server.address() as AddressInfo).port;
});

test.afterAll(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

const ATTACKERS = [
  { label: 'cross-site attacker', host: () => `http://127.0.0.1:${port}` },
  { label: 'same-site attacker on another port', host: () => `http://localhost:${port}` },
] as const;

async function victim(context: BrowserContext): Promise<Page> {
  const env = e2eEnv();
  await adoptSession(context, await apiLogin(env.adminEmail, env.adminPassword));
  const page = await context.newPage();
  await open(page, '/');
  await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeVisible();
  return page;
}

async function refreshCookie(context: BrowserContext): Promise<string | undefined> {
  return (await context.cookies()).find((cookie) => cookie.name === 'vd_refresh')?.value;
}

/** Status codes of the app API responses the attacker page caused (0 = never answered). */
function apiAnswers(page: Page): { method: string; path: string; status: number }[] {
  const answers: { method: string; path: string; status: number }[] = [];
  page.on('response', (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/') && url.port === new URL(e2eEnv().baseURL).port) {
      answers.push({
        method: response.request().method(),
        path: url.pathname,
        status: response.status(),
      });
    }
  });
  return answers;
}

for (const attacker of ATTACKERS) {
  test(`${attacker.label} cannot refresh, log out, log in or read API responses`, async ({
    browser,
  }) => {
    test.setTimeout(180_000);
    const context = await browser.newContext();
    const app = await victim(context);
    const before = await refreshCookie(context);
    expect(before, 'victim refresh cookie').toBeDefined();

    const evil = await context.newPage();
    const answers = apiAnswers(evil);
    await evil.goto(`${attacker.host()}/`);
    const api = e2eEnv().apiBase;

    const fetchResults = await evil.evaluate(async (base) => {
      const attempt = async (label: string, url: string, init: RequestInit) => {
        try {
          const response = await fetch(url, init);
          let body = '';
          try {
            body = await response.text();
          } catch {
            body = '';
          }
          return { label, outcome: `status ${response.status} type ${response.type}`, body };
        } catch (error) {
          return { label, outcome: `blocked ${String(error)}`, body: '' };
        }
      };
      return [
        await attempt('refresh with CSRF header (preflighted)', `${base}/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-Requested-With': 'vitadelta', 'Content-Type': 'application/json' },
          body: '{}',
        }),
        await attempt('refresh as a simple request', `${base}/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          mode: 'no-cors',
          headers: { 'Content-Type': 'text/plain' },
          body: '{}',
        }),
        await attempt('logout with CSRF header (preflighted)', `${base}/auth/logout`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-Requested-With': 'vitadelta', 'Content-Type': 'application/json' },
          body: '{}',
        }),
        await attempt('logout as a simple request', `${base}/auth/logout`, {
          method: 'POST',
          credentials: 'include',
          mode: 'no-cors',
          headers: { 'Content-Type': 'text/plain' },
          body: '{}',
        }),
        await attempt('read the profile', `${base}/me`, { credentials: 'include' }),
        await attempt('read the session quota', `${base}/session/quota`, {
          credentials: 'include',
        }),
        await attempt('read the public catalog', `${base}/products?country=TR`, {}),
      ];
    }, api);

    for (const result of fetchResults) {
      if (result.outcome.startsWith('status')) {
        // Only opaque no-cors answers may come back; their body is unreadable.
        expect(result.outcome, `${result.label}: readable response`).toContain('type opaque');
        expect(result.body, `${result.label}: body`).toBe('');
      }
    }

    for (const path of ['/auth/logout', '/auth/refresh']) {
      await evil.evaluate(
        ({ action }) => {
          const form = document.createElement('form');
          form.method = 'POST';
          form.action = action;
          form.target = 'sink';
          form.enctype = 'text/plain';
          const input = document.createElement('input');
          input.name = '{"a":"';
          input.value = '"}';
          form.appendChild(input);
          document.body.appendChild(form);
          form.submit();
        },
        { action: `${api}${path}` },
      );
      await evil.waitForTimeout(1_000);
    }

    const env = e2eEnv();
    await evil.evaluate(
      ({ action, email, password }) => {
        const form = document.createElement('form');
        form.method = 'POST';
        form.action = action;
        form.target = 'sink';
        for (const [name, value] of [
          ['email', email],
          ['password', password],
        ]) {
          const input = document.createElement('input');
          input.name = name ?? '';
          input.value = value ?? '';
          form.appendChild(input);
        }
        document.body.appendChild(form);
        form.submit();
      },
      { action: `${api}/auth/login`, email: env.adminEmail, password: env.adminPassword },
    );
    await evil.waitForTimeout(1_000);

    const stateChanging = answers.filter((answer) =>
      /\/auth\/(logout|refresh|login)$/.test(answer.path),
    );
    for (const answer of stateChanging) {
      expect(
        answer.status,
        `${answer.method} ${answer.path} from the attacker`,
      ).toBeGreaterThanOrEqual(400);
    }
    expect(await refreshCookie(context), 'refresh cookie untouched by the attacker').toBe(before);

    await app.reload();
    await expect(
      header(app).getByRole('button', { name: /^Account menu/ }),
      'the victim is still signed in',
    ).toBeVisible();
    await context.close();
  });
}

for (const attacker of ATTACKERS) {
  test(`${attacker.label} cannot use the cookie-less refresh of an anonymous visitor`, async ({
    browser,
  }) => {
    const context = await browser.newContext();
    const evil = await context.newPage();
    const answers = apiAnswers(evil);
    await evil.goto(`${attacker.host()}/`);
    const api = e2eEnv().apiBase;

    const results = await evil.evaluate(async (url) => {
      const attempt = async (label: string, init: RequestInit) => {
        try {
          const response = await fetch(url, init);
          return {
            label,
            status: response.status,
            type: response.type,
            body: await response.text(),
          };
        } catch (error) {
          return { label, status: -1, type: `blocked ${String(error)}`, body: '' };
        }
      };
      return [
        await attempt('with the CSRF header (preflighted)', {
          method: 'POST',
          headers: { 'X-Requested-With': 'vitadelta', 'Content-Type': 'application/json' },
          body: '{}',
        }),
        await attempt('with the CSRF header and credentials', {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-Requested-With': 'vitadelta' },
        }),
        await attempt('as a simple cors request', {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: '{}',
        }),
        await attempt('as a no-cors request', {
          method: 'POST',
          mode: 'no-cors',
          headers: { 'Content-Type': 'text/plain' },
          body: '{}',
        }),
      ];
    }, `${api}/auth/refresh`);

    for (const result of results) {
      // The attacker learns nothing: the call is blocked or its answer is opaque.
      expect(result.type === 'opaque' || result.type.startsWith('blocked'), result.label).toBe(
        true,
      );
      expect(result.body, `${result.label}: readable body`).toBe('');
    }
    // A request that reached the API without the CSRF header was refused there as well; the
    // preflighted ones never left the browser.
    const posts = answers.filter((answer) => answer.method === 'POST');
    for (const answer of posts) {
      expect(answer.status, `POST ${answer.path} from the attacker`).toBe(403);
    }
    const preflights = answers.filter((answer) => answer.method === 'OPTIONS');
    for (const answer of preflights) {
      expect(answer.status, 'preflight from the attacker').toBeGreaterThanOrEqual(400);
    }
    expect(
      (await context.cookies()).map((cookie) => cookie.name),
      'cookies after the attack',
    ).not.toContain('vd_refresh');
    await context.close();
  });
}

test('the app cannot be framed by another origin', async ({ browser }) => {
  const context = await browser.newContext();
  await victim(context);
  for (const attacker of ATTACKERS) {
    for (const path of ['/', '/account', '/auth/login', '/auth/verify']) {
      const evil = await context.newPage();
      const blocked: string[] = [];
      evil.on('console', (message) => {
        if (/frame-ancestors|X-Frame-Options/i.test(message.text())) {
          blocked.push(message.text());
        }
      });
      await evil.goto(
        `${attacker.host()}/?frame=${encodeURIComponent(`${e2eEnv().baseURL}${path}`)}`,
      );
      await evil.waitForTimeout(1_500);
      const child = evil.frames().find((frame) => frame !== evil.mainFrame());
      const label = `${attacker.label} framing ${path}`;
      expect(child, `${label}: iframe present`).toBeDefined();
      expect(child?.url() ?? '', `${label}: frame did not load the app`).not.toMatch(
        new RegExp(`^${e2eEnv().baseURL}`),
      );
      const shells = await child?.locator('vd-shell').count();
      expect(shells ?? 0, `${label}: app shell rendered inside the frame`).toBe(0);
      expect(blocked.length, `${label}: the browser reports the framing block`).toBeGreaterThan(0);
      await evil.close();
    }
  }
  await context.close();
});

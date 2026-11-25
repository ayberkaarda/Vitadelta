import { type APIRequestContext, expect, test } from '@playwright/test';

import { open } from '../support/app';
import { e2eEnv } from '../support/env';
import { watch } from './support/attack';

/**
 * Transport headers, CSP and the served bundle (project specification §15.2, §15.4, §15.7
 * cases 11 and 18, web part).
 */

const WEB_CSP =
  "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
  "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; " +
  "frame-ancestors 'none'";
const API_CSP = "default-src 'none'; frame-ancestors 'none'";
const PERMISSIONS = 'camera=(), microphone=(), geolocation=()';

function expectHardened(
  headers: Record<string, string>,
  url: string,
  kind: 'web' | 'api' | 'token-page',
): void {
  expect(headers['x-content-type-options'], `${url}: nosniff`).toBe('nosniff');
  expect(headers['x-frame-options'], `${url}: X-Frame-Options`).toBe('DENY');
  expect(headers['permissions-policy'], `${url}: Permissions-Policy`).toBe(PERMISSIONS);
  expect(headers['strict-transport-security'], `${url}: no HSTS on plain HTTP`).toBeUndefined();
  const csp = headers['content-security-policy'];
  expect(csp, `${url}: exactly one CSP`).toBeDefined();
  expect(csp?.includes('\n'), `${url}: duplicated CSP header`).toBe(false);
  if (kind === 'api') {
    expect(csp, `${url}: API CSP`).toBe(API_CSP);
    expect(headers['referrer-policy'], `${url}: API Referrer-Policy`).toBe('no-referrer');
  } else {
    expect(csp, `${url}: web CSP`).toBe(WEB_CSP);
    expect(headers['referrer-policy'], `${url}: Referrer-Policy`).toBe(
      kind === 'token-page' ? 'no-referrer' : 'strict-origin-when-cross-origin',
    );
  }
}

/** Every JavaScript and CSS file reachable from index.html (lazy chunks included). */
async function servedAssets(request: APIRequestContext): Promise<Map<string, string>> {
  const base = e2eEnv().baseURL;
  const html = await (await request.get(`${base}/`)).text();
  const queue = [...html.matchAll(/(?:src|href)="([^"]+\.(?:js|css))"/g)].map(
    (match) => match[1] ?? '',
  );
  const files = new Map<string, string>();
  while (queue.length > 0) {
    const next = queue.shift() ?? '';
    const path = next.startsWith('/') ? next : `/${next.replace(/^\.\//, '')}`;
    if (files.has(path)) {
      continue;
    }
    const response = await request.get(`${base}${path}`);
    expect(response.status(), `${path}`).toBe(200);
    const body = await response.text();
    files.set(path, body);
    for (const match of body.matchAll(
      /["'`(/]((?:\.\/)?(?:chunk|main|polyfills|styles)-[A-Za-z0-9]+\.(?:js|css))/g,
    )) {
      queue.push(match[1] ?? '');
    }
  }
  return files;
}

test('every HTML, asset and API response of a page load carries the §15.2 headers', async ({
  page,
}) => {
  const seen: { url: string; headers: Record<string, string>; type: string }[] = [];
  page.on('response', (response) => {
    if (!response.url().startsWith(e2eEnv().baseURL)) {
      return;
    }
    void response
      .allHeaders()
      .then((headers) =>
        seen.push({ url: response.url(), headers, type: response.request().resourceType() }),
      )
      .catch(() => undefined);
  });
  await open(page, '/privacy');
  await open(page, '/auth/login');
  await page.goto('/auth/verify');
  await page.goto('/auth/reset');
  await page.waitForLoadState('networkidle');

  const types = new Set(seen.map((entry) => entry.type));
  for (const expected of ['document', 'script', 'stylesheet', 'fetch']) {
    expect(types, `resource types seen: ${[...types].join(', ')}`).toContain(expected);
  }
  for (const entry of seen) {
    const path = new URL(entry.url).pathname;
    const kind = path.startsWith('/api/')
      ? 'api'
      : /^\/auth\/(verify|reset)\/?$/.test(path)
        ? 'token-page'
        : 'web';
    expectHardened(entry.headers, path, kind);
  }
});

test('the API keeps its own headers and auth answers are not cacheable', async ({ request }) => {
  const api = e2eEnv().apiBase;
  const health = await request.get(`${api}/health`);
  expectHardened(health.headers(), '/api/v1/health', 'api');
  expect(await health.json()).toEqual({ status: expect.any(String) });
  const refresh = await request.post(`${api}/auth/refresh`, {
    headers: { 'X-Requested-With': 'vitadelta', 'Content-Type': 'application/json' },
    data: {},
  });
  expect(refresh.status()).toBe(401);
  expectHardened(refresh.headers(), '/api/v1/auth/refresh', 'api');
  expect(refresh.headers()['cache-control'] ?? '', '/auth/** Cache-Control').toContain('no-store');
});

test('the CSP leaves no room for inline or evaluated script', async ({ page }) => {
  const response = await page.goto('/');
  const csp = (await response?.allHeaders())?.['content-security-policy'] ?? '';
  const directives = new Map(
    csp
      .split(';')
      .map((part) => part.trim().split(/\s+/))
      .filter((part) => part.length > 0 && part[0])
      .map((part) => [part[0] ?? '', part.slice(1)]),
  );
  const scripts = directives.get('script-src') ?? directives.get('default-src') ?? [];
  expect(scripts).toEqual(["'self'"]);
  for (const forbidden of [
    "'unsafe-inline'",
    "'unsafe-eval'",
    "'unsafe-hashes'",
    'data:',
    '*',
    'http:',
    'https:',
  ]) {
    expect(scripts, `script-src ${forbidden}`).not.toContain(forbidden);
  }
  expect(directives.get('object-src')).toEqual(["'none'"]);
  expect(directives.get('base-uri')).toEqual(["'self'"]);
  expect(directives.get('frame-ancestors')).toEqual(["'none'"]);
  expect(directives.get('form-action')).toEqual(["'self'"]);
});

test('injected inline script, handlers and javascript: URLs are blocked and reported', async ({
  page,
}) => {
  const probe = await watch(page);
  await open(page, '/');
  await page.evaluate(() => {
    const own = window as unknown as Record<string, unknown>;
    delete own['__inline'];
    const script = document.createElement('script');
    script.textContent = 'window.__inline = "script";';
    document.head.appendChild(script);
    const image = document.createElement('img');
    image.setAttribute('src', 'data:image/gif;base64,invalid');
    image.setAttribute('onerror', 'window.__inline = "handler";');
    document.body.appendChild(image);
    const link = document.createElement('a');
    link.href = 'javascript:window.__inline="url"';
    link.textContent = 'x';
    document.body.appendChild(link);
    link.click();
    // String timers are compiled by the page itself and need 'unsafe-eval'.
    (window.setTimeout as unknown as (code: string, delay: number) => number)(
      'window.__inline = "timer";',
      0,
    );
  });
  await page.waitForTimeout(1_000);
  const state = await page.evaluate(() => {
    const own = window as unknown as Record<string, unknown>;
    return { inline: own['__inline'] ?? null };
  });
  expect(state.inline, 'injected code ran').toBeNull();
  const violations = await probe.cspViolations();
  expect(
    violations.some((entry) => entry.startsWith('script-src')),
    violations.join(' | '),
  ).toBe(true);
});

test('index.html is always revalidated while hashed assets are cached immutably', async ({
  request,
}) => {
  const base = e2eEnv().baseURL;
  for (const path of ['/', '/index.html', '/results', '/auth/login']) {
    const cache = (await request.get(`${base}${path}`)).headers()['cache-control'] ?? '';
    expect(cache, `${path} Cache-Control`).toMatch(/no-cache|no-store/);
    expect(cache, `${path} Cache-Control`).not.toContain('immutable');
  }
  const assets = [...(await servedAssets(request)).keys()].filter((path) =>
    /-[A-Za-z0-9]{8,}\.(js|css)$/.test(path),
  );
  expect(assets.length, 'hashed assets').toBeGreaterThan(0);
  for (const path of assets) {
    const cache = (await request.get(`${base}${path}`)).headers()['cache-control'] ?? '';
    expect.soft(cache, `${path} Cache-Control`).toMatch(/immutable|max-age=(?:[3-9]\d{6,}|\d{8,})/);
  }
});

test('unknown API paths answer problem JSON, never the SPA shell', async ({ request }) => {
  const base = e2eEnv().baseURL;
  for (const path of [
    '/api/v1/does-not-exist',
    '/api/v1/',
    '/api/v2/products',
    '/api/nope',
    '/api/v1/products/not-a-uuid?country=TR',
    '/api/v1/admin/does-not-exist',
  ]) {
    const response = await request.get(`${base}${path}`);
    const body = await response.text();
    expect(response.status(), `${path} status`).toBeGreaterThanOrEqual(400);
    expect(response.headers()['content-type'] ?? '', `${path} type`).toContain(
      'application/problem+json',
    );
    expect(body, `${path} body`).not.toContain('<vd-shell');
    expect(body, `${path} leaks internals`).not.toMatch(
      /Exception|\tat |stackTrace|org\.springframework/,
    );
  }
  const actuator = await request.get(`${base}/actuator/health`);
  expect(actuator.status()).toBe(404);
  expect(await actuator.text()).not.toContain('<vd-shell');
});

test('the served bundle has no source maps, secrets or internal host names', async ({
  request,
}) => {
  const base = e2eEnv().baseURL;
  const files = await servedAssets(request);
  expect(files.size, 'bundle files found').toBeGreaterThan(2);
  const patterns: readonly [string, RegExp][] = [
    ['source map reference', /sourceMappingURL/],
    ['environment variable names', /VITADELTA_/],
    ['scoring service address', /localhost:8000|scoring:8000/],
    ['internal API host', /\bapi:8080\b/],
    ['database names', /postgres|jdbc:/i],
    ['mail sink', /mailpit|:1025\b|:8025\b/i],
    ['bearer token literal', /Bearer ey/],
    ['JWT literal', /eyJ[\w-]{10,}\.eyJ[\w-]{10,}/],
    ['private key', /BEGIN (?:RSA |EC )?PRIVATE KEY/],
    ['local file paths', /[A-Z]:\\\\Users|\/home\/[a-z]|\/Users\/[a-z]/],
    ['source file paths', /src\/app\/[\w/-]+\.ts/],
  ];
  for (const [path, body] of files) {
    for (const [label, pattern] of patterns) {
      expect(body.match(pattern)?.[0] ?? null, `${path}: ${label}`).toBeNull();
    }
    if (path.endsWith('.js')) {
      const map = await request.get(`${base}${path}.map`);
      const text = await map.text();
      expect(text, `${path}.map is not served`).not.toMatch(/"mappings"\s*:/);
      expect(text, `${path}.map is not served`).not.toMatch(/"sources"\s*:/);
    }
  }
});

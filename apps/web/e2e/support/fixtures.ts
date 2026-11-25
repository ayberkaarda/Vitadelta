import AxeBuilder from '@axe-core/playwright';
import { type Page, test as base, expect } from '@playwright/test';

/**
 * Console and network problems seen by one page. Every visited page must be free of console
 * errors, uncaught exceptions and Content Security Policy violations.
 *
 * Chromium logs "Failed to load resource: … status of 4xx" for every API answer with an error
 * status, including the ones the app expects and renders (the anonymous `POST /auth/refresh`
 * 401 on start-up, `403 quota_exceeded`, `403 entitlement_exceeded`, `401 invalid_credentials`).
 * Such a message is accepted only when it belongs to an `/api/` response with status below 500
 * and a `application/problem+json` body, i.e. a documented server decision; everything else
 * (a missing asset, a 5xx, a blocked script) fails the test.
 */
export class PageMonitor {
  readonly problems: string[] = [];
  readonly expectedApiErrors: string[] = [];
  private readonly problemResponses = new Set<string>();

  constructor(readonly page: Page) {
    page.on('response', (response) => {
      const url = response.url();
      const type = response.headers()['content-type'] ?? '';
      if (
        url.includes('/api/') &&
        response.status() >= 400 &&
        response.status() < 500 &&
        type.startsWith('application/problem+json')
      ) {
        this.problemResponses.add(url);
      }
      if (response.status() >= 500) {
        this.problems.push(`HTTP ${response.status()} ${response.request().method()} ${url}`);
      }
    });
    page.on('console', (message) => {
      if (message.type() !== 'error') {
        return;
      }
      const text = message.text();
      const url = message.location().url;
      if (/^Failed to load resource: the server responded with a status of 4\d\d/.test(text)) {
        if (this.problemResponses.has(url)) {
          this.expectedApiErrors.push(`${text} ${url}`);
          return;
        }
      }
      this.problems.push(`console error: ${text} ${url}`.trim());
    });
    page.on('pageerror', (error) => this.problems.push(`uncaught: ${error.message}`));
  }

  /** Fails when anything unexpected was logged since the page opened. */
  expectClean(): void {
    expect(this.problems, 'console errors, uncaught errors or CSP violations').toEqual([]);
  }
}

/** Reports CSP violations as console errors so {@link PageMonitor} sees them. */
export async function watchPage(page: Page): Promise<PageMonitor> {
  const monitor = new PageMonitor(page);
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (event) => {
      console.error(`CSP violation: ${event.violatedDirective} blocked ${event.blockedURI}`);
    });
  });
  return monitor;
}

/** Runs axe (WCAG 2.1 A/AA) on the current page; serious or critical violations fail. */
export async function expectAccessible(page: Page, label: string): Promise<void> {
  // Colour contrast is only meaningful once fade-in transitions (drawer, dialog) have ended.
  await page.evaluate(() =>
    Promise.all(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getComputedTiming().iterations !== Infinity)
        .map((animation) => animation.finished.catch(() => undefined)),
    ),
  );
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations
    .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    .map(
      (violation) =>
        `${violation.id} (${violation.impact}): ${violation.help} -> ` +
        violation.nodes.map((node) => node.target.join(' ')).join(', '),
    );
  expect(blocking, `axe on ${label}`).toEqual([]);
}

interface Fixtures {
  monitor: PageMonitor;
}

/** `test` whose `page` is watched for console errors and CSP violations. */
export const test = base.extend<Fixtures>({
  monitor: [
    async ({ page }, use) => {
      const monitor = await watchPage(page);
      await use(monitor);
      monitor.expectClean();
    },
    { auto: true },
  ],
});

export { expect };

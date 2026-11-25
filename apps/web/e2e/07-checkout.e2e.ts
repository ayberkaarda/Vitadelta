import { type Browser, type BrowserContext, type Page, expect, test } from '@playwright/test';

import { main } from './support/app';
import { CHECKOUT_URL, PORTAL_URL, mockBillingApi } from './support/billing-api';
import { type PageMonitor, expectAccessible, watchPage } from './support/fixtures';

/**
 * Hosted checkout and billing portal (ADR-0014) with a mocked API: the checkout button asks for
 * a session and the browser follows the returned URL to a local stand-in of the provider's page
 * (no request reaches Stripe); the return to `/upgrade?checkout=success` waits for the plan the
 * server reports; the account page opens the billing portal. The layout checks cover the
 * payment parts at 375, 768 and 1280 px in both languages, with the wide fallback fonts of
 * Linux CI in mind (no sideways scroll, nothing beyond the viewport).
 *
 * Every API answer is mocked (`support/billing-api.ts`), so these checks change no server state.
 */

const WIDTHS = [375, 768, 1280] as const;

interface Opened {
  readonly context: BrowserContext;
  readonly page: Page;
  /** Console errors, uncaught exceptions and CSP violations of the page. */
  readonly monitor: PageMonitor;
}

async function openContext(browser: Browser, width: number, lang: 'en' | 'tr'): Promise<Opened> {
  const context = await browser.newContext({
    viewport: { width, height: 900 },
    locale: lang === 'tr' ? 'tr-TR' : 'en-US',
    timezoneId: 'UTC',
    reducedMotion: 'reduce',
  });
  await context.addInitScript((language) => {
    localStorage.setItem('vd.country', 'TR');
    localStorage.setItem('vd.lang', language);
  }, lang);
  const page = await context.newPage();
  return { context, page, monitor: await watchPage(page) };
}

/**
 * Elements that end beyond the right edge of the viewport, and a document that scrolls sideways.
 * Visually hidden helpers and the rows that scroll sideways by design (`.subnav` tabs,
 * `.tbl-wrap` tables, as in `06-long-text.e2e.ts`) are skipped.
 */
async function overflowing(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const found: string[] = [];
    const root = document.scrollingElement ?? document.documentElement;
    if (root.scrollWidth > root.clientWidth) {
      found.push(`document ${root.scrollWidth}/${root.clientWidth}`);
    }
    for (const element of Array.from(document.querySelectorAll<HTMLElement>('main *'))) {
      if (element.closest('.sr-only, .subnav, .tbl-wrap')) {
        continue;
      }
      const box = element.getBoundingClientRect();
      if (box.width > 0 && box.right > root.clientWidth + 0.5) {
        const name = `${element.tagName.toLowerCase()}.${Array.from(element.classList).join('.')}`;
        found.push(`${name} right=${Math.round(box.right)} > ${root.clientWidth}`);
      }
      const style = getComputedStyle(element);
      if (
        ['auto', 'scroll', 'hidden'].includes(style.overflowX) &&
        element.clientWidth > 1 &&
        element.scrollWidth > element.clientWidth + 1
      ) {
        const name = `${element.tagName.toLowerCase()}.${Array.from(element.classList).join('.')}`;
        found.push(`${name} scrolls ${element.scrollWidth}/${element.clientWidth}`);
      }
    }
    return found;
  });
}

test.describe('online payment with a mocked payment provider', () => {
  test('buys PRO through the hosted checkout and manages it in the billing portal', async ({
    browser,
  }) => {
    const { context, page, monitor } = await openContext(browser, 1280, 'en');
    const api = await mockBillingApi(context, page, { checkout: true, plan: 'FREE' });

    await page.goto('/upgrade');
    const pro = main(page).locator('[data-plan="PRO"]');
    const subscribe = pro.getByRole('button', { name: 'Subscribe to PRO' });
    await expect(subscribe).toBeVisible();
    await expect(main(page).locator('[data-how]')).toContainText('secure payment page');
    await expect(main(page).getByRole('heading', { name: 'Redeem a promo code' })).toBeVisible();
    await expectAccessible(page, 'upgrade page with checkout');

    // Keyboard: the button is reachable and starts the checkout.
    await subscribe.focus();
    await page.keyboard.press('Enter');
    await page.waitForURL(CHECKOUT_URL);
    await expect(page.getByRole('heading', { name: 'Checkout stand-in' })).toBeVisible();
    expect(api.billingCalls).toEqual(['POST /billing/checkout-session']);
    expect(api.providerVisits).toEqual([CHECKOUT_URL]);

    // The provider returns the browser before its webhook has reached the API: the page keeps
    // asking /me and shows PRO once the server reports it.
    api.landWebhookAfter(2);
    await page.goto('/upgrade?checkout=success');
    await expect(main(page).locator('[data-return="active"]')).toContainText(
      'PRO is active until Dec 25, 2026.',
      { timeout: 20_000 },
    );
    await expect(page).toHaveURL(/\/upgrade$/);
    await expect(pro.getByText('Your plan')).toBeVisible();
    await expect(pro.getByRole('button', { name: 'Subscribe to PRO' })).toHaveCount(0);
    await expectAccessible(page, 'upgrade page after checkout');

    await main(page).getByRole('link', { name: 'Go to your account' }).click();
    await expect(page).toHaveURL(/\/account$/);
    const manage = main(page)
      .locator('vd-plan-card')
      .getByRole('button', { name: 'Manage subscription' });
    await expect(manage).toBeVisible();
    await manage.click();
    await page.waitForURL(PORTAL_URL);
    expect(api.billingCalls).toEqual([
      'POST /billing/checkout-session',
      'POST /billing/portal-session',
    ]);
    expect(api.unmocked, 'API calls without a mocked answer').toEqual([]);
    monitor.expectClean();
    await context.close();
  });

  test('says that a canceled checkout charged nothing', async ({ browser }) => {
    const { context, page, monitor } = await openContext(browser, 1280, 'en');
    const api = await mockBillingApi(context, page, { checkout: true, plan: 'FREE' });
    await page.goto('/upgrade?checkout=canceled');
    await expect(main(page).locator('[data-return="canceled"]')).toContainText(
      'No payment was made and your plan did not change.',
    );
    await expect(page).toHaveURL(/\/upgrade$/);
    await expect(main(page).getByRole('button', { name: 'Subscribe to PRO' })).toBeVisible();
    expect(api.billingCalls).toEqual([]);
    expect(api.unmocked).toEqual([]);
    monitor.expectClean();
    await context.close();
  });

  test('shows no checkout button without a payment provider', async ({ browser }) => {
    const { context, page, monitor } = await openContext(browser, 1280, 'en');
    const api = await mockBillingApi(context, page, { checkout: false, plan: 'FREE' });
    await page.goto('/upgrade');
    await expect(main(page).locator('[data-plan="PRO"]')).toBeVisible();
    await expect(main(page).locator('[data-how]')).toContainText(
      'Online payment is not available yet.',
    );
    await expect(main(page).locator('[data-billing]')).toHaveCount(0);
    expect(api.unmocked).toEqual([]);
    monitor.expectClean();
    await context.close();
  });

  for (const lang of ['en', 'tr'] as const) {
    for (const width of WIDTHS) {
      test(`payment parts fit at ${width} px (${lang})`, async ({ browser }) => {
        const { context, page, monitor } = await openContext(browser, width, lang);
        const checkout = await mockBillingApi(context, page, {
          checkout: true,
          plan: 'FREE',
          lang,
        });
        await page.goto('/upgrade?checkout=canceled');
        await expect(main(page).locator('[data-return="canceled"]')).toBeVisible();
        await expect(main(page).locator('button[data-action="checkout"]')).toBeVisible();
        expect(await overflowing(page)).toEqual([]);
        await expectAccessible(page, `upgrade page at ${width} px (${lang})`);
        expect(checkout.unmocked).toEqual([]);
        monitor.expectClean();
        await context.close();

        const second = await openContext(browser, width, lang);
        const managed = await mockBillingApi(second.context, second.page, {
          checkout: true,
          plan: 'PRO_PROVIDER',
          lang,
        });
        await second.page.goto('/account');
        await expect(
          main(second.page).locator('vd-plan-card button[data-action="manage-subscription"]'),
        ).toBeVisible();
        expect(await overflowing(second.page)).toEqual([]);
        await second.page.goto('/upgrade');
        await expect(
          main(second.page).locator('[data-plan="PRO"] button[data-action="manage-subscription"]'),
        ).toBeVisible();
        expect(await overflowing(second.page)).toEqual([]);
        expect(managed.unmocked).toEqual([]);
        second.monitor.expectClean();
        await second.context.close();
      });
    }
  }
});

import { type Page, expect, test } from '@playwright/test';

import { GOLD_A_ID, GOLD_B_ID } from '../src/app/features/results/testing/golden.fixtures';
import { main } from './support/app';
import {
  LONG_CODE,
  LONG_EMAIL,
  LONG_FILE,
  LONG_PROMO,
  LONG_TOKEN,
  type LongTextApi,
  mockLongTextApi,
} from './support/long-text-api';

/**
 * Catalog, account and server text with long unbroken tokens (a 60-character product, brand,
 * retailer, nutrient and notification text, the longest product and promo codes of the
 * contract, a 72-character e-mail address and file name) never widens the page: at 375 px the
 * document does not scroll horizontally and no panel scrolls sideways (only `.tbl-wrap` tables
 * and the `.subnav` tab row may, by design); at 1280 px nothing scrolls sideways at all, tables
 * included.
 *
 * Every API answer is mocked (`support/long-text-api.ts`), so these checks need only the served
 * web app; they change no server state.
 */

const WIDTHS = [375, 1280] as const;

interface Surface {
  readonly name: string;
  readonly path: string;
  readonly signedIn: boolean;
  /** Text that must be visible before measuring (the import page has none before the upload). */
  readonly ready?: string;
  /** Extra steps after the page has loaded (open the drawer, upload a file). */
  readonly act?: (page: Page) => Promise<void>;
}

const SURFACES: readonly Surface[] = [
  {
    name: 'product detail heading, offers and formulation',
    path: `/products/${GOLD_A_ID}?country=TR`,
    signedIn: false,
    ready: LONG_TOKEN,
  },
  {
    name: 'results rows',
    path: '/results?country=TR',
    signedIn: true,
    ready: LONG_CODE,
  },
  {
    name: 'breakdown drawer title',
    path: '/results?country=TR',
    signedIn: true,
    ready: LONG_CODE,
    act: async (page) => {
      await main(page).locator('button[data-action="breakdown"]').first().click();
      await expect(page.getByRole('dialog')).toBeVisible();
    },
  },
  {
    name: 'compare header',
    path: `/compare/${GOLD_A_ID}/${GOLD_B_ID}?country=TR`,
    signedIn: true,
    ready: LONG_CODE,
  },
  { name: 'saved products', path: '/account/saved', signedIn: true, ready: LONG_TOKEN },
  { name: 'alerts', path: '/account/alerts', signedIn: true, ready: LONG_TOKEN },
  { name: 'inbox', path: '/account/notifications', signedIn: true, ready: LONG_TOKEN },
  { name: 'admin users', path: '/admin/users', signedIn: true, ready: LONG_EMAIL },
  { name: 'admin promo codes', path: '/admin/promo-codes', signedIn: true, ready: LONG_PROMO },
  {
    name: 'admin import jobs and errors',
    path: '/admin/imports',
    signedIn: true,
    act: async (page) => {
      await page.locator('[data-slot="OFFERS_CSV"] input[type="file"]').setInputFiles({
        name: LONG_FILE,
        mimeType: 'text/csv',
        buffer: Buffer.from('product_code,country_code\n'),
      });
      const job = main(page).locator('tr[data-status="FAILED"]');
      await expect(job).toContainText(LONG_FILE);
      const details = job.getByRole('button', { name: 'Details' });
      if ((await details.getAttribute('aria-expanded')) !== 'true') {
        await details.click();
      }
      await expect(
        main(page).locator('[data-job-errors]').getByRole('table').locator('tbody tr'),
      ).toHaveCount(2);
    },
  },
];

/**
 * Elements that scroll sideways: the document itself and every scroll container (`overflow-x`
 * auto, scroll or hidden) wider inside than outside. Visually hidden helpers are skipped.
 */
async function sidewaysScroll(page: Page, allowTables: boolean): Promise<string[]> {
  return page.evaluate((tablesMayScroll) => {
    const found: string[] = [];
    const root = document.scrollingElement ?? document.documentElement;
    if (root.scrollWidth > root.clientWidth) {
      found.push(`document ${root.scrollWidth}/${root.clientWidth}`);
    }
    for (const element of Array.from(document.body.querySelectorAll<HTMLElement>('*'))) {
      const style = getComputedStyle(element);
      if (!['auto', 'scroll', 'hidden'].includes(style.overflowX)) {
        continue;
      }
      if (element.clientWidth <= 1 || element.closest('.sr-only')) {
        continue;
      }
      const scrollsByDesign = ['tbl-wrap', 'subnav'].some((name) =>
        element.classList.contains(name),
      );
      if (tablesMayScroll && scrollsByDesign) {
        continue;
      }
      if (element.scrollWidth > element.clientWidth + 1) {
        const name = `${element.tagName.toLowerCase()}.${Array.from(element.classList).join('.')}`;
        found.push(`${name} ${element.scrollWidth}/${element.clientWidth}`);
      }
    }
    return found;
  }, allowTables);
}

test.describe('long unbroken catalog text', () => {
  test('the tokens have the intended length', () => {
    expect(LONG_TOKEN).toHaveLength(60);
    expect(LONG_TOKEN).not.toMatch(/[\s-]/);
    expect(LONG_CODE).toHaveLength(40);
  });

  for (const surface of SURFACES) {
    for (const width of WIDTHS) {
      test(`${surface.name} at ${width} px`, async ({ browser }) => {
        const context = await browser.newContext({
          viewport: { width, height: 900 },
          locale: 'en-US',
          timezoneId: 'UTC',
          reducedMotion: 'reduce',
        });
        await context.addInitScript(() => {
          localStorage.setItem('vd.country', 'TR');
          localStorage.setItem('vd.lang', 'en');
        });
        const page = await context.newPage();
        const errors: string[] = [];
        page.on('pageerror', (error) => errors.push(error.message));
        const api: LongTextApi = await mockLongTextApi(page, surface.signedIn);
        await page.goto(surface.path);
        if (surface.ready) {
          await expect(main(page).getByText(surface.ready).first()).toBeVisible();
        }
        await surface.act?.(page);
        expect(await sidewaysScroll(page, width < 1280)).toEqual([]);
        expect(api.unmocked, 'API calls without a mocked answer').toEqual([]);
        expect(errors).toEqual([]);
        await context.close();
      });
    }
  }
});

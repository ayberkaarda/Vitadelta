import { type Browser, type Page, expect, test } from '@playwright/test';

import { expectNoHorizontalScroll, main, open } from '../support/app';
import {
  COUNTRY,
  JOINED,
  PAYLOADS,
  type Probe,
  apiFetch,
  expectInert,
  importProducts,
  productIdOf,
  watch,
} from './support/attack';

/**
 * Reference and upper-limit facts of the product detail page (project specification §15.5,
 * §15.7 case 8, web part; Phase 7 `reference` / `upperLimit` contract).
 *
 * The reference amounts, upper limits and their citation notes come from the seeded reference
 * data; no admin API can change them. Everything an importer controls on the same table row
 * goes hostile instead: the label form text of every row and the product name, which is a
 * translation parameter of the table caption. The rows cover each server decision: two
 * exceeded limits (TOTAL and SUPPLEMENTAL basis), one limit met exactly, a UL-relevant
 * amount below the labelled one, a form without an applicable limit and a nutrient without
 * reference or limit. The page must show every hostile string verbatim as text, at desktop
 * and phone width, and only the server may decide which rows are marked as exceeded and
 * which citation note is open.
 */

const CODE = 'ADV-FACTS-E';
const NAME =
  `Adversarial E ${PAYLOADS.img} {{ name }} {{ code }} {{ amount }} ` +
  '{{ product.formulation.ul_exceeded }}';

/** Label form text per nutrient: script payloads and interpolation markers of the table keys. */
const LABELS: Readonly<Record<string, string>> = {
  ZINC: `${PAYLOADS.img} {{ code }} {{ set }}`,
  MAGNESIUM: `${PAYLOADS.svg} {{ amount }}`,
  VIT_A: PAYLOADS.template,
  VIT_D: `{{ product.formulation.ul_exceeded }} {{ name }} ${PAYLOADS.script}`,
  VIT_B3: PAYLOADS.jsUrl,
  CHOLINE: '{{ constructor.name }} {{ product.formulation.reference_none }} {{ missing }}',
  VIT_C: JOINED,
  IODINE: '{{ amount }}{{ amount }} %{{ set }}',
};

function hostileProduct(status: 'AVAILABLE' | 'NOT_SOLD'): object {
  const nutrient = (code: string, amount: number, unit: string, extra: object = {}) => ({
    nutrient_code: code,
    amount_per_serving: amount,
    unit,
    form_label: LABELS[code],
    ...extra,
  });
  return {
    code: CODE,
    brand: `${PAYLOADS.script} Brand`,
    name: NAME,
    dosage_form: 'TABLET',
    units_per_container: 60,
    units_per_serving: 1,
    servings_per_day: 1,
    source: 'Synthetic adversarial fixture (fictional brand, hostile display strings)',
    synthetic: true,
    nutrients: [
      // 40 mg against the EFSA total limit of 25 mg: exceeded.
      nutrient('ZINC', 40, 'mg'),
      // 300 mg against the EFSA supplemental limit of 250 mg: exceeded.
      nutrient('MAGNESIUM', 300, 'mg'),
      // Retinol 900 mcg of which 600 mcg count toward the 3000 mcg limit: not exceeded.
      nutrient('VIT_A', 900, 'mcg', { form_code: 'RETINOL', ul_amount_per_serving: 600 }),
      // 5 mcg against the EFSA limit of 100 mcg, which carries a citation note.
      nutrient('VIT_D', 5, 'mcg'),
      // Niacin without a stated form: the EFSA limits are form-dependent, so none applies.
      nutrient('VIT_B3', 20, 'mg'),
      // Choline: no EU reference value and no EFSA limit.
      nutrient('CHOLINE', 50, 'mg'),
      nutrient('VIT_C', 80, 'mg'),
      // Equal to the EFSA limit of 600 mcg: not exceeded.
      nutrient('IODINE', 600, 'mcg'),
    ],
    availability: [{ country_code: COUNTRY, status }],
    offers: [
      {
        country_code: COUNTRY,
        retailer: 'Facts Store',
        price: 30,
        currency: 'GBP',
        in_stock: true,
        url: `https://adversarial-shop.example/gb/p/${CODE.toLowerCase()}`,
        checked_at: '2026-11-24T09:00:00Z',
      },
    ],
  };
}

interface ApiNutrient {
  readonly code: string;
  readonly formLabel: string | null;
  readonly reference: { readonly percentOfReference: number } | null;
  readonly upperLimit: {
    readonly exceeded: boolean;
    readonly note: string | null;
    readonly basis: string;
  } | null;
}

/** Nutrient rows of the product detail answer, or `null` when the API answers no product. */
async function apiNutrients(id: string, country: string): Promise<ApiNutrient[] | null> {
  const response = await apiFetch(`/products/${id}?country=${country}`);
  if (response.status !== 200) {
    return null;
  }
  return ((await response.json()) as { product: { nutrients: ApiNutrient[] } }).product.nutrients;
}

function exceededCodes(nutrients: readonly ApiNutrient[] | null): string[] {
  return (nutrients ?? [])
    .filter((entry) => entry.upperLimit?.exceeded === true)
    .map((entry) => entry.code)
    .sort();
}

async function anonymousPage(
  browser: Browser,
  viewport: { width: number; height: number },
): Promise<Probe> {
  const context = await browser.newContext({ viewport });
  return watch(await context.newPage());
}

function table(page: Page) {
  return main(page).locator('vd-formulation-table');
}

/** What the rendered table claims: exceeded rows, highlighted rows and open notes. */
async function tableClaims(page: Page): Promise<{
  exceeded: string[];
  highlighted: string[];
  openNotes: number;
  expanded: string[];
}> {
  return table(page).evaluate((host) => {
    const codes = (selector: string) =>
      [...host.querySelectorAll(selector)]
        .map((element) => element.closest('tr')?.getAttribute('data-code') ?? '?')
        .sort();
    return {
      exceeded: codes('[data-marker="exceeded"]'),
      highlighted: codes('tr.is-hl td:first-child'),
      openNotes: host.querySelectorAll('.note:not([hidden])').length,
      expanded: [...host.querySelectorAll('[aria-expanded="true"]')].map(
        (element) => element.getAttribute('aria-label') ?? '',
      ),
    };
  });
}

// Not serial: each case stands alone (the import is idempotent), so one failing rendering check
// does not hide the tampering case.
test.describe('reference and upper-limit facts of the product detail page', () => {
  let id = '';
  let facts: ApiNutrient[] = [];

  test.beforeAll(async () => {
    const job = await importProducts({
      schema_version: 1,
      products: [hostileProduct('AVAILABLE')],
    });
    expect(job.status, JSON.stringify(job.errors)).toBe('SUCCEEDED');
    id = await productIdOf(CODE, COUNTRY);
    facts = (await apiNutrients(id, COUNTRY)) ?? [];
    expect(facts.map((entry) => entry.code).sort()).toEqual(Object.keys(LABELS).sort());
  });

  test.afterAll(async () => {
    const cleanup = await importProducts({
      schema_version: 1,
      products: [hostileProduct('NOT_SOLD')],
    });
    expect(cleanup.status, JSON.stringify(cleanup.errors)).toBe('SUCCEEDED');
  });

  test('the API keeps the label text verbatim and decides the limits itself', async () => {
    for (const entry of facts) {
      expect(entry.formLabel, `${entry.code} formLabel`).toBe(LABELS[entry.code]);
    }
    expect(exceededCodes(facts), 'exceeded rows').toEqual(['MAGNESIUM', 'ZINC']);
    const byCode = new Map(facts.map((entry) => [entry.code, entry]));
    expect(byCode.get('VIT_B3')?.upperLimit, 'niacin of unknown form').toBeNull();
    expect(byCode.get('CHOLINE')?.reference, 'choline reference').toBeNull();
    expect(byCode.get('CHOLINE')?.upperLimit, 'choline limit').toBeNull();
    expect(byCode.get('IODINE')?.upperLimit?.exceeded, 'iodine at the limit').toBe(false);
    expect(byCode.get('MAGNESIUM')?.upperLimit?.basis).toBe('SUPPLEMENTAL');
    expect(byCode.get('VIT_D')?.upperLimit?.note ?? '', 'citation note').toMatch(/^EFSA /);
  });

  for (const viewport of [
    { width: 1280, height: 800 },
    { width: 375, height: 812 },
  ]) {
    test(`hostile label text and product name render as text at ${viewport.width} px`, async ({
      browser,
    }) => {
      const probe = await anonymousPage(browser, viewport);
      const page = probe.page;
      await open(page, `/products/${id}?country=${COUNTRY}`);
      await expect(main(page).getByRole('heading', { level: 1 })).toContainText(NAME);
      const label = `product facts at ${viewport.width} px`;

      // The caption is a translated string with the product name as its parameter.
      const region = table(page).getByRole('region');
      await expect(region).toHaveAttribute('aria-label', `Formulation of ${NAME}`);
      await expect(table(page).locator('caption')).toHaveText(`Formulation of ${NAME}`);

      for (const [code, text] of Object.entries(LABELS)) {
        const row = table(page).locator(`tr[data-code="${code}"]`);
        await expect(row.locator('td').nth(1), `${code} form cell`).toHaveText(text);
      }
      const claims = await tableClaims(page);
      expect(claims.exceeded, `${label}: exceeded markers`).toEqual(exceededCodes(facts));
      expect(claims.openNotes, `${label}: notes open on load`).toBe(0);

      // Server decisions next to hostile text: the UL-relevant amount, the empty markers.
      const vitA = table(page).locator('tr[data-code="VIT_A"]');
      await expect(vitA.locator('[data-part="ul-amount"]')).toHaveText(
        'Counts toward the limit: 600 mcg',
      );
      await expect(
        table(page).locator('tr[data-code="CHOLINE"] [data-empty="reference"]'),
      ).toBeVisible();
      await expect(
        table(page).locator('tr[data-code="VIT_B3"] [data-empty="upper-limit"]'),
      ).toBeVisible();
      await expect(
        table(page).locator('tr[data-code="IODINE"] [data-marker="exceeded"]'),
      ).toHaveCount(0);

      // The citation note opens only through its own button and shows the seeded text.
      const vitD = table(page).locator('tr[data-code="VIT_D"]');
      const source = vitD.getByRole('button', { name: 'Source of the upper limit for VIT_D' });
      await source.scrollIntoViewIfNeeded();
      await source.click();
      await expect(source).toHaveAttribute('aria-expanded', 'true');
      const note = facts.find((entry) => entry.code === 'VIT_D')?.upperLimit?.note ?? '';
      await expect(vitD.locator('.note')).toHaveText(note);
      expect((await tableClaims(page)).openNotes, `${label}: one note open`).toBe(1);

      await expectInert(probe, label, [NAME, ...Object.values(LABELS)]);
      if (viewport.width < 600) {
        await expectNoHorizontalScroll(page);
      }
      await page.context().close();
    });
  }

  test('the exceeded marker and the note disclosure cannot be driven from the URL', async ({
    browser,
  }) => {
    const probe = await anonymousPage(browser, { width: 1280, height: 800 });
    const page = probe.page;
    const tampered = [
      `?country=${COUNTRY}&exceeded=VIT_C&flag=EXCEEDS_UL&flags=EXCEEDS_UL&nutrientCodes=VIT_C` +
        '&highlight=VIT_C,VIT_D&open=VIT_D&note=VIT_D#nutrient-row-VIT_C',
      `?country=${COUNTRY}&upperLimit%5Bexceeded%5D=true&upperLimit.exceeded=true` +
        '&reference=0&percentOfReference=9999&ul=1&basis=SUPPLEMENTAL',
      `?country=${COUNTRY}&country=TR&exceeded=true`,
      `?country=${COUNTRY}&__proto__%5Bexceeded%5D=true&constructor%5Bprototype%5D%5Bexceeded%5D=true`,
      `?country=${COUNTRY}#vd-ul-note-0-VIT_D`,
      '?country=TR&exceeded=VIT_C',
      '?country=US&exceeded=VIT_C',
    ];
    for (const query of tampered) {
      const answered = page
        .waitForResponse(
          (response) => new URL(response.url()).pathname === `/api/v1/products/${id}`,
          { timeout: 20_000 },
        )
        .catch(() => null);
      await open(page, `/products/${id}${query}`);
      const requested = await answered;
      expect(requested, `${query}: the page asked the API`).not.toBeNull();
      const country = new URL(requested?.url() ?? 'http://x/').searchParams.get('country') ?? '';
      const served = await apiNutrients(id, country);
      if (served) {
        await expect(table(page).locator('tbody tr').first()).toBeVisible();
        // A late hash change or history entry does not open or mark anything either.
        await page.evaluate(() => {
          location.hash = 'nutrient-row-VIT_C';
          history.replaceState(
            null,
            '',
            `${location.pathname}${location.search}#vd-ul-note-1-VIT_D`,
          );
          window.dispatchEvent(new HashChangeEvent('hashchange'));
        });
        const claims = await tableClaims(page);
        expect(claims.exceeded, `${query}: exceeded markers`).toEqual(exceededCodes(served));
        expect(
          claims.highlighted.filter((code) => !exceededCodes(served).includes(code)),
          `${query}: highlighted rows that the server did not mark`,
        ).toEqual([]);
        expect(claims.openNotes, `${query}: open notes`).toBe(0);
        expect(claims.expanded, `${query}: expanded disclosures`).toEqual([]);
      } else {
        await expect(table(page), `${query}: no formulation without a product`).toHaveCount(0);
      }
      await expectInert(probe, `tampered ${query}`);
    }
    await page.context().close();
  });
});

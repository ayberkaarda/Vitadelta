import { type Locator, type Page, expect } from '@playwright/test';

import { e2eEnv } from './env';

/** Synthetic fixture products of the TR market used by the flows (data/fixtures). */
export const GOLD_A = { code: 'GOLD-A', name: 'Gold Standard Daily A' } as const;
export const GOLD_B = { code: 'GOLD-B', name: 'Gold Standard Daily B' } as const;

export const PASSWORD = 'Saffron-Lantern-Harbor-42';

/** A unique e-mail address of this run (Mailpit accepts every recipient). */
export function uniqueEmail(role: string): string {
  const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
  return `e2e-${role}-${stamp}@example.com`;
}

export function header(page: Page): Locator {
  return page.getByRole('banner');
}

export function main(page: Page): Locator {
  return page.getByRole('main');
}

/** Opens `path` and waits until the shell has resolved the session (quota chip or account menu). */
export async function open(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(
    header(page)
      .getByRole('status')
      .or(header(page).getByRole('button', { name: /^Account menu|^Hesap menüsü/ })),
  ).toBeVisible();
}

/** Selects the market in the header and the English UI (stored in this browser). */
export async function chooseMarket(page: Page, country: string): Promise<void> {
  const select = header(page).getByRole('combobox', { name: /^(Country|Ülke)$/ });
  if ((await select.inputValue()) !== country) {
    await select.selectOption(country);
  }
  await useLanguage(page, 'en');
}

export async function useLanguage(page: Page, lang: 'en' | 'tr'): Promise<void> {
  const name = lang === 'en' ? 'EN English' : 'TR Türkçe';
  const button = header(page).getByRole('button', { name, exact: true });
  if ((await button.getAttribute('aria-pressed')) !== 'true') {
    await button.click();
  }
  await expect(page.locator('html')).toHaveAttribute('lang', lang);
}

/** Submits the query form as it is and waits for the results heading. */
export async function rankProducts(page: Page): Promise<void> {
  await main(page).getByRole('button', { name: 'Rank products' }).click();
  await expect(page).toHaveURL(/\/results\?/);
}

export function resultsHeading(page: Page): Locator {
  return main(page).getByRole('heading', { level: 1 });
}

/** Rows of the ranked-products table (without the header row). */
export function resultRows(page: Page): Locator {
  return main(page).getByRole('table', { name: 'Ranked products' }).locator('tbody tr');
}

/** Product names in the ranked table, top to bottom. */
export async function rankedNames(page: Page): Promise<string[]> {
  return resultRows(page)
    .locator('td a[href^="/products/"]')
    .allInnerTexts()
    .then((names) => names.map((name) => name.trim()));
}

/** Product id of `code` in `country` from the public product search. */
export async function productId(code: string, country = 'TR'): Promise<string> {
  const response = await fetch(
    `${e2eEnv().apiBase}/products?country=${country}&q=${encodeURIComponent(code)}`,
  );
  const body = (await response.json()) as { items: { id: string; code: string }[] };
  const item = body.items.find((entry) => entry.code === code);
  if (!item) {
    throw new Error(`Product ${code} is not in market ${country}; run scripts/seed.sh`);
  }
  return item.id;
}

/** Every product code of the market (to prove locked rows carry no product data). */
export async function marketCodes(country = 'TR'): Promise<string[]> {
  const codes: string[] = [];
  for (let page = 1; page < 20; page += 1) {
    const response = await fetch(
      `${e2eEnv().apiBase}/products?country=${country}&page=${page}&pageSize=100`,
    );
    const body = (await response.json()) as { items: { code: string }[] };
    codes.push(...body.items.map((item) => item.code));
    if (body.items.length < 100) {
      break;
    }
  }
  return codes;
}

/** Fails when the page scrolls horizontally at the current viewport width. */
export async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => {
    const root = document.scrollingElement ?? document.documentElement;
    return root.scrollWidth - root.clientWidth;
  });
  expect(overflow, 'horizontal overflow in px').toBeLessThanOrEqual(0);
}

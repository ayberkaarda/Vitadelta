import { type Browser, type Page, expect, test } from '@playwright/test';

import { open, resultRows } from '../support/app';
import { e2eEnv } from '../support/env';
import {
  COUNTRY,
  adoptSession,
  apiLogin,
  expectInert,
  importProducts,
  product,
  sleep,
  watch,
} from './support/attack';

/**
 * Catalog text inside translated messages (project specification §15.5, §10.3).
 *
 * Product names reach translated strings as interpolation parameters, for example the
 * accessible name "Score breakdown of {{ name }}" of every results row. A parameter value is
 * data: interpolation markers inside it must be shown verbatim, never resolved against the
 * parameters, the JavaScript prototype chain or the translation dictionary, and never looped on.
 */

function offeredIn(code: string, name: string, status: 'AVAILABLE' | 'NOT_SOLD'): object {
  const document = product(code, name, 'Template Store', 9) as Record<string, unknown>;
  return { ...document, availability: [{ country_code: COUNTRY, status }] };
}

async function signedInPage(browser: Browser): Promise<Page> {
  const env = e2eEnv();
  const context = await browser.newContext();
  await adoptSession(context, await apiLogin(env.adminEmail, env.adminPassword));
  return context.newPage();
}

test('interpolation markers in a product name are shown verbatim', async ({ browser }) => {
  const name = 'Adversarial C {{ constructor.name }} {{ results.table.breakdown }} {{ missing }}';
  const job = await importProducts({
    schema_version: 1,
    products: [offeredIn('ADV-TPL-C', name, 'AVAILABLE')],
  });
  expect(job.status, JSON.stringify(job.errors)).toBe('SUCCEEDED');

  const page = await signedInPage(browser);
  const probe = await watch(page);
  await open(page, `/results?country=${COUNTRY}`);
  const row = resultRows(page).filter({ hasText: 'Adversarial C' });
  await expect(row).toHaveCount(1);
  await expect(row.locator('td a[href^="/products/"]')).toHaveText(name);
  await expect(
    row.locator('button[aria-label^="Score breakdown of"]'),
    'accessible name built from the product name',
  ).toHaveAttribute('aria-label', `Score breakdown of ${name}`);
  await expectInert(probe, 'results with interpolation markers');
  await page.context().close();
});

test('a product name that repeats its own placeholder does not hang the results page', async ({
  browser,
}) => {
  const name = 'Adversarial D placeholder {{ name }}';
  const job = await importProducts({
    schema_version: 1,
    products: [offeredIn('ADV-TPL-D', name, 'AVAILABLE')],
  });
  expect(job.status, JSON.stringify(job.errors)).toBe('SUCCEEDED');
  const page = await signedInPage(browser);
  try {
    const ranked = page.waitForResponse(
      (response) => response.url().includes('/api/v1/recommendations/query') && response.ok(),
    );
    await page.goto(`/results?country=${COUNTRY}`);
    await ranked;
    await sleep(3_000);
    const responsive = await Promise.race([
      page.evaluate(() => document.body.innerText.length).then(() => true),
      sleep(10_000).then(() => false),
    ]);
    expect(responsive, 'the results tab still answers within 10 s (no endless loop)').toBe(true);
  } finally {
    await page
      .context()
      .close()
      .catch(() => undefined);
    // The looping product leaves the market again so later flows can open GB results.
    const cleanup = await importProducts({
      schema_version: 1,
      products: [offeredIn('ADV-TPL-D', name, 'NOT_SOLD')],
    });
    expect(cleanup.status, JSON.stringify(cleanup.errors)).toBe('SUCCEEDED');
  }
});

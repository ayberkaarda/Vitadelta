import { resolve } from 'node:path';

import { header, main, open, resultRows, useLanguage } from './support/app';
import { e2eEnv } from './support/env';
import { expect, expectAccessible, test } from './support/fixtures';

const PRODUCTS_JSON = resolve(__dirname, 'fixtures', 'products.import.synthetic.json');
const IMPORTED = { code: 'E2E-IMPORT-DAILY', name: 'Import Check Daily' } as const;

test('admin imports a products JSON in the UI and the product is ranked', async ({ page }) => {
  await open(page, '/auth/login');
  await useLanguage(page, 'en');
  await main(page).getByLabel('E-mail').fill(e2eEnv().adminEmail);
  await main(page).getByLabel('Password').fill(e2eEnv().adminPassword);
  await main(page).getByRole('button', { name: 'Log in' }).click();
  await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeVisible();

  await open(page, '/admin/imports');
  await page.locator('[data-slot="PRODUCTS_JSON"] input[type="file"]').setInputFiles(PRODUCTS_JSON);
  const job = main(page).getByRole('row').filter({ hasText: 'products.import.synthetic.json' });
  await expect(job).toContainText('Succeeded', { timeout: 30_000 });

  await open(page, '/admin/users');
  await expect(main(page).getByRole('heading', { name: 'Users', exact: true })).toBeVisible();
  await expectAccessible(page, 'admin users page');

  await open(page, '/results?country=US&mode=PRICE');
  await expect(resultRows(page).first()).toContainText(IMPORTED.name);
  await expect(resultRows(page).first()).toContainText(IMPORTED.code);
});

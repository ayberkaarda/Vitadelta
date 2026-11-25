import { resolve } from 'node:path';

import type { BrowserContext, Page } from '@playwright/test';

import {
  GOLD_A,
  GOLD_B,
  PASSWORD,
  chooseMarket,
  header,
  main,
  open,
  productId,
  rankProducts,
  rankedNames,
  resultRows,
  resultsHeading,
  uniqueEmail,
  useLanguage,
} from './support/app';
import { type PageMonitor, expect, expectAccessible, test, watchPage } from './support/fixtures';
import { linkIn, messagesTo, waitForMail } from './support/mailpit';
import { e2eEnv } from './support/env';

const PRICE_DROP_CSV = resolve(
  __dirname,
  '..',
  '..',
  '..',
  'data',
  'fixtures',
  'offers.price-drop.synthetic.csv',
);

/**
 * One FREE user from the anonymous call to action to PRO and logout, with the bootstrap admin
 * importing the price-drop offers and creating the promo code in between. The steps build on
 * each other, so they run serially on one browser context per actor.
 */
test.describe.serial('account journey', () => {
  const email = uniqueEmail('journey');
  let context: BrowserContext;
  let page: Page;
  let monitor: PageMonitor;
  let adminContext: BrowserContext;
  let admin: Page;
  let adminMonitor: PageMonitor;
  let promoCode = '';

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext();
    page = await context.newPage();
    monitor = await watchPage(page);
    adminContext = await browser.newContext();
    admin = await adminContext.newPage();
    adminMonitor = await watchPage(admin);
  });

  test.afterEach(() => {
    monitor.expectClean();
    adminMonitor.expectClean();
  });

  test.afterAll(async () => {
    await context.close();
    await adminContext.close();
  });

  test('registers from the locked results and verifies through the e-mail link', async () => {
    await open(page, '/');
    await chooseMarket(page, 'TR');
    await rankProducts(page);
    await expect(resultRows(page)).toHaveCount(3);
    await main(page).getByRole('link', { name: 'Create free account' }).click();

    await expect(page).toHaveURL(/\/auth\/register/);
    await expectAccessible(page, 'register page');
    await main(page).getByLabel('E-mail').fill(email);
    await main(page).getByLabel('Password').fill(PASSWORD);
    await expect(main(page).getByLabel('Country')).toHaveValue('TR');
    await main(page).getByRole('button', { name: 'Create account' }).click();
    await expect(main(page).getByRole('heading', { name: 'Check your e-mail' })).toBeVisible();

    const mail = await waitForMail(email, /Confirm your VitaDelta e-mail address/);
    const link = linkIn(mail, '/auth/verify');
    expect(link).toMatch(/\/auth\/verify#token=[A-Za-z0-9_-]+$/);
    expect(link.startsWith(e2eEnv().baseURL)).toBe(true);

    await page.goto(link);
    await expect(main(page).getByRole('heading', { name: 'E-mail verified' })).toBeVisible();
    expect(page.url()).not.toContain('token');
    expect(await page.evaluate(() => window.location.hash)).toBe('');
    await expectAccessible(page, 'verify page');

    await main(page).getByRole('link', { name: 'Log in' }).click();
    await expect(page).toHaveURL(/\/auth\/login/);
    await expectAccessible(page, 'login page');
    await main(page).getByLabel('E-mail').fill(email);
    await main(page).getByLabel('Password').fill(PASSWORD);
    await main(page).getByRole('button', { name: 'Log in' }).click();
    await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeVisible();
    await expect(header(page).getByRole('link', { name: 'Log in' })).toHaveCount(0);
  });

  test('sees the full result list, paged', async () => {
    await open(page, '/results?country=TR&size=5');
    await expect(resultsHeading(page)).toHaveText(/(\d+) products ranked/);
    const total = Number(/(\d+)/.exec(await resultsHeading(page).innerText())?.[1]);
    expect(total).toBeGreaterThan(5);
    await expect(resultRows(page)).toHaveCount(5);
    await expect(main(page).getByRole('list', { name: 'Locked results' })).toHaveCount(0);
    const pagination = main(page).getByRole('navigation', { name: 'Pagination' });
    await expect(pagination.getByText(`Showing 1–5 of ${total}`)).toBeVisible();
    const firstPage = await rankedNames(page);
    await pagination
      .getByRole('button', { name: 'Next' })
      .or(pagination.getByRole('link', { name: 'Next' }))
      .click();
    await expect(page).toHaveURL(/page=2/);
    await expect(pagination.getByText(`Showing 6–10 of ${total}`)).toBeVisible();
    const secondPage = await rankedNames(page);
    expect(secondPage.some((name) => firstPage.includes(name))).toBe(false);
    await expectAccessible(page, 'results page (signed in)');
  });

  test('keeps the session across a reload without storing a token', async () => {
    await open(page, '/account');
    await expect(main(page).getByRole('heading', { level: 1 })).toBeVisible();
    await page.reload();
    await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeVisible();
    await expect(main(page).getByText(email).first()).toBeVisible();
    const stored = await page.evaluate(() => ({
      keys: Object.keys(localStorage),
      session: Object.keys(sessionStorage),
      cookie: document.cookie,
    }));
    expect(stored.keys.every((key) => /^vd\.(country|currency|lang)$/.test(key))).toBe(true);
    expect(stored.session).toEqual([]);
    expect(stored.cookie).not.toContain('vd_refresh');
    await expectAccessible(page, 'account page');
  });

  test('switching the display currency to USD converts money and keeps the order', async () => {
    await open(page, '/results?country=TR');
    await expect(resultRows(page).first()).toBeVisible();
    const before = await rankedNames(page);
    const costCell = resultRows(page).first().locator('td').nth(3);
    await expect(costCell).toContainText('TRY');

    await header(page).getByRole('combobox', { name: 'Show in' }).selectOption('USD');
    await expect(costCell).toContainText('$');
    expect(await rankedNames(page)).toEqual(before);
    await expect(header(page)).toContainText(/rate [A-Z][a-z]{2} \d{1,2}, \d{4}/);
    await expect(costCell.getByRole('button')).toHaveAccessibleDescription(
      /Native amount TRY\s[\d.,]+\s1 TRY = [\d.,]+ USD, ECB rate of /,
    );

    await header(page).getByRole('combobox', { name: 'Show in' }).selectOption('TRY');
    await expect(costCell).toContainText('TRY');
  });

  test('saves GOLD-B and creates a price-drop alert for it', async () => {
    const id = await productId(GOLD_B.code);
    await open(page, `/products/${id}?country=TR`);
    await expect(main(page).getByRole('heading', { level: 1 })).toContainText(GOLD_B.name);
    const actions = main(page).getByRole('group', { name: 'Product actions' });
    await actions.getByRole('button', { name: /^Save/ }).click();
    await expect(actions.getByRole('button', { name: /^Saved/ })).toBeVisible();

    await actions.getByRole('button', { name: 'Create alert' }).click();
    await main(page)
      .getByRole('radio', { name: /The price drops/ })
      .check();
    await main(page).getByLabel('By at least (percent)').fill('5');
    await main(page).getByRole('button', { name: 'Create alert' }).last().click();
    await expect(main(page).getByText(/^Alert created\./)).toBeVisible();

    await open(page, '/account/saved');
    await expect(main(page).getByRole('link', { name: GOLD_B.name })).toBeVisible();
    await expectAccessible(page, 'saved products page');
    await open(page, '/account/alerts');
    const row = main(page).getByRole('row').filter({ hasText: GOLD_B.name });
    await expect(row).toContainText('Price drop');
    await expectAccessible(page, 'alerts page');
  });

  test('FREE plan: the sixth saved product shows the limit notice', async () => {
    await open(page, '/results?country=TR');
    await expect(resultRows(page).first()).toBeVisible();
    const names = (await rankedNames(page)).filter((name) => name !== GOLD_B.name);
    for (const name of names.slice(0, 4)) {
      const save = main(page).getByRole('button', { name: `Save ${name}`, exact: true });
      await save.click();
      await expect(save).toHaveAttribute('aria-pressed', 'true');
    }
    await open(page, '/account/saved');
    await expect(main(page).getByText('5 of 5 saved on FREE')).toBeVisible();

    await open(page, '/results?country=TR');
    await expect(resultRows(page).first()).toBeVisible();
    await main(page)
      .getByRole('button', { name: `Save ${names[4]}`, exact: true })
      .click();
    const notice = main(page).getByText(/^You have saved 5 of 5 products\./);
    await expect(notice).toBeVisible();
    await expect(main(page).getByRole('link', { name: 'See PRO' }).first()).toHaveAttribute(
      'href',
      '/upgrade',
    );
    await expectAccessible(page, 'limit notice');
  });

  test('admin imports the price-drop offers and creates a promo code in the UI', async () => {
    await open(admin, '/auth/login');
    await useLanguage(admin, 'en');
    await main(admin).getByLabel('E-mail').fill(e2eEnv().adminEmail);
    await main(admin).getByLabel('Password').fill(e2eEnv().adminPassword);
    await main(admin).getByRole('button', { name: 'Log in' }).click();
    await expect(header(admin).getByRole('button', { name: /^Account menu/ })).toBeVisible();

    await open(admin, '/admin/imports');
    await expect(main(admin).getByRole('heading', { name: 'Imports', exact: true })).toBeVisible();
    await expectAccessible(admin, 'admin imports page');
    await admin
      .locator('[data-slot="OFFERS_CSV"] input[type="file"]')
      .setInputFiles(PRICE_DROP_CSV);
    const job = main(admin)
      .getByRole('row')
      .filter({ hasText: 'offers.price-drop.synthetic.csv' })
      .first();
    await expect(job).toContainText('Succeeded', { timeout: 30_000 });

    await open(admin, '/admin/promo-codes');
    await expect(
      main(admin).getByRole('heading', { name: 'Promo codes', exact: true }),
    ).toBeVisible();
    await main(admin).getByLabel('Prefix (optional)').fill('E2E');
    await main(admin).getByLabel('Duration (days)').fill('30');
    await main(admin).getByLabel('Max redemptions').fill('1');
    await main(admin).getByRole('button', { name: 'Create code' }).click();
    const created = admin.locator('[data-created] [data-code]');
    await expect(created).toHaveText(/^E2E-[A-Z0-9]+$/);
    promoCode = (await created.innerText()).trim();
    await expectAccessible(admin, 'admin promo codes page');
  });

  test('the price drop reaches the bell, the inbox and the mailbox', async () => {
    const bell = header(page).getByRole('link', { name: /^Notifications, \d+ unread$/ });
    await expect(async () => {
      await open(page, '/');
      await expect(bell).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 90_000, intervals: [2_000, 5_000] });

    await bell.click();
    await expect(page).toHaveURL(/\/account\/notifications/);
    await expect(main(page).getByText(`Price drop: ${GOLD_B.name}`)).toBeVisible();
    await expect(main(page).getByText(/900\.00.*846\.00.*Fixture Pharmacy/)).toBeVisible();
    await expectAccessible(page, 'inbox page');

    await expect
      .poll(async () => (await messagesTo(email)).length, { timeout: 60_000 })
      .toBeGreaterThanOrEqual(2);
  });

  test('after the price drop GOLD-A vs GOLD-B is still unswapped and not worth it', async () => {
    const a = await productId(GOLD_A.code);
    const b = await productId(GOLD_B.code);
    await open(page, `/compare/${a}/${b}?country=TR`);
    await expect(
      main(page)
        .getByRole('region', { name: 'Verdict' })
        .getByText('Not worth it', { exact: true }),
    ).toBeVisible();
    await expect(main(page).getByText('Order switched', { exact: true })).toHaveCount(0);
    await expect(main(page).getByRole('heading', { level: 1 })).toContainText(GOLD_B.name);
  });

  test('redeems the promo code and saves the sixth product on PRO', async () => {
    expect(promoCode).not.toBe('');
    await open(page, '/account');
    await main(page).getByRole('textbox', { name: 'Promo code' }).fill(promoCode);
    await main(page).getByRole('button', { name: 'Redeem' }).click();
    await expect(main(page).getByText(/^PRO is active until /)).toBeVisible();
    await expect(main(page).getByRole('region', { name: 'Your plan' })).toContainText('PRO');

    await open(page, '/results?country=TR');
    await expect(resultRows(page).first()).toBeVisible();
    const unsaved = resultRows(page)
      .locator('button[data-action="save"][aria-pressed="false"]')
      .first();
    const name = (await unsaved.getAttribute('aria-label')) ?? '';
    expect(name).toMatch(/^Save /);
    await unsaved.click();
    await expect(main(page).getByRole('button', { name, exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await open(page, '/account/saved');
    await expect(main(page).getByText('6 saved · unlimited on PRO')).toBeVisible();
  });

  test('switches the language to Turkish and back', async () => {
    await open(page, '/');
    await useLanguage(page, 'tr');
    await expect(main(page).getByRole('button', { name: 'Ürünleri sırala' })).toBeVisible();
    await expect(page).toHaveTitle(/VitaDelta/);
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('lang', 'tr');
    await expect(main(page).getByRole('button', { name: 'Ürünleri sırala' })).toBeVisible();
    await expectAccessible(page, 'query page (tr)');
    await useLanguage(page, 'en');
    await expect(main(page).getByRole('button', { name: 'Rank products' })).toBeVisible();
  });

  test('logs out', async () => {
    await open(page, '/');
    await header(page)
      .getByRole('button', { name: /^Account menu/ })
      .click();
    await page
      .getByRole('menuitem', { name: 'Log out' })
      .or(page.getByRole('button', { name: 'Log out' }))
      .click();
    await expect(header(page).getByRole('link', { name: 'Log in' })).toBeVisible();
    await page.reload();
    await expect(header(page).getByRole('link', { name: 'Log in' })).toBeVisible();
    await page.goto('/account');
    await expect(page).toHaveURL(/\/auth\/login\?returnUrl=/);
  });
});

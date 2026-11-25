import { type Browser, type BrowserContext, type Page, expect, test } from '@playwright/test';

import { chooseMarket, header, main, open, resultRows } from '../support/app';
import { e2eEnv } from '../support/env';
import { messagesTo } from '../support/mailpit';
import {
  ALL_PAYLOADS,
  CHECKED_AT,
  COUNTRY,
  JOINED,
  PAYLOADS,
  type Account,
  type ApiSession,
  type Probe,
  adoptSession,
  apiFetch,
  apiLogin,
  apiPost,
  bearer,
  expectInert,
  importOffersCsv,
  importProducts,
  product,
  productIdOf,
  registerVerifiedAccount,
  sleep,
  watch,
} from './support/attack';

/**
 * Stored XSS through the catalog (project specification §15.5, §15.7 case 8, web part).
 *
 * Two synthetic products of the GB market carry every hostile string in their name, brand and
 * label form text, and their offers in the retailer name. They are cheap enough to rank in the
 * anonymous top three. A verified FREE user saves both and sets a price-drop alert on one; an
 * offers import then drops that price so the alert reaches the inbox and the mailbox. Every
 * page that renders these strings must show them as text and never execute them.
 */

const PRODUCT_A = { code: 'ADV-XSS-A', name: `Adversarial A ${JOINED}` } as const;
const PRODUCT_B = {
  code: 'ADV-XSS-B',
  name: `Adversarial B ${[...ALL_PAYLOADS].reverse().join(' ')}`,
} as const;
const RETAILER_A = `${PAYLOADS.script} ${PAYLOADS.img}`;
const RETAILER_B = `${PAYLOADS.svg} ${PAYLOADS.template}`;

const CSV_HEADER = 'product_code,country_code,retailer,price,currency,in_stock,url,checked_at';

function csvField(value: string): string {
  return `"${value.replace(/"/g, '""')}"`;
}

/** Offer rows with hostile URLs and hostile values in fields that row errors echo. */
const HOSTILE_OFFERS_CSV = [
  CSV_HEADER,
  `${PRODUCT_A.code},GB,${csvField(RETAILER_A)},0.15,GBP,true,javascript:window.__xss=1,${CHECKED_AT}`,
  `${PRODUCT_A.code},GB,Data Store,0.15,GBP,true,${csvField('data:text/html,<script>window.__xss=1</script>')},${CHECKED_AT}`,
  `${PRODUCT_A.code},GB,Relative Store,0.15,GBP,true,/gb/p/relative,${CHECKED_AT}`,
  `${csvField(PAYLOADS.img)},GB,Echo Store,0.15,GBP,true,https://shop.example/a,${CHECKED_AT}`,
  `${PRODUCT_A.code},${csvField(PAYLOADS.svg)},Echo Store,0.15,GBP,true,https://shop.example/a,${CHECKED_AT}`,
  `${PRODUCT_A.code},GB,Echo Store 2,${csvField(PAYLOADS.template)},GBP,true,https://shop.example/a,${CHECKED_AT}`,
  `${PRODUCT_A.code},GB,Echo Store 3,0.15,GBP,true,${csvField(`https://shop.example/${PAYLOADS.script}`)},${CHECKED_AT}`,
].join('\n');

const HOSTILE_FILE_NAME = `${PAYLOADS.img}.csv`;

async function anonymousPage(browser: Browser): Promise<{ context: BrowserContext; probe: Probe }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  return { context, probe: await watch(page) };
}

test.describe.serial('stored XSS through imported catalog data', () => {
  let user: Account;
  let userSession: ApiSession;
  let idA = '';
  let idB = '';
  let userContext: BrowserContext;
  let userPage: Page;
  let userProbe: Probe;

  test.beforeAll(async ({ browser }) => {
    const job = await importProducts({
      schema_version: 1,
      products: [
        product(PRODUCT_A.code, PRODUCT_A.name, RETAILER_A, 0.2),
        product(PRODUCT_B.code, PRODUCT_B.name, RETAILER_B, 0.25),
      ],
    });
    expect(job.status, JSON.stringify(job.errors)).toBe('SUCCEEDED');
    idA = await productIdOf(PRODUCT_A.code, COUNTRY);
    idB = await productIdOf(PRODUCT_B.code, COUNTRY);

    user = await registerVerifiedAccount('xss');
    userSession = await apiLogin(user.email, user.password);
    for (const id of [idA, idB]) {
      const saved = await apiPost('/me/saved-products', userSession.accessToken, {
        productId: id,
        countryCode: COUNTRY,
      });
      expect([200, 201]).toContain(saved.status);
    }
    const alert = await apiPost('/me/alerts', userSession.accessToken, {
      productId: idA,
      countryCode: COUNTRY,
      trigger: 'PRICE_DROP',
      thresholdPercent: 5,
    });
    expect(alert.status).toBe(201);

    userContext = await browser.newContext();
    await adoptSession(userContext, userSession);
    userPage = await userContext.newPage();
    userProbe = await watch(userPage);
  });

  test.afterAll(async () => {
    await userContext.close();
  });

  test('anonymous results and the breakdown drawer render the strings as text', async ({
    browser,
  }) => {
    const { context, probe } = await anonymousPage(browser);
    const page = probe.page;
    await open(page, `/results?country=${COUNTRY}`);
    const row = resultRows(page).filter({ hasText: 'Adversarial A' });
    await expect(row).toHaveCount(1);
    await expectInert(probe, 'anonymous results', [PRODUCT_A.name]);

    await row.getByRole('button', { name: /^Score breakdown of Adversarial A/ }).click();
    const drawer = page.getByRole('dialog', { name: /^Adversarial A .* · score breakdown$/ });
    await expect(drawer).toBeVisible();
    await expectInert(probe, 'breakdown drawer', [PRODUCT_A.name]);
    await context.close();
  });

  test('signed-in results, compare and product detail stay inert', async () => {
    await open(userPage, `/results?country=${COUNTRY}`);
    await expect(header(userPage).getByRole('button', { name: /^Account menu/ })).toBeVisible();
    await expect(resultRows(userPage).filter({ hasText: 'Adversarial B' })).toHaveCount(1);
    await expectInert(userProbe, 'signed-in results', [PRODUCT_A.name, PRODUCT_B.name]);

    await open(userPage, `/compare/${idA}/${idB}?country=${COUNTRY}`);
    await expect(main(userPage)).toContainText('Adversarial B');
    await expectInert(userProbe, 'compare', [PRODUCT_A.name, PRODUCT_B.name]);

    await open(userPage, `/products/${idA}?country=${COUNTRY}`);
    await expect(main(userPage).getByRole('heading', { level: 1 })).toContainText('Adversarial A');
    await expectInert(userProbe, 'product detail', [PRODUCT_A.name, RETAILER_A]);
  });

  test('external offer links are http(s) only and open with noopener noreferrer', async () => {
    await open(userPage, `/products/${idA}?country=${COUNTRY}`);
    await expect(main(userPage).getByRole('heading', { level: 1 })).toContainText('Adversarial A');
    const links = await main(userPage)
      .locator('a[target="_blank"], a[href^="http"]')
      .evaluateAll((anchors) =>
        anchors.map((anchor) => ({
          href: anchor.getAttribute('href') ?? '',
          rel: (anchor.getAttribute('rel') ?? '').split(/\s+/),
          target: anchor.getAttribute('target'),
        })),
      );
    const offers = links.filter((link) => !link.href.startsWith(e2eEnv().baseURL));
    expect(offers.length, 'offer links on the product page').toBeGreaterThan(0);
    for (const link of offers) {
      expect(link.href).toMatch(/^https?:\/\//);
      if (link.target === '_blank') {
        expect(link.rel).toEqual(expect.arrayContaining(['noopener', 'noreferrer']));
      }
    }
  });

  test('saved products and alerts pages stay inert', async () => {
    await open(userPage, '/account/saved');
    await chooseMarket(userPage, COUNTRY);
    await expect(main(userPage).getByRole('link', { name: /^Adversarial A/ })).toBeVisible();
    await expectInert(userProbe, 'saved products', [PRODUCT_A.name, PRODUCT_B.name]);

    await open(userPage, '/account/alerts');
    await expect(main(userPage).getByRole('row').filter({ hasText: 'Adversarial A' })).toHaveCount(
      1,
    );
    await expectInert(userProbe, 'alerts', [PRODUCT_A.name]);
  });

  test('the price-drop notification and its e-mail carry the strings inert', async () => {
    const drop = [
      CSV_HEADER,
      `${PRODUCT_A.code},GB,${csvField(RETAILER_A)},0.10,GBP,true,https://adversarial-shop.example/gb/p/adv-xss-a,${CHECKED_AT}`,
    ].join('\n');
    const job = await importOffersCsv(drop, 'offers.adversarial-drop.synthetic.csv');
    expect(job.status, JSON.stringify(job.errors)).toBe('SUCCEEDED');

    await expect
      .poll(
        async () => {
          const response = await apiFetch(
            '/me/notifications/unread-count',
            bearer(userSession.accessToken),
          );
          return ((await response.json()) as { unreadCount: number }).unreadCount;
        },
        { timeout: 90_000, intervals: [2_000], message: 'price-drop notification' },
      )
      .toBeGreaterThan(0);

    await open(userPage, '/account/notifications');
    await expect(
      main(userPage).getByRole('listitem').filter({ hasText: 'Adversarial A' }),
    ).toHaveCount(1);
    // The template-marker payload is checked on its own in the last test of this file.
    await expectInert(userProbe, 'notification inbox', [
      PAYLOADS.script,
      PAYLOADS.img,
      PAYLOADS.svg,
      PAYLOADS.jsUrl,
    ]);

    let html = '';
    await expect
      .poll(
        async () => {
          const mail = (await messagesTo(user.email)).find((message) =>
            message.Subject.startsWith('Price drop'),
          );
          if (!mail) {
            return false;
          }
          const response = await fetch(`${e2eEnv().mailpitURL}/api/v1/message/${mail.ID}`);
          html = ((await response.json()) as { HTML: string }).HTML;
          return true;
        },
        { timeout: 90_000, intervals: [2_000], message: 'price-drop e-mail' },
      )
      .toBe(true);
    for (const raw of ['<script>window.__xss', '<img src=x', '<svg onload']) {
      expect(html, 'alert e-mail HTML').not.toContain(raw);
    }
    expect(html).toContain('&lt;script&gt;window.__xss=1&lt;/script&gt;');
  });

  test('hostile offer URLs are rejected at import and never become links', async () => {
    const job = await importOffersCsv(HOSTILE_OFFERS_CSV, 'offers.adversarial-hostile.csv');
    expect(job.status).toBe('FAILED');
    const urlErrors = job.errors.filter((error) => error.code === 'invalid_offer_url');
    expect(urlErrors.map((error) => error.rowNumber)).toEqual(expect.arrayContaining([2, 3, 4]));

    const viaJson = await importProducts({
      schema_version: 1,
      products: [
        {
          ...product(PRODUCT_A.code, PRODUCT_A.name, RETAILER_A, 0.1),
          offers: [
            {
              country_code: COUNTRY,
              retailer: 'Script Store',
              price: 0.1,
              currency: 'GBP',
              in_stock: true,
              url: 'javascript:window.__xss=1',
              checked_at: CHECKED_AT,
            },
          ],
        },
      ],
    });
    expect(viaJson.status).toBe('FAILED');

    await open(userPage, `/products/${idA}?country=${COUNTRY}`);
    await expect(main(userPage).getByRole('heading', { level: 1 })).toContainText('Adversarial A');
    const hrefs = await userPage
      .locator('a[href]')
      .evaluateAll((anchors) => anchors.map((anchor) => anchor.getAttribute('href') ?? ''));
    expect(hrefs.filter((href) => /^(javascript|data):|relative/i.test(href))).toEqual([]);
    await expect(main(userPage)).not.toContainText('Script Store');
  });

  test('the admin job-error table shows echoed hostile input as text', async ({ browser }) => {
    const env = e2eEnv();
    const context = await browser.newContext();
    await adoptSession(context, await apiLogin(env.adminEmail, env.adminPassword));
    const page = await context.newPage();
    const probe = await watch(page);
    await open(page, '/admin/imports');
    await page.locator('[data-slot="OFFERS_CSV"] input[type="file"]').setInputFiles({
      name: HOSTILE_FILE_NAME,
      mimeType: 'text/csv',
      buffer: Buffer.from(HOSTILE_OFFERS_CSV, 'utf8'),
    });
    const job = main(page).getByRole('row').filter({ hasText: HOSTILE_FILE_NAME });
    await expect(job).toContainText('Failed', { timeout: 30_000 });
    const details = job.getByRole('button', { name: 'Details' });
    // The page may open the errors of a failed job by itself; open them only if it did not.
    await sleep(1_000);
    if ((await details.getAttribute('aria-expanded')) !== 'true') {
      await details.click();
    }
    await expect(details).toHaveAttribute('aria-expanded', 'true');
    const errors = page.locator('[data-job-errors]');
    await expect(errors).toContainText('unknown_product_code');
    // The API echoes the hostile values in each error message; the table shows a localized
    // text per error code instead, so only the hostile file name appears on screen.
    await expect(errors).toContainText('unknown_country');
    await expect(errors).toContainText('invalid_price');
    await expectInert(probe, 'admin job errors', [HOSTILE_FILE_NAME]);
    await sleep(500);
    await expectInert(probe, 'admin job errors (after render settles)');
    await context.close();
  });

  test('the inbox shows the template-marker payload verbatim', async () => {
    await open(userPage, '/account/notifications');
    const item = main(userPage).getByRole('listitem').filter({ hasText: 'Adversarial A' });
    await expect(item).toHaveCount(1);
    await expect(item.getByRole('link')).toHaveText(`Price drop: ${PRODUCT_A.name}`);
    await expectInert(userProbe, 'notification inbox (template marker)', [PAYLOADS.template]);
  });
});

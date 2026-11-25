import { GOLD_A, GOLD_B, chooseMarket, main, open, productId } from './support/app';
import { expect, expectAccessible, test } from './support/fixtures';

test.describe('public pages', () => {
  test('compare GOLD-A vs GOLD-B is not worth it, indicative and with the disclaimer', async ({
    page,
  }) => {
    const a = await productId(GOLD_A.code);
    const b = await productId(GOLD_B.code);
    await open(page, '/');
    await chooseMarket(page, 'TR');
    await open(page, `/compare/${a}/${b}?country=TR`);

    const verdict = main(page).getByRole('region', { name: 'Verdict' });
    await expect(verdict.getByText('Not worth it', { exact: true })).toBeVisible();
    await expect(main(page).getByText('Verdict for B over A · indicative')).toBeVisible();
    await expect(
      main(page).getByText(/^The verdict is indicative: it compares label contents/),
    ).toBeVisible();
    await expect(
      main(page).getByText(/^This comparison is based on label contents and demo prices\./),
    ).toBeVisible();
    await expect(main(page).getByText('Order switched', { exact: true })).toHaveCount(0);
    await expectAccessible(page, 'compare page');
  });

  test('compare opened in the other order explains the swap', async ({ page }) => {
    const a = await productId(GOLD_A.code);
    const b = await productId(GOLD_B.code);
    await open(page, '/');
    await chooseMarket(page, 'TR');
    await open(page, `/compare/${b}/${a}?country=TR`);
    await expect(main(page).getByText('Order switched', { exact: true })).toBeVisible();
    await expect(
      main(page)
        .getByRole('region', { name: 'Verdict' })
        .getByText('Not worth it', { exact: true }),
    ).toBeVisible();
  });

  test('product detail shows the formulation, offers and the disclaimer', async ({ page }) => {
    const b = await productId(GOLD_B.code);
    await open(page, '/');
    await chooseMarket(page, 'TR');
    await open(page, `/products/${b}?country=TR`);
    await expect(main(page).getByRole('heading', { level: 1 })).toContainText(GOLD_B.name);
    await expect(main(page).getByText('Fixture Pharmacy').first()).toBeVisible();
    await expect(
      main(page).getByText(/^This comparison is based on label contents and demo prices\./),
    ).toBeVisible();
    await expectAccessible(page, 'product page');
  });

  test('privacy notice renders the contact address', async ({ page }) => {
    const legal = (await (await page.request.get('/api/v1/legal')).json()) as {
      privacyContact: string;
    };
    await open(page, '/privacy');
    await expect(main(page).getByRole('heading', { level: 1 })).toBeVisible();
    await expect(main(page).getByText(legal.privacyContact, { exact: true })).toBeVisible();
    await expectAccessible(page, 'privacy page');
  });
  test('serves the app under the strict Content Security Policy', async ({ page }) => {
    const response = await page.goto('/');
    const csp = response?.headers()['content-security-policy'] ?? '';
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    const verify = await page.request.get('/auth/verify');
    expect(verify.headers()['referrer-policy']).toBe('no-referrer');
  });

  test('upgrade and not-found pages render accessibly', async ({ page }) => {
    await open(page, '/upgrade');
    await expect(main(page).getByRole('heading', { level: 1 })).toBeVisible();
    await expect(main(page).getByText('PRO').first()).toBeVisible();
    await expectAccessible(page, 'upgrade page');
    await open(page, '/no-such-page');
    await expect(
      main(page).getByRole('heading', { name: 'This page does not exist' }),
    ).toBeVisible();
    await expectAccessible(page, 'not-found page');
  });
});

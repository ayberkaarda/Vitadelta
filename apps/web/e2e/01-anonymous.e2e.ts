import {
  chooseMarket,
  expectNoHorizontalScroll,
  header,
  main,
  marketCodes,
  open,
  rankProducts,
  resultRows,
  resultsHeading,
} from './support/app';
import { expect, expectAccessible, test } from './support/fixtures';

test.describe('anonymous visitor', () => {
  test('sees three results and locked rows until the free queries run out', async ({ page }) => {
    await open(page, '/');
    await chooseMarket(page, 'TR');
    const chip = header(page).getByRole('status');
    await expect(chip).toHaveText('3/3 free queries left');
    await expectAccessible(page, 'query page');

    await rankProducts(page);
    await expect(resultsHeading(page)).toHaveText(/\d+ products ranked/);
    await expect(resultRows(page)).toHaveCount(3);
    await expect(chip).toHaveText('2/3 free queries left');

    const locked = main(page).getByRole('list', { name: 'Locked results' });
    await expect(locked.getByRole('listitem').first()).toHaveAccessibleName('Result 4, locked');
    const lockedHtml = await locked.innerHTML();
    for (const code of await marketCodes('TR')) {
      expect(lockedHtml, `locked rows must not carry ${code}`).not.toContain(code);
    }
    expect(lockedHtml).not.toContain('/products/');
    await expect(main(page).getByRole('link', { name: 'Create free account' })).toHaveAttribute(
      'href',
      '/auth/register',
    );
    await expectAccessible(page, 'results page (anonymous)');

    await main(page).getByRole('link', { name: 'Edit query' }).click();
    await rankProducts(page);
    await expect(resultRows(page)).toHaveCount(3);
    await expect(chip).toHaveText('1/3 free query left');

    await main(page).getByRole('link', { name: 'Edit query' }).click();
    await rankProducts(page);
    await expect(resultRows(page)).toHaveCount(3);
    await expect(chip).toHaveText('No free queries left · Sign up');

    await main(page).getByRole('link', { name: 'Edit query' }).click();
    await expect(main(page).getByText('You have no free queries left.')).toBeVisible();
    await rankProducts(page);
    await expect(
      main(page).getByRole('heading', { name: 'You have used your 3 free queries' }),
    ).toBeVisible();
    await expect(resultRows(page)).toHaveCount(0);
    await expectAccessible(page, 'quota exhausted');
  });

  test('filters the query and opens the score breakdown', async ({ page }) => {
    await open(page, '/');
    await chooseMarket(page, 'TR');
    await main(page).getByRole('radio', { name: 'Include out of stock' }).check();
    await main(page)
      .getByRole('radio', { name: /^Richness/ })
      .check();
    await rankProducts(page);
    await expect(page).toHaveURL(/stock=all/);
    await expect(page).toHaveURL(/mode=RICHNESS/);
    await expect(resultsHeading(page)).toHaveText(/\d+ products ranked/);
    await expect(main(page).getByText('Including out of stock')).toBeVisible();

    const first = resultRows(page).first();
    const name = (await first.locator('td a[href^="/products/"]').innerText()).trim();
    const opener = first.getByRole('button', { name: `Score breakdown of ${name}` });
    await opener.click();
    const drawer = page.getByRole('dialog', { name: `${name} · score breakdown` });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText(/Reference: .+ for Türkiye/)).toBeVisible();
    await expect(drawer.getByText('Content score', { exact: false }).first()).toBeVisible();
    await expectAccessible(page, 'breakdown drawer');
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await expect(opener).toBeFocused();
  });

  test('runs query, results and breakdown with the keyboard only', async ({ page }) => {
    await open(page, '/');
    await chooseMarket(page, 'TR');
    await page.goto('/?country=TR');
    await expect(main(page).getByRole('button', { name: 'Rank products' })).toBeVisible();

    const submit = main(page).getByRole('button', { name: 'Rank products' });
    for (let step = 0; step < 80 && !(await isFocused(submit)); step += 1) {
      await page.keyboard.press('Tab');
    }
    await expect(submit).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/results\?/);
    await expect(resultRows(page)).toHaveCount(3);

    const breakdown = resultRows(page)
      .first()
      .getByRole('button', { name: /^Score breakdown of/ });
    for (let step = 0; step < 80 && !(await isFocused(breakdown)); step += 1) {
      await page.keyboard.press('Tab');
    }
    await expect(breakdown).toBeFocused();
    await page.keyboard.press('Enter');
    const drawer = page.getByRole('dialog');
    await expect(drawer).toBeVisible();
    await expect(drawer.locator(':focus')).toHaveCount(1);
    await page.keyboard.press('Tab');
    await expect(drawer.locator(':focus')).toHaveCount(1);
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await expect(breakdown).toBeFocused();
  });

  test('works at 375 px without horizontal scrolling', async ({ page }) => {
    await open(page, '/');
    await chooseMarket(page, 'TR');
    await page.setViewportSize({ width: 375, height: 812 });
    await expectNoHorizontalScroll(page);
    await rankProducts(page);
    await expect(resultsHeading(page)).toHaveText(/\d+ products ranked/);
    await expect(
      main(page)
        .getByRole('button', { name: /^Score breakdown of/ })
        .first(),
    ).toBeVisible();
    await expectNoHorizontalScroll(page);
    await expectAccessible(page, 'results at 375 px');
    for (const path of ['/privacy', '/auth/login', '/auth/register', '/upgrade']) {
      await open(page, path);
      await expect(main(page).getByRole('heading', { level: 1 })).toBeVisible();
      await expectNoHorizontalScroll(page);
    }
  });
});

async function isFocused(locator: import('@playwright/test').Locator): Promise<boolean> {
  return locator.evaluate((element) => element === document.activeElement).catch(() => false);
}

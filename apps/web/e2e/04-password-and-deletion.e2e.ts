import type { BrowserContext, Page } from '@playwright/test';

import { PASSWORD, chooseMarket, header, main, open, uniqueEmail } from './support/app';
import { type PageMonitor, expect, expectAccessible, test, watchPage } from './support/fixtures';
import { linkIn, waitForMail } from './support/mailpit';

const NEW_PASSWORD = 'Juniper-Compass-Meadow-58';

async function logIn(page: Page, email: string, password: string): Promise<void> {
  await open(page, '/auth/login');
  await main(page).getByLabel('E-mail').fill(email);
  await main(page).getByLabel('Password').fill(password);
  await main(page).getByRole('button', { name: 'Log in' }).click();
}

/** Password reset through the e-mail link, then deleting the account. */
test.describe.serial('password reset and account deletion', () => {
  const email = uniqueEmail('reset');
  let context: BrowserContext;
  let page: Page;
  let monitor: PageMonitor;

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext();
    page = await context.newPage();
    monitor = await watchPage(page);
  });

  test.afterEach(() => {
    monitor.expectClean();
  });

  test.afterAll(async () => {
    await context.close();
  });

  test('registers and verifies a second account', async () => {
    await open(page, '/');
    await chooseMarket(page, 'TR');
    await open(page, '/auth/register');
    await main(page).getByLabel('E-mail').fill(email);
    await main(page).getByLabel('Password').fill(PASSWORD);
    await main(page).getByRole('button', { name: 'Create account' }).click();
    await expect(main(page).getByRole('heading', { name: 'Check your e-mail' })).toBeVisible();
    await page.goto(linkIn(await waitForMail(email, /Confirm your VitaDelta/), '/auth/verify'));
    await expect(main(page).getByRole('heading', { name: 'E-mail verified' })).toBeVisible();
  });

  test('resets the password through the e-mail link', async () => {
    await open(page, '/auth/login');
    await main(page).getByRole('link', { name: 'Forgot password?' }).click();
    await expect(page).toHaveURL(/\/auth\/forgot/);
    await expectAccessible(page, 'forgot page');
    await main(page).getByLabel('E-mail').fill(email);
    await main(page).getByRole('button', { name: 'Send reset link' }).click();
    await expect(
      main(page).getByText('If an account exists for this address, a reset link has been sent.'),
    ).toBeVisible();

    const link = linkIn(await waitForMail(email, /password/i), '/auth/reset');
    expect(link).toMatch(/\/auth\/reset#token=[A-Za-z0-9_-]+$/);
    await page.goto(link);
    await expect(main(page).getByRole('heading', { name: 'Choose a new password' })).toBeVisible();
    expect(page.url()).not.toContain('token');
    await expectAccessible(page, 'reset page');
    await main(page).getByLabel('New password', { exact: true }).fill(NEW_PASSWORD);
    await main(page).getByLabel('Repeat password', { exact: true }).fill(NEW_PASSWORD);
    await main(page).getByRole('button', { name: 'Save password' }).click();
    await expect(page).toHaveURL(/\/auth\/login/);
    await expect(
      main(page).getByText('Your password was changed. Log in with the new password.'),
    ).toBeVisible();

    await logIn(page, email, PASSWORD);
    await expect(main(page).getByText('E-mail or password is not correct.')).toBeVisible();
    await expect(header(page).getByRole('button', { name: /^Account menu/ })).toHaveCount(0);

    await logIn(page, email, NEW_PASSWORD);
    await expect(header(page).getByRole('button', { name: /^Account menu/ })).toBeVisible();
  });

  test('deletes the account after confirming with the password', async () => {
    await open(page, '/account');
    await main(page).getByRole('button', { name: 'Delete account…' }).click();
    const dialog = page.getByRole('dialog', { name: 'Delete your account?' });
    await expect(dialog).toBeVisible();
    await expectAccessible(page, 'delete account dialog');
    await dialog.getByLabel('Confirm with your password').fill(NEW_PASSWORD);
    await dialog.getByRole('button', { name: 'Delete my account' }).click();
    await expect(dialog).toBeHidden();
    await expect(header(page).getByRole('link', { name: 'Log in' })).toBeVisible();

    await logIn(page, email, NEW_PASSWORD);
    await expect(header(page).getByRole('button', { name: /^Account menu/ })).toHaveCount(0);
    await expect(main(page).getByText('E-mail or password is not correct.')).toBeVisible();
  });
});

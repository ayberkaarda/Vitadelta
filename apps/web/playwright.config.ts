import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end suite against the running Docker Compose stack (nginx on one origin with the
 * API). Start it first with `scripts/dev-up.sh` and `scripts/seed.sh`; see the README.
 *
 * The flows share one stack and depend on each other's server state (rate limits per client
 * IP, the price-drop import, the catalog import), so they run in one worker in file order.
 */
const baseURL = process.env['E2E_BASE_URL'] ?? 'http://localhost:8080';

export default defineConfig({
  testDir: './e2e',
  outputDir: './e2e/.output/results',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: 1,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: './e2e/.output/report' }]],
  use: {
    baseURL,
    headless: true,
    locale: 'en-US',
    timezoneId: 'UTC',
    trace: process.env['CI'] ? 'off' : 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: 'e2e',
      testMatch: '**/*.e2e.ts',
      testIgnore: 'adversarial/**',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'adversarial',
      testDir: './e2e/adversarial',
      testMatch: '**/*.adversarial.ts',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      // README screenshots (`npm run e2e:screenshots`); never part of the suites above.
      name: 'screenshots',
      testMatch: 'screenshots.ts',
      retries: 0,
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1280, height: 800 },
        deviceScaleFactor: 1,
        colorScheme: 'light',
      },
    },
  ],
});

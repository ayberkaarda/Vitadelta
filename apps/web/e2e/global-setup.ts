import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { e2eEnv } from './support/env';

const SEEDED_OFFERS = resolve(
  __dirname,
  '..',
  '..',
  '..',
  'data',
  'fixtures',
  'offers.synthetic.csv',
);

/**
 * Checks that the stack is up and seeded, then, as the bootstrap admin, refreshes the exchange
 * rates (the refresh job otherwise runs daily) and re-imports the seeded offers so the
 * price-drop flow starts from the seeded GOLD-B price even after an earlier run.
 */
export default async function globalSetup(): Promise<void> {
  const env = e2eEnv();

  const health = await fetch(`${env.apiBase}/health`).catch((error: unknown) => {
    throw new Error(
      `The stack is not reachable at ${env.baseURL} (${String(error)}). ` +
        'Start it with scripts/dev-up.sh and seed it with scripts/seed.sh.',
    );
  });
  if (!health.ok) {
    throw new Error(`GET /api/v1/health answered ${health.status}`);
  }

  const products = await fetch(`${env.apiBase}/products?country=TR&q=GOLD`);
  const catalog = (await products.json()) as { meta: { total: number } };
  if (catalog.meta.total < 2) {
    throw new Error('The catalog has no GOLD-A/GOLD-B fixtures: run scripts/seed.sh first.');
  }

  const mailpit = await fetch(`${env.mailpitURL}/api/v1/info`).catch(() => null);
  if (!mailpit?.ok) {
    throw new Error(`Mailpit is not reachable at ${env.mailpitURL}`);
  }

  const login = await fetch(`${env.apiBase}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: env.adminEmail, password: env.adminPassword }),
  });
  if (!login.ok) {
    throw new Error(
      `Bootstrap admin login failed with ${login.status}; the stack must be started with ` +
        'the same admin credentials.',
    );
  }
  const { accessToken } = (await login.json()) as { accessToken: string };
  const refresh = await fetch(`${env.apiBase}/admin/fx/refresh`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!refresh.ok) {
    throw new Error(`POST /api/v1/admin/fx/refresh answered ${refresh.status}`);
  }
  const result = (await refresh.json()) as { upsertedRows: number; failedBases: string[] };
  if (result.failedBases.length > 0) {
    throw new Error(`FX refresh failed for ${result.failedBases.join(', ')}`);
  }

  const form = new FormData();
  form.append(
    'file',
    new Blob([readFileSync(SEEDED_OFFERS)], { type: 'text/csv' }),
    'offers.synthetic.csv',
  );
  const offers = await fetch(`${env.apiBase}/admin/imports/offers`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}` },
    body: form,
  });
  if (!offers.ok) {
    throw new Error(`POST /api/v1/admin/imports/offers answered ${offers.status}`);
  }
  const job = (await offers.json()) as { id: string };
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const status = await fetch(`${env.apiBase}/admin/imports/${job.id}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const body = (await status.json()) as { status: string };
    if (body.status === 'SUCCEEDED') {
      return;
    }
    if (body.status === 'FAILED') {
      throw new Error('Re-importing the seeded offers failed');
    }
    await new Promise((done) => setTimeout(done, 500));
  }
  throw new Error('Re-importing the seeded offers did not finish within 30 s');
}

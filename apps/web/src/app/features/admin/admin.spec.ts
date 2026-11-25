import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';

import adminEn from '../../../../public/i18n/admin/en.json';
import adminTr from '../../../../public/i18n/admin/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import {
  AdminUser,
  AdminUserPage,
  ImportJob,
  ImportJobDetail,
  PromoCode,
} from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { fixtureUser } from '../../testing/api-fixtures';
import { provideTestLocale } from '../../testing/test-providers';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { AdminImportsPageComponent } from './admin-imports-page.component';
import { AdminLayoutComponent } from './admin-layout.component';
import { AdminPromoCodesPageComponent } from './admin-promo-codes-page.component';
import { AdminUsersPageComponent } from './admin-users-page.component';
import { IMPORT_POLL_INTERVAL_MS, ImportJobsStore } from './import-jobs.store';

async function settle(fixture: ComponentFixture<unknown>, rounds = 3): Promise<void> {
  for (let round = 0; round < rounds; round++) {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  await fixture.whenStable();
}

function configure(
  api: Record<string, unknown>,
  userId = 'admin-id',
  generation = signal(1),
  admin = signal(true),
) {
  TestBed.configureTestingModule({
    imports: [translocoTesting({ scopes: { admin: { en: adminEn, tr: adminTr } } })],
    providers: [
      provideRouter([]),
      provideTestLocale('en-US'),
      { provide: ApiClient, useValue: api },
      { provide: IMPORT_POLL_INTERVAL_MS, useValue: 0 },
      {
        provide: SessionStore,
        useValue: {
          user: signal(fixtureUser({ id: userId, role: 'ADMIN' })),
          generation,
          isAdmin: admin,
        },
      },
    ],
  });
}

function choose(host: HTMLElement, id: string, file: File): void {
  const input = host.querySelector<HTMLInputElement>(`#${id}`);
  if (!input) {
    throw new Error(`missing #${id}`);
  }
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new Event('change'));
}

describe('AdminImportsPageComponent', () => {
  const RUNNING: ImportJob = {
    id: 'job-1',
    kind: 'OFFERS_CSV',
    status: 'RUNNING',
    createdAt: '2026-11-25T09:51:00Z',
    finishedAt: null,
    summary: null,
  };

  it('polls an offers import until it fails and shows the row errors as plain text', async () => {
    const failed: ImportJobDetail = {
      ...RUNNING,
      status: 'FAILED',
      finishedAt: '2026-11-25T09:51:30Z',
      summary: { rows: 412, inserted: 0, updated: 0, unchanged: 0, deleted: 0, changedPairs: 0 },
      errors: [
        {
          rowNumber: 17,
          code: 'currency_mismatch',
          message: 'currency_mismatch: EUR offer for country TR (row 17)',
        },
        {
          rowNumber: 88,
          message: 'invalid_offer_url: <img src=x onerror=alert(1)> (row 88)',
        },
      ],
    };
    const getImportJob = vi
      .fn()
      .mockReturnValueOnce(of({ ...RUNNING, errors: [] }))
      .mockReturnValueOnce(of(failed));
    const importOffers = vi.fn(() => of(RUNNING));
    configure({ importOffers, importProducts: vi.fn(), getImportJob });
    const fixture = TestBed.createComponent(AdminImportsPageComponent);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain(adminEn.imports.no_jobs);

    const file = new File(['product_code,retailer\n'], 'offers-tr.csv', { type: 'text/csv' });
    choose(host, 'import-offers', file);
    await settle(fixture, 8);

    expect(importOffers).toHaveBeenCalledWith(file, 'offers-tr.csv');
    expect(getImportJob).toHaveBeenCalledTimes(2);
    const row = host.querySelector('tr[data-job="job-1"]');
    expect(row?.getAttribute('data-status')).toBe('FAILED');
    expect(row?.textContent).toContain('412 rows read');
    expect(row?.textContent).toContain(adminEn.imports.summary.nothing_written);

    const panel = host.querySelector('[data-job-errors]');
    expect(panel?.textContent).toContain('2 errors');
    const cells = Array.from(panel?.querySelectorAll('tbody tr') ?? []).map((tr) =>
      Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.trim()),
    );
    expect(cells[0]).toEqual([
      '17',
      'currency_mismatch',
      'currency',
      'The currency is not the currency of the country.',
    ]);
    expect(cells[1]).toEqual([
      '88',
      'invalid_offer_url',
      'url',
      'The offer URL is not an absolute http or https address.',
    ]);
    expect(panel?.textContent).not.toContain('EUR offer for country');
    expect(panel?.textContent).not.toContain('onerror');
    expect(panel?.querySelector('img')).toBeNull();
    expect(panel?.textContent).toContain(adminEn.imports.rows_note_csv);
  });

  it('translates allow-listed error codes and never renders the server message', async () => {
    const failed: ImportJobDetail = {
      ...RUNNING,
      id: 'job-9',
      kind: 'PRODUCTS_JSON',
      status: 'FAILED',
      finishedAt: '2026-11-25T09:51:30Z',
      summary: { products: 4, inserted: 0, updated: 0, unchanged: 0, deleted: 0, changedPairs: 0 },
      errors: [
        {
          rowNumber: 3,
          code: 'schema_violation',
          message:
            'schema_violation: expected number, got string (products[2].nutrients[0].amount_per_serving)',
        },
        { rowNumber: 5, code: 'mystery_code', message: 'mystery_code: internal detail 42 (row 5)' },
        { rowNumber: 1, message: 'Something odd happened at /srv/app (row 1)' },
      ],
    };
    const importProducts = vi.fn(() => of({ ...RUNNING, id: 'job-9', kind: 'PRODUCTS_JSON' }));
    configure({ importProducts, importOffers: vi.fn(), getImportJob: vi.fn(() => of(failed)) });
    const fixture = TestBed.createComponent(AdminImportsPageComponent);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    const file = new File(['{}'], 'products.json', { type: 'application/json' });
    choose(host, 'import-products', file);
    await settle(fixture, 6);

    const panel = host.querySelector('[data-job-errors]');
    const cells = Array.from(panel?.querySelectorAll('tbody tr') ?? []).map((tr) =>
      Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.trim()),
    );
    expect(cells).toEqual([
      [
        '3',
        'schema_violation',
        'products[2].nutrients[0].amount_per_serving',
        'The product does not match the import schema.',
      ],
      ['5', 'mystery_code', '—', 'This entry could not be imported.'],
      ['1', '—', '—', 'This entry could not be imported.'],
    ]);
    const shown = panel?.textContent ?? '';
    expect(shown).not.toContain('expected number');
    expect(shown).not.toContain('internal detail');
    expect(shown).not.toContain('/srv/app');
  });

  it('forgets the jobs and stops polling when the session changes', async () => {
    const generation = signal(1);
    const getImportJob = vi.fn(() => of({ ...RUNNING, errors: [] }));
    configure(
      { importOffers: vi.fn(() => of(RUNNING)), importProducts: vi.fn(), getImportJob },
      'admin-id',
      generation,
    );
    const store = TestBed.inject(ImportJobsStore);
    await store.upload('OFFERS_CSV', new File(['x'], 'offers.csv', { type: 'text/csv' }));
    expect(store.jobs()).toHaveLength(1);
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(getImportJob).toHaveBeenCalled();

    generation.set(2);
    expect(store.jobs()).toEqual([]);
    const calls = getImportJob.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(getImportJob).toHaveBeenCalledTimes(calls);
    expect(store.jobs()).toEqual([]);
  });

  it('polls a products import until it succeeds and shows the summary', async () => {
    const running: ImportJob = { ...RUNNING, id: 'job-2', kind: 'PRODUCTS_JSON' };
    const done: ImportJobDetail = {
      ...running,
      status: 'SUCCEEDED',
      finishedAt: '2026-11-24T16:02:10Z',
      summary: {
        products: 48,
        inserted: 3,
        updated: 11,
        unchanged: 34,
        deleted: 0,
        changedPairs: 6,
      },
      errors: [],
    };
    const importProducts = vi.fn(() => of(running));
    const getImportJob = vi.fn(() => of(done));
    configure({ importProducts, importOffers: vi.fn(), getImportJob });
    const fixture = TestBed.createComponent(AdminImportsPageComponent);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    const file = new File(['{"products":[]}'], 'products.json', { type: 'application/json' });
    choose(host, 'import-products', file);
    await settle(fixture, 6);

    expect(importProducts).toHaveBeenCalledWith(file);
    expect(getImportJob).toHaveBeenCalledWith('job-2');
    const row = host.querySelector('tr[data-job="job-2"]');
    expect(row?.getAttribute('data-status')).toBe('SUCCEEDED');
    expect(row?.textContent?.replace(/\s+/g, ' ')).toContain(
      '48 products · 3 inserted · 11 updated · 34 unchanged · 0 deleted · 6 changed pairs',
    );
    expect(host.querySelector('[data-job-errors]')).toBeNull();

    row?.querySelector<HTMLButtonElement>('button[aria-expanded]')?.click();
    await settle(fixture);
    expect(host.querySelector('[data-job-errors]')?.textContent).toContain(
      adminEn.imports.no_errors,
    );
  });

  it('shows the size hint warning and an upload problem', async () => {
    const importOffers = vi.fn(() =>
      throwError(() => new ApiProblem({ kind: 'http', status: 413, code: null })),
    );
    configure({ importOffers, importProducts: vi.fn(), getImportJob: vi.fn() });
    const fixture = TestBed.createComponent(AdminImportsPageComponent);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    const big = new File(['x'], 'big.csv', { type: 'text/csv' });
    Object.defineProperty(big, 'size', { value: 21 * 1024 * 1024 });
    choose(host, 'import-offers', big);
    await settle(fixture);
    const slot = host.querySelector('[data-slot="OFFERS_CSV"]');
    expect(slot?.querySelector('[data-size-warning]')).not.toBeNull();
    expect(slot?.textContent).toContain('21.0 MB');
    expect(slot?.textContent).toContain(EN.problem.generic.title);
  });
});

describe('AdminPromoCodesPageComponent', () => {
  const EXISTING: PromoCode = {
    code: 'PRESS-4T8W2K6N9Q3R7M1X',
    planCode: 'PRO',
    durationDays: 90,
    maxRedemptions: 10,
    redeemedCount: 10,
    expiresAt: null,
    createdBy: null,
  };
  const CREATED: PromoCode = {
    code: 'LAUNCH-7K3M9QXT2B4N6PRW',
    planCode: 'PRO',
    durationDays: 30,
    maxRedemptions: 100,
    redeemedCount: 0,
    expiresAt: '2026-12-31T23:59:59Z',
    createdBy: 'admin-id',
  };

  async function setup(api: Record<string, unknown>) {
    configure({ listPromoCodes: vi.fn(() => of({ items: [EXISTING] })), ...api });
    const fixture = TestBed.createComponent(AdminPromoCodesPageComponent);
    await settle(fixture);
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  function type(host: HTMLElement, id: string, value: string, event = 'input'): void {
    const input = host.querySelector<HTMLInputElement>(`#${id}`);
    if (input) {
      input.value = value;
      input.dispatchEvent(new Event(event));
    }
  }

  it('creates a code, shows it once with a copy button and lists it', async () => {
    const createPromoCode = vi.fn(() => of(CREATED));
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const { fixture, host } = await setup({ createPromoCode });
    expect(host.querySelector('[data-code-row="PRESS-4T8W2K6N9Q3R7M1X"]')?.textContent).toContain(
      adminEn.promo.used_up,
    );

    type(host, 'promo-prefix', 'launch');
    type(host, 'promo-expires', '2026-12-31', 'change');
    host.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(createPromoCode).toHaveBeenCalledWith({
      planCode: 'PRO',
      durationDays: 30,
      maxRedemptions: 100,
      expiresAt: '2026-12-31T23:59:59Z',
      prefix: 'LAUNCH',
    });
    const created = host.querySelector('[data-created]');
    expect(created?.querySelector('[data-code]')?.textContent).toBe(CREATED.code);
    created?.querySelector<HTMLButtonElement>('button')?.click();
    await settle(fixture);
    expect(writeText).toHaveBeenCalledWith(CREATED.code);
    expect(host.querySelector('[data-created]')?.textContent).toContain(adminEn.promo.copied);
    expect(host.querySelector(`[data-code-row="${CREATED.code}"]`)).not.toBeNull();

    const dismiss = Array.from(created?.querySelectorAll('button') ?? []).find((b) =>
      b.textContent?.includes(adminEn.promo.dismiss),
    );
    dismiss?.click();
    await settle(fixture);
    expect(host.querySelector('[data-created]')).toBeNull();
  });

  it('shows not_in_future on the expiry field and validates numbers on the client', async () => {
    const createPromoCode = vi.fn(() =>
      throwError(
        () =>
          new ApiProblem({
            kind: 'problem',
            status: 422,
            code: 'validation_failed',
            errors: [{ field: 'expiresAt', code: 'not_in_future', message: 'x' }],
          }),
      ),
    );
    const { fixture, host } = await setup({ createPromoCode });
    type(host, 'promo-duration', '0');
    host.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(createPromoCode).not.toHaveBeenCalled();
    expect(host.querySelector('#promo-duration')?.getAttribute('aria-invalid')).toBe('true');

    type(host, 'promo-duration', '30');
    type(host, 'promo-expires', '2026-11-01', 'change');
    host.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(host.querySelector('#promo-expires-error')?.textContent).toContain(
      EN.validation.not_in_future,
    );
  });

  it('deletes a code only after the inline confirmation', async () => {
    const deletePromoCode = vi.fn(() => of(undefined));
    const { fixture, host } = await setup({ deletePromoCode });
    host
      .querySelector<HTMLButtonElement>('button[aria-label="Delete PRESS-4T8W2K6N9Q3R7M1X"]')
      ?.click();
    await settle(fixture);
    expect(deletePromoCode).not.toHaveBeenCalled();
    host.querySelector<HTMLButtonElement>('[data-confirm]')?.click();
    await settle(fixture);
    expect(deletePromoCode).toHaveBeenCalledWith('PRESS-4T8W2K6N9Q3R7M1X');
    expect(host.querySelector('[data-code-row]')).toBeNull();
  });
});

describe('AdminUsersPageComponent', () => {
  const USERS: AdminUser[] = [
    {
      id: 'u1',
      email: 'user1@example.com',
      role: 'USER',
      status: 'ACTIVE',
      plan: 'FREE',
      emailVerified: true,
      createdAt: '2026-11-02T10:00:00Z',
    },
    {
      id: 'u3',
      email: 'user3@example.com',
      role: 'USER',
      status: 'DISABLED',
      plan: 'FREE',
      emailVerified: false,
      createdAt: '2026-11-09T10:00:00Z',
    },
    {
      id: 'admin-id',
      email: 'admin@example.com',
      role: 'ADMIN',
      status: 'ACTIVE',
      plan: 'FREE',
      emailVerified: true,
      createdAt: '2026-11-01T10:00:00Z',
    },
  ];
  const PAGE: AdminUserPage = { meta: { page: 1, pageSize: 20, total: 1204 }, items: USERS };

  async function setup(api: Record<string, unknown>) {
    const listUsers = vi.fn(() => of(PAGE));
    configure({ listUsers, ...api });
    const fixture = TestBed.createComponent(AdminUsersPageComponent);
    await settle(fixture);
    return { fixture, host: fixture.nativeElement as HTMLElement, listUsers };
  }
  const row = (host: HTMLElement, email: string) => host.querySelector(`tr[data-user="${email}"]`);

  it('lists users, hides actions on the own account and searches', async () => {
    const { fixture, host, listUsers } = await setup({});
    expect(listUsers).toHaveBeenCalledWith({ page: 1, pageSize: 20 });
    expect(host.querySelector('[data-total]')?.textContent).toContain('1,204 users');
    expect(row(host, 'admin@example.com')?.textContent).toContain(adminEn.users.self);
    expect(row(host, 'admin@example.com')?.querySelector('button')).toBeNull();

    const search = host.querySelector<HTMLInputElement>('#user-search');
    if (search) {
      search.value = 'user3';
      search.dispatchEvent(new Event('input'));
    }
    host.querySelector('form[role="search"]')?.dispatchEvent(new Event('submit'));
    await settle(fixture);
    expect(listUsers).toHaveBeenLastCalledWith({ page: 1, pageSize: 20, q: 'user3' });
  });

  it('disables a user after the inline confirmation and enables another', async () => {
    const setUserStatus = vi.fn((id: string, body: { status: AdminUser['status'] }) =>
      of({ ...USERS.find((user) => user.id === id), ...body } as AdminUser),
    );
    const { fixture, host } = await setup({ setUserStatus });
    const disable = Array.from(
      row(host, 'user1@example.com')?.querySelectorAll<HTMLButtonElement>('button') ?? [],
    ).find((b) => b.textContent?.includes(adminEn.users.disable));
    disable?.click();
    await settle(fixture);
    expect(setUserStatus).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Disable user1@example.com?');
    host.querySelector<HTMLButtonElement>('[data-confirm]')?.click();
    await settle(fixture);
    expect(setUserStatus).toHaveBeenCalledWith('u1', { status: 'DISABLED' });
    expect(
      row(host, 'user1@example.com')?.querySelector('[data-status]')?.getAttribute('data-status'),
    ).toBe('DISABLED');
    expect(host.querySelector('[data-user-message]')?.textContent).toContain(
      'user1@example.com is disabled.',
    );

    const enable = Array.from(
      row(host, 'user3@example.com')?.querySelectorAll<HTMLButtonElement>('button') ?? [],
    ).find((b) => b.textContent?.includes(adminEn.users.enable));
    enable?.click();
    await settle(fixture);
    expect(setUserStatus).toHaveBeenLastCalledWith('u3', { status: 'ACTIVE' });
    expect(
      row(host, 'user3@example.com')?.querySelector('[data-status]')?.getAttribute('data-status'),
    ).toBe('ACTIVE');
  });

  it('grants PRO for a number of days', async () => {
    const grantSubscription = vi.fn(() =>
      of({
        id: 's1',
        planCode: 'PRO' as const,
        status: 'ACTIVE' as const,
        source: 'MANUAL' as const,
        startedAt: '2026-11-25T10:00:00Z',
        currentPeriodEnd: '2027-01-24T10:00:00Z',
        canceledAt: null,
      }),
    );
    const { fixture, host } = await setup({ grantSubscription });
    const open = Array.from(
      row(host, 'user1@example.com')?.querySelectorAll<HTMLButtonElement>('button') ?? [],
    ).find((b) => b.textContent?.includes(adminEn.users.grant_open));
    open?.click();
    await settle(fixture);
    const days = host.querySelector<HTMLInputElement>('#grant-days-u1');
    if (days) {
      days.value = '60';
      days.dispatchEvent(new Event('input'));
    }
    row(host, 'user1@example.com')
      ?.querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(grantSubscription).toHaveBeenCalledWith('u1', { planCode: 'PRO', durationDays: 60 });
    expect(row(host, 'user1@example.com')?.textContent).toContain('PRO');
    expect(host.querySelector('[data-user-message]')?.textContent).toContain(
      'PRO granted to user1@example.com until Jan 24, 2027.',
    );
  });

  it('shows a row problem when the status change fails', async () => {
    const setUserStatus = vi.fn(() =>
      throwError(() => new ApiProblem({ kind: 'problem', status: 403, code: 'forbidden' })),
    );
    const { fixture, host } = await setup({ setUserStatus });
    Array.from(row(host, 'user3@example.com')?.querySelectorAll<HTMLButtonElement>('button') ?? [])
      .find((b) => b.textContent?.includes(adminEn.users.enable))
      ?.click();
    await settle(fixture);
    expect(host.textContent).toContain(EN.problem.forbidden.title);
  });
});

describe('AdminLayoutComponent', () => {
  it('refreshes the exchange rates and reports failed bases', async () => {
    const refreshFxRates = vi
      .fn()
      .mockReturnValueOnce(of({ upsertedRows: 120, failedBases: [] }))
      .mockReturnValueOnce(of({ upsertedRows: 80, failedBases: ['TRY'] }));
    configure({ refreshFxRates });
    const fixture = TestBed.createComponent(AdminLayoutComponent);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    const button = host.querySelector<HTMLButtonElement>('.page-head button');
    button?.click();
    await settle(fixture);
    expect(host.querySelector('[data-fx-result]')?.textContent).toContain('120 rows updated');
    button?.click();
    await settle(fixture);
    expect(host.querySelector('[data-fx-result]')?.textContent).toContain('failed for TRY');
    expect(host.querySelectorAll('.subnav a')).toHaveLength(3);
  });

  it('re-creates the routed admin page for a new session and removes it without the role', async () => {
    let created = 0;
    @Component({ selector: 'vd-probe', template: '' })
    class ProbeComponent {
      constructor() {
        created += 1;
      }
    }
    const generation = signal(1);
    const admin = signal(true);
    configure({ refreshFxRates: vi.fn() }, 'admin-id', generation, admin);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'admin',
            component: AdminLayoutComponent,
            children: [{ path: '', component: ProbeComponent }],
          },
        ]),
      ],
    });
    const harness = await RouterTestingHarness.create('/admin');
    await harness.fixture.whenStable();
    expect(created).toBe(1);

    generation.set(2);
    await harness.fixture.whenStable();
    expect(created).toBe(2);

    admin.set(false);
    await harness.fixture.whenStable();
    expect(harness.routeNativeElement?.querySelector('vd-probe')).toBeNull();
  });
});

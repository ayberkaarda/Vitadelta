import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import accountEn from '../../../../public/i18n/account/en.json';
import accountTr from '../../../../public/i18n/account/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { Notification, NotificationPage } from '../../core/api/api-types';
import { UnreadCountStore } from '../../core/state/unread-count.store';
import { provideTestLocale } from '../../testing/test-providers';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { InboxPageComponent } from './inbox-page.component';

const PAYLOAD_BASE = {
  subscriptionId: 'a1000000-0000-4000-8000-000000000001',
  productId: 'p1000000-0000-4000-8000-000000000001',
  productCode: 'DAILY-60',
  brand: 'Bosphorus Health',
  productName: 'Daily Complete 60',
  countryCode: 'TR',
  currency: 'TRY',
};

const NOTIFICATIONS: Notification[] = [
  {
    id: 'n1',
    type: 'ALERT_PRICE_DROP',
    createdAt: '2026-11-24T14:05:00Z',
    readAt: null,
    payload: {
      ...PAYLOAD_BASE,
      trigger: 'PRICE_DROP',
      thresholdPercent: 5,
      baselinePrice: 540,
      before: { effectivePrice: 540, inStock: true, retailer: 'Demo Store', url: null },
      after: { effectivePrice: 499.9, inStock: true, retailer: 'Demo Store', url: null },
    },
  },
  {
    id: 'n2',
    type: 'ALERT_BACK_IN_STOCK',
    createdAt: '2026-11-23T09:12:00Z',
    readAt: null,
    payload: {
      ...PAYLOAD_BASE,
      productName: 'Active Plus 60',
      trigger: 'BACK_IN_STOCK',
      thresholdPercent: null,
      baselinePrice: null,
      before: { effectivePrice: 1260, inStock: false, retailer: 'Demo Market', url: null },
      after: { effectivePrice: 1260, inStock: true, retailer: 'Demo Market', url: null },
    },
  },
  {
    id: 'n3',
    type: 'ALERT_PRICE_CHANGE',
    createdAt: '2026-11-21T18:40:00Z',
    readAt: null,
    payload: {
      ...PAYLOAD_BASE,
      productName: 'Gold Multi B',
      trigger: 'PRICE_CHANGE',
      thresholdPercent: null,
      baselinePrice: null,
      before: { effectivePrice: 880, inStock: true, retailer: 'Demo Store', url: null },
      after: { effectivePrice: 900, inStock: true, retailer: 'Demo Store', url: null },
    },
  },
  {
    id: 'n4',
    type: 'NOTIFICATION_CAP_REACHED',
    createdAt: '2026-11-20T07:00:00Z',
    readAt: null,
    payload: { periodMonth: '2026-11-01', limit: 30 },
  },
  {
    id: 'n5',
    type: 'PLAN_ACTIVATED',
    createdAt: '2026-11-10T08:00:00Z',
    readAt: '2026-11-10T09:00:00Z',
    payload: {
      planCode: 'PRO',
      source: 'PROMO',
      currentPeriodEnd: '2026-12-10T08:00:00Z',
      extended: false,
    },
  },
  {
    id: 'n6',
    type: 'PLAN_EXPIRED',
    createdAt: '2026-11-02T00:00:00Z',
    readAt: '2026-11-02T08:00:00Z',
    payload: { expiredPlanCode: 'PRO', planCode: 'FREE', deactivatedAlertSubscriptions: 2 },
  },
];

const PAGE: NotificationPage = {
  meta: { page: 1, pageSize: 10, total: 12 },
  items: NOTIFICATIONS,
};

describe('InboxPageComponent', () => {
  let fixture: ComponentFixture<InboxPageComponent>;
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let unread: {
    count: ReturnType<typeof signal<number | null>>;
    refresh: ReturnType<typeof vi.fn>;
    set: ReturnType<typeof vi.fn>;
  };

  const host = () => fixture.nativeElement as HTMLElement;
  const item = (type: string) => host().querySelector(`li[data-type="${type}"]`);
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  };

  async function setup(overrides: Record<string, ReturnType<typeof vi.fn>> = {}): Promise<void> {
    api = {
      listNotifications: vi.fn(() => of(PAGE)),
      markNotificationRead: vi.fn(() => of(undefined)),
      markAllNotificationsRead: vi.fn(() => of(undefined)),
      ...overrides,
    };
    const count = signal<number | null>(4);
    unread = {
      count,
      refresh: vi.fn(async () => undefined),
      set: vi.fn((value: number | null) => count.set(value)),
    };
    TestBed.configureTestingModule({
      imports: [
        InboxPageComponent,
        translocoTesting({ scopes: { account: { en: accountEn, tr: accountTr } } }),
      ],
      providers: [
        provideRouter([]),
        provideTestLocale('en-US'),
        { provide: ApiClient, useValue: api },
        { provide: UnreadCountStore, useValue: unread },
      ],
    });
    fixture = TestBed.createComponent(InboxPageComponent);
    await settle();
  }

  it('renders each notification type from its typed payload', async () => {
    await setup();
    expect(api['listNotifications']).toHaveBeenCalledWith({ page: 1, pageSize: 10 });
    expect(unread.refresh).toHaveBeenCalled();
    expect(host().querySelector('[data-unread]')?.textContent).toContain('4 unread');

    const drop = item('ALERT_PRICE_DROP');
    expect(drop?.textContent).toContain('Price drop: Daily Complete 60');
    expect(drop?.textContent).toContain('540.00 → TRY');
    expect(drop?.textContent).toContain('499.90 at Demo Store · −7.4 %');
    expect(drop?.classList).toContain('unread');
    expect(drop?.querySelector('.sr-only')?.textContent).toContain(accountEn.inbox.unread);
    expect(drop?.querySelector('a')?.getAttribute('href')).toBe(
      '/products/p1000000-0000-4000-8000-000000000001?country=TR',
    );

    expect(item('ALERT_BACK_IN_STOCK')?.textContent).toContain('Back in stock: Active Plus 60');
    expect(item('ALERT_BACK_IN_STOCK')?.textContent).toContain('Demo Market ·');
    expect(item('ALERT_PRICE_CHANGE')?.textContent).toContain('Price changed: Gold Multi B');
    expect(item('ALERT_PRICE_CHANGE')?.textContent).toContain('880.00 →');

    const cap = item('NOTIFICATION_CAP_REACHED');
    expect(cap?.textContent).toContain('Notification limit reached for November');
    expect(cap?.textContent).toContain('30 notifications a month');
    expect(cap?.textContent).toContain('Dec 1, 2026');
    expect(cap?.querySelector('a[href="/upgrade"]')).not.toBeNull();

    const activated = item('PLAN_ACTIVATED');
    expect(activated?.textContent).toContain('PRO is active');
    expect(activated?.textContent).toContain('Active until Dec 10, 2026');
    expect(activated?.classList).not.toContain('unread');
    expect(activated?.textContent).toContain(accountEn.inbox.read);

    const expired = item('PLAN_EXPIRED');
    expect(expired?.textContent).toContain('PRO expired');
    expect(expired?.textContent).toContain('Your plan is FREE again');
    expect(expired?.textContent).toContain('2 alerts above the limit were switched off.');
  });

  it('marks one notification read and refreshes the unread count', async () => {
    await setup();
    item('ALERT_PRICE_DROP')?.querySelector<HTMLButtonElement>('.meta button')?.click();
    await settle();
    expect(api['markNotificationRead']).toHaveBeenCalledWith('n1');
    expect(item('ALERT_PRICE_DROP')?.classList).not.toContain('unread');
    expect(unread.refresh).toHaveBeenCalledTimes(2);
    expect(host().textContent).toContain(accountEn.inbox.marked_read);
  });

  it('rolls the read state back when marking fails', async () => {
    const pending = new Subject<void>();
    await setup({ markNotificationRead: vi.fn(() => pending.asObservable()) });
    item('ALERT_PRICE_DROP')?.querySelector<HTMLButtonElement>('.meta button')?.click();
    await settle();
    expect(item('ALERT_PRICE_DROP')?.classList).not.toContain('unread');
    pending.error(new ApiProblem({ kind: 'network', status: 0, code: null }));
    await settle();
    expect(item('ALERT_PRICE_DROP')?.classList).toContain('unread');
    expect(host().textContent).toContain(EN.problem.network.title);
  });

  it('marks all as read and sets the shell count to zero', async () => {
    await setup();
    const button = Array.from(host().querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes(accountEn.inbox.mark_all),
    );
    button?.click();
    await settle();
    expect(api['markAllNotificationsRead']).toHaveBeenCalled();
    expect(unread.set).toHaveBeenCalledWith(0);
    expect(host().querySelectorAll('li.unread')).toHaveLength(0);
  });

  it('pages through the notifications', async () => {
    await setup();
    const next = Array.from(
      host().querySelectorAll<HTMLButtonElement>('vd-pagination button'),
    ).find((b) => b.textContent?.includes(EN.pagination.next));
    next?.click();
    await settle();
    expect(api['listNotifications']).toHaveBeenLastCalledWith({ page: 2, pageSize: 10 });
  });

  it('shows the empty state and a retryable error', async () => {
    await setup({
      listNotifications: vi
        .fn()
        .mockReturnValueOnce(
          throwError(
            () => new ApiProblem({ kind: 'problem', status: 500, code: 'internal_error' }),
          ),
        )
        .mockReturnValue(of({ meta: { page: 1, pageSize: 10, total: 0 }, items: [] })),
    });
    expect(host().textContent).toContain(EN.problem.internal_error.title);
    host().querySelector<HTMLButtonElement>('vd-problem-notice button')?.click();
    await settle();
    expect(host().textContent).toContain(accountEn.inbox.empty_text);
  });
});

import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { Notification, NotificationPage } from '../../core/api/api-types';
import { Formatter } from '../../core/format/formatter';
import { UnreadCountStore } from '../../core/state/unread-count.store';
import { IconComponent } from '../../shared/icon/icon.component';
import { IconName } from '../../shared/icon/icon-paths';
import { PaginationComponent } from '../../shared/pagination/pagination.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { EmptyStateComponent } from '../../shared/state/empty-state.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import { formatMonth, nextMonthStart, percentChange } from './account-format';

export const INBOX_PAGE_SIZE = 10;

type LoadState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly page: NotificationPage }
  | { readonly kind: 'error'; readonly problem: ApiProblem };

/** What one inbox entry shows: icon tile, i18n keys with their parameters and links. */
export interface InboxEntry {
  readonly icon: IconName;
  readonly tone: 'info' | 'ok' | 'warn' | 'neutral';
  readonly titleKey: string;
  readonly titleParams: Readonly<Record<string, string | number>>;
  readonly detailKey: string | null;
  readonly detailParams: Readonly<Record<string, string | number>>;
  readonly extraKey: string | null;
  readonly extraParams: Readonly<Record<string, string | number>>;
  readonly productLink: readonly string[] | null;
  readonly country: string | null;
  readonly showUpgrade: boolean;
}

/**
 * `/account/notifications` (project specification §8.6; design hand-off §4.8): one rendering
 * per typed payload (price drop, back in stock, price change, monthly cap, plan activated,
 * plan expired). Amounts are the native snapshot of the payload and are never converted.
 * Marking read updates the shell's unread count through {@link UnreadCountStore}.
 */
@Component({
  selector: 'vd-inbox-page',
  imports: [
    EmptyStateComponent,
    IconComponent,
    PaginationComponent,
    ProblemNoticeComponent,
    RouterLink,
    SkeletonComponent,
    TranslocoPipe,
  ],
  templateUrl: './inbox-page.component.html',
  styleUrl: './inbox-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InboxPageComponent {
  private readonly api = inject(ApiClient);
  protected readonly unread = inject(UnreadCountStore);
  protected readonly format = inject(Formatter);

  private loadSequence = 0;

  protected readonly state = signal<LoadState>({ kind: 'loading' });
  protected readonly page = signal(1);
  protected readonly actionProblem = signal<ApiProblem | null>(null);
  protected readonly announcement = signal<string | null>(null);
  protected readonly markingAll = signal(false);

  constructor() {
    void this.load(1);
    void this.unread.refresh();
  }

  /** Loads a page; an answer of a superseded load (another page) is ignored. */
  protected async load(page: number): Promise<void> {
    const sequence = ++this.loadSequence;
    this.page.set(page);
    this.state.set({ kind: 'loading' });
    try {
      const result = await firstValueFrom(
        this.api.listNotifications({ page, pageSize: INBOX_PAGE_SIZE }),
      );
      if (sequence === this.loadSequence) {
        this.state.set({ kind: 'ready', page: result });
      }
    } catch (error: unknown) {
      if (sequence === this.loadSequence) {
        this.state.set({ kind: 'error', problem: ApiProblem.from(error) });
      }
    }
  }

  protected entry(notification: Notification): InboxEntry {
    const base = {
      detailKey: null,
      detailParams: {},
      extraKey: null,
      extraParams: {},
      productLink: null,
      country: null,
      showUpgrade: false,
    };
    switch (notification.type) {
      case 'ALERT_PRICE_DROP':
      case 'ALERT_BACK_IN_STOCK':
      case 'ALERT_PRICE_CHANGE': {
        const payload = notification.payload;
        if (!payload) {
          return {
            ...base,
            icon: 'bell',
            tone: 'info',
            titleKey: 'account.inbox.alert_generic',
            titleParams: {},
          };
        }
        const money = (amount: number | null) =>
          amount === null ? '—' : this.format.money(amount, payload.currency);
        const amount = (value: number | null) => (value === null ? '—' : this.format.amount(value));
        const link = {
          productLink: ['/products', payload.productId],
          country: payload.countryCode,
        };
        const retailer = payload.after.retailer ?? payload.before.retailer ?? '—';
        if (notification.type === 'ALERT_PRICE_DROP') {
          const change = percentChange(
            payload.baselinePrice ?? payload.before.effectivePrice,
            payload.after.effectivePrice,
          );
          return {
            ...base,
            ...link,
            icon: 'down',
            tone: 'info',
            titleKey: 'account.inbox.price_drop_title',
            titleParams: { name: payload.productName },
            detailKey: 'account.inbox.price_drop_detail',
            detailParams: {
              before: amount(payload.baselinePrice ?? payload.before.effectivePrice),
              after: money(payload.after.effectivePrice),
              retailer,
              change: change === null ? '—' : this.format.signed(change, 1),
            },
          };
        }
        if (notification.type === 'ALERT_BACK_IN_STOCK') {
          return {
            ...base,
            ...link,
            icon: 'box',
            tone: 'ok',
            titleKey: 'account.inbox.back_in_stock_title',
            titleParams: { name: payload.productName },
            detailKey: 'account.inbox.back_in_stock_detail',
            detailParams: { retailer, price: money(payload.after.effectivePrice) },
          };
        }
        return {
          ...base,
          ...link,
          icon: 'fx',
          tone: 'neutral',
          titleKey: 'account.inbox.price_change_title',
          titleParams: { name: payload.productName },
          detailKey: 'account.inbox.price_change_detail',
          detailParams: {
            before: amount(payload.before.effectivePrice),
            after: money(payload.after.effectivePrice),
            retailer,
          },
        };
      }
      case 'NOTIFICATION_CAP_REACHED': {
        const payload = notification.payload;
        const locale = this.format.locale();
        return {
          ...base,
          icon: 'warn',
          tone: 'warn',
          titleKey: 'account.inbox.cap_title',
          titleParams: { month: payload ? formatMonth(locale, payload.periodMonth) : '' },
          detailKey: 'account.inbox.cap_detail',
          detailParams: {
            limit: payload?.limit ?? '',
            date: payload ? this.format.date(nextMonthStart(payload.periodMonth)) : '',
          },
          showUpgrade: true,
        };
      }
      case 'PLAN_ACTIVATED': {
        const payload = notification.payload;
        return {
          ...base,
          icon: 'star',
          tone: 'info',
          titleKey: payload?.extended
            ? 'account.inbox.plan_extended_title'
            : 'account.inbox.plan_activated_title',
          titleParams: { plan: payload?.planCode ?? 'PRO' },
          detailKey: payload?.currentPeriodEnd ? 'account.inbox.plan_activated_detail' : null,
          detailParams: {
            date: payload?.currentPeriodEnd ? this.format.date(payload.currentPeriodEnd) : '',
          },
        };
      }
      case 'PLAN_EXPIRED': {
        const payload = notification.payload;
        const deactivated = payload?.deactivatedAlertSubscriptions ?? 0;
        return {
          ...base,
          icon: 'star',
          tone: 'neutral',
          titleKey: 'account.inbox.plan_expired_title',
          titleParams: { plan: payload?.expiredPlanCode ?? 'PRO' },
          detailKey: 'account.inbox.plan_expired_detail',
          detailParams: { plan: payload?.planCode ?? 'FREE' },
          extraKey:
            deactivated === 0
              ? null
              : deactivated === 1
                ? 'account.inbox.plan_expired_deactivated_one'
                : 'account.inbox.plan_expired_deactivated',
          extraParams: { count: deactivated },
        };
      }
    }
  }

  protected async markRead(notification: Notification): Promise<void> {
    if (notification.readAt !== null) {
      return;
    }
    this.actionProblem.set(null);
    const readAt = new Date().toISOString();
    this.patch(notification.id, readAt);
    try {
      await firstValueFrom(this.api.markNotificationRead(notification.id));
      this.announcement.set('account.inbox.marked_read');
      await this.unread.refresh();
    } catch (error: unknown) {
      this.patch(notification.id, null);
      this.actionProblem.set(ApiProblem.from(error));
    }
  }

  protected async markAllRead(): Promise<void> {
    if (this.markingAll()) {
      return;
    }
    this.markingAll.set(true);
    this.actionProblem.set(null);
    try {
      await firstValueFrom(this.api.markAllNotificationsRead());
      const readAt = new Date().toISOString();
      const current = this.state();
      if (current.kind === 'ready') {
        this.state.set({
          kind: 'ready',
          page: {
            ...current.page,
            items: current.page.items.map((item) =>
              item.readAt === null ? { ...item, readAt } : item,
            ),
          },
        });
      }
      this.unread.set(0);
      this.announcement.set('account.inbox.marked_all_read');
      await this.unread.refresh();
    } catch (error: unknown) {
      this.actionProblem.set(ApiProblem.from(error));
    } finally {
      this.markingAll.set(false);
    }
  }

  private patch(id: string, readAt: string | null): void {
    const current = this.state();
    if (current.kind !== 'ready') {
      return;
    }
    this.state.set({
      kind: 'ready',
      page: {
        ...current.page,
        items: current.page.items.map((item) => (item.id === id ? { ...item, readAt } : item)),
      },
    });
  }
}

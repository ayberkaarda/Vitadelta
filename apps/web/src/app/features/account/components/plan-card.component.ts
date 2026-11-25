import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { Me } from '../../../core/api/api-types';
import { Formatter } from '../../../core/format/formatter';
import { UsageMeterComponent } from '../../../shared/charts/usage-meter.component';
import { formatMonth, nextMonthStart } from '../account-format';
import { ManageSubscriptionComponent } from './manage-subscription.component';

/**
 * "Your plan" card (design hand-off §4.8): plan tag, PRO period, and the three usage meters.
 * Every number comes from the `/me` payload; `limit = null` renders as unlimited. A subscription
 * paid through the payment provider (`source = PROVIDER`) gets "Manage subscription" (ADR-0014).
 */
@Component({
  selector: 'vd-plan-card',
  imports: [ManageSubscriptionComponent, RouterLink, TranslocoPipe, UsageMeterComponent],
  template: `
    @let m = me();
    <section class="card raised stack" aria-labelledby="plan-card-title">
      <div class="row-between">
        <h2 id="plan-card-title" class="h3">{{ 'account.plan.title' | transloco }}</h2>
        <span class="plan-tag" [class.pro]="m.plan.code === 'PRO'" data-plan>{{
          'plan.' + m.plan.code | transloco
        }}</span>
      </div>
      @if (periodEnd(); as end) {
        <p class="text-sm" data-period>
          {{ 'account.plan.active_until' | transloco: { date: end } }} ·
          {{ 'account.plan.source.' + m.subscription.source | transloco }}
        </p>
      }
      <div class="stack-3">
        <vd-usage-meter
          [label]="'account.plan.saved' | transloco"
          [used]="m.usage.savedProducts.used"
          [limit]="m.usage.savedProducts.limit"
        />
        <vd-usage-meter
          [label]="'account.plan.alerts' | transloco"
          [used]="m.usage.alertSubscriptions.used"
          [limit]="m.usage.alertSubscriptions.limit"
        />
        <vd-usage-meter
          [label]="'account.plan.notifications' | transloco: { month: month() }"
          [used]="m.usage.notificationsThisMonth.used"
          [limit]="m.usage.notificationsThisMonth.limit"
        />
        @if (m.usage.notificationsThisMonth.limit !== null) {
          <p class="text-xs muted">
            {{ 'account.plan.resets' | transloco: { date: resetDate() } }}
          </p>
        }
      </div>
      @if (m.subscription.source === 'PROVIDER') {
        <vd-manage-subscription />
      }
      <div>
        <a class="btn" routerLink="/upgrade">{{ 'account.plan.compare' | transloco }}</a>
      </div>
    </section>
  `,
  styles: `
    .plan-tag {
      font-size: 0.6875rem;
      font-weight: var(--vd-font-weight-bold);
      letter-spacing: 0.06em;
      padding: 1px 6px;
      border-radius: var(--vd-radius-sm);
      background: var(--vd-color-neutral-bg);
      color: var(--vd-color-neutral);
    }
    .plan-tag.pro {
      background: var(--vd-color-accent);
      color: var(--vd-color-on-accent);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlanCardComponent {
  private readonly format = inject(Formatter);

  readonly me = input.required<Me>();

  protected readonly periodEnd = computed(() => {
    const end = this.me().subscription.currentPeriodEnd;
    return this.me().plan.code !== 'FREE' && end ? this.format.date(end) : null;
  });
  protected readonly month = computed(() =>
    formatMonth(this.format.locale(), this.me().usage.notificationsThisMonth.periodMonth),
  );
  protected readonly resetDate = computed(() =>
    this.format.date(nextMonthStart(this.me().usage.notificationsThisMonth.periodMonth)),
  );
}

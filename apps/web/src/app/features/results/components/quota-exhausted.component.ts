import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { ApiProblem } from '../../../core/api/api-problem';
import { PlanLimits } from '../../../core/api/api-types';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/state/empty-state.component';

/**
 * Replaces the ranking after `403 quota_exceeded` (design hand-off §4.4). The number of used
 * queries comes from the problem (`limit`), the benefits from the FREE plan of `GET /plans`;
 * the call to action is the problem's `upgradeHint.ctaPath` (sign-up) plus a login link.
 */
@Component({
  selector: 'vd-quota-exhausted',
  imports: [EmptyStateComponent, IconComponent, RouterLink, TranslocoPipe],
  template: `
    <div class="card">
      <vd-empty-state
        icon="lock"
        tone="info"
        [heading]="'results.quota.title' | transloco: { limit: problem().limit ?? '' }"
      >
        <p>{{ 'results.quota.text' | transloco }}</p>
        @if (free(); as plan) {
          <ul role="list" class="benefits" [attr.aria-label]="'results.quota.benefits' | transloco">
            <li><vd-icon name="check" size="sm" />{{ 'results.quota.unlimited' | transloco }}</li>
            <li>
              <vd-icon name="check" size="sm" />
              @if (plan.savedProductsLimit === null) {
                {{ 'results.quota.saved_unlimited' | transloco }}
              } @else {
                {{ 'results.quota.saved' | transloco: { count: plan.savedProductsLimit } }}
              }
            </li>
            <li>
              <vd-icon name="check" size="sm" />
              @if (plan.alertSubscriptionsLimit === null) {
                {{ 'results.quota.alerts_unlimited' | transloco }}
              } @else {
                {{ 'results.quota.alerts' | transloco: { count: plan.alertSubscriptionsLimit } }}
              }
            </li>
          </ul>
        }
        <div vdStateActions>
          <a class="btn btn-primary" [routerLink]="registerPath()">{{
            'results.locked_register' | transloco
          }}</a>
          <a class="btn" routerLink="/auth/login" [queryParams]="loginParams()">{{
            'results.locked_login' | transloco
          }}</a>
        </div>
        <p class="text-xs">{{ 'results.quota.open_note' | transloco }}</p>
      </vd-empty-state>
    </div>
  `,
  styles: `
    .card {
      padding: 0;
    }
    .benefits {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: var(--vd-space-2) var(--vd-space-5);
      margin: 0;
      font-size: var(--vd-font-size-sm);
    }
    .benefits li {
      display: inline-flex;
      align-items: center;
      gap: var(--vd-space-2);
    }
    .benefits .i {
      color: var(--vd-color-success);
    }
    [vdStateActions] {
      display: contents;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QuotaExhaustedComponent {
  private readonly api = inject(ApiClient);

  readonly problem = input.required<ApiProblem>();
  /** Login query parameters (return URL of the results page). */
  readonly loginParams = input<Record<string, string>>({});

  protected readonly free = signal<PlanLimits | null>(null);
  protected readonly registerPath = computed(() => {
    const path = this.problem().upgradeHint?.ctaPath;
    return path && path.startsWith('/auth/') ? path : '/auth/register';
  });

  constructor() {
    firstValueFrom(this.api.listPlans()).then(
      (plans) => this.free.set(plans.items.find((plan) => plan.code === 'FREE') ?? null),
      () => this.free.set(null),
    );
  }
}

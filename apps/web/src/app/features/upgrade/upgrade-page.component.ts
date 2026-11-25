import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { Plan, PlanList } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { CurrencyStore } from '../../core/state/currency.store';
import { IconComponent } from '../../shared/icon/icon.component';
import { MoneyComponent } from '../../shared/money/money.component';
import { FxNoticeComponent } from '../../shared/money/fx-notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import { PromoFormComponent } from '../account/components/promo-form.component';

type LoadState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly list: PlanList }
  | { readonly kind: 'error'; readonly problem: ApiProblem };

/** One comparison row: label key and the plan's limit (`null` = unlimited). */
interface FeatureRow {
  readonly labelKey: string;
  readonly value: number | null;
}

/**
 * `/upgrade` (project specification §8.8, §10.2; design hand-off §4.9): FREE and PRO side by
 * side from `GET /plans`, the PRO price through the money display (converted to the display
 * currency by the server), the `upgrade.how_to_get_pro` text and the promo-code form for
 * signed-in users. There is deliberately no checkout button.
 */
@Component({
  selector: 'vd-upgrade-page',
  imports: [
    FxNoticeComponent,
    IconComponent,
    MoneyComponent,
    ProblemNoticeComponent,
    PromoFormComponent,
    RouterLink,
    SkeletonComponent,
    TranslocoPipe,
  ],
  templateUrl: './upgrade-page.component.html',
  styleUrl: './upgrade-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UpgradePageComponent {
  private readonly api = inject(ApiClient);
  private readonly currency = inject(CurrencyStore);
  protected readonly session = inject(SessionStore);
  protected readonly format = inject(Formatter);

  private loadSequence = 0;

  protected readonly state = signal<LoadState>({ kind: 'loading' });
  protected readonly loginParams = { returnUrl: '/upgrade' };

  /** Current plan of a signed-in user, from `/me`. */
  protected readonly currentPlan = computed(() =>
    this.session.isAuthenticated() ? this.session.plan() : null,
  );

  constructor() {
    effect(() => {
      const displayCurrency = this.currency.requestCurrency();
      untracked(() => void this.load(displayCurrency));
    });
  }

  protected reload(): void {
    void this.load(this.currency.requestCurrency());
  }

  /** Loads the plans; an answer for a superseded display currency is ignored. */
  private async load(displayCurrency: string | undefined): Promise<void> {
    const sequence = ++this.loadSequence;
    this.state.set({ kind: 'loading' });
    try {
      const list = await firstValueFrom(
        this.api.listPlans(displayCurrency ? { displayCurrency } : {}),
      );
      if (sequence !== this.loadSequence) {
        return;
      }
      this.currency.recordMeta(list.meta);
      this.state.set({ kind: 'ready', list });
    } catch (error: unknown) {
      if (sequence === this.loadSequence) {
        this.state.set({ kind: 'error', problem: ApiProblem.from(error) });
      }
    }
  }

  protected rows(plan: Plan): readonly FeatureRow[] {
    return [
      { labelKey: 'upgrade.rows.saved', value: plan.savedProductsLimit },
      { labelKey: 'upgrade.rows.alerts', value: plan.alertSubscriptionsLimit },
      { labelKey: 'upgrade.rows.notifications', value: plan.notificationsPerMonthLimit },
    ];
  }
}

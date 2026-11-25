import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { filter, firstValueFrom, take } from 'rxjs';

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
import { CheckoutPanelComponent } from './components/checkout-panel.component';
import {
  CheckoutOutcome,
  CheckoutReturnComponent,
  checkoutOutcome,
} from './components/checkout-return.component';

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
 * `/upgrade` (project specification §8.8, §10.2; design hand-off §4.9; ADR-0014): FREE and PRO
 * side by side from `GET /plans`, the PRO price through the money display (converted to the
 * display currency by the server), the how-to-get-PRO text and the promo-code form for
 * signed-in users. The PRO card offers the provider's hosted checkout only when the server
 * reports `checkoutAvailable` (and "Manage subscription" for a PRO plan paid through the
 * provider); without a provider there is no checkout button. `?checkout=success|canceled`, the
 * provider's return, shows a banner and is then removed from the address.
 */
@Component({
  selector: 'vd-upgrade-page',
  imports: [
    CheckoutPanelComponent,
    CheckoutReturnComponent,
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
  /** Outcome of a return from the provider's checkout, read once on arrival. */
  protected readonly checkoutReturn: CheckoutOutcome | null;

  /** Whether the server offers online payment for any plan (selects the how-to text). */
  protected readonly checkoutOffered = computed(() => {
    const current = this.state();
    return current.kind === 'ready' && current.list.items.some((plan) => plan.checkoutAvailable);
  });

  /** Current plan of a signed-in user, from `/me`. */
  protected readonly currentPlan = computed(() =>
    this.session.isAuthenticated() ? this.session.plan() : null,
  );

  constructor() {
    const route = inject(ActivatedRoute);
    this.checkoutReturn = checkoutOutcome(route.snapshot.queryParamMap.get('checkout'));
    if (this.checkoutReturn !== null) {
      // A reload must not replay the banner: once the arriving navigation has ended, drop the
      // parameter without a new history entry.
      const router = inject(Router);
      const strip = () =>
        void router.navigate([], {
          relativeTo: route,
          queryParams: { checkout: null },
          queryParamsHandling: 'merge',
          replaceUrl: true,
        });
      if (router.currentNavigation() === null) {
        strip();
      } else {
        router.events
          .pipe(
            filter((event) => event instanceof NavigationEnd),
            take(1),
            takeUntilDestroyed(),
          )
          .subscribe(strip);
      }
    }
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

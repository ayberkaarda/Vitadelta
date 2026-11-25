import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { ApiProblem } from '../../../core/api/api-problem';
import { loginQueryParams } from '../../../core/auth/return-url';
import { SessionStore } from '../../../core/auth/session.store';
import { ProviderActionGuard } from '../../../core/billing/action-guard';
import { BillingNavigator } from '../../../core/billing/billing-navigator';
import { IconComponent } from '../../../shared/icon/icon.component';
import { NoticeComponent } from '../../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../../shared/notice/problem-notice.component';
import { ManageSubscriptionComponent } from '../../account/components/manage-subscription.component';

/**
 * Online-payment part of the PRO card on `/upgrade` (ADR-0014). Everything it shows follows the
 * server: `available` is `Plan.checkoutAvailable` from `GET /plans`, the plan and its source
 * come from `GET /me`.
 *
 * - PRO paid through the provider (`subscription.source = PROVIDER`): "Manage subscription".
 * - Checkout not available: nothing (no checkout button without a provider).
 * - Visitors: a log-in link that returns here.
 * - PRO from a promo code or a grant: a short note, no button.
 * - Everyone else: "Subscribe to PRO", which asks the API for a hosted checkout session and
 *   sends the browser to the provider. `401` leads to the login page, `409 plan_already_active`
 *   reloads `/me` and points to the account (or, while `/me` is not PRO, to the billing portal:
 *   a provider subscription of the user has not ended), `403 email_not_verified` points to the
 *   account page (where the e-mail can be resent), `429` and `503 billing_unavailable` are
 *   retryable;
 *   after `429` the button waits for the `Retry-After` countdown too. Leaving the page cancels
 *   a pending session request, so a late answer never moves the browser.
 */
@Component({
  selector: 'vd-checkout-panel',
  imports: [
    IconComponent,
    ManageSubscriptionComponent,
    NoticeComponent,
    ProblemNoticeComponent,
    RouterLink,
    TranslocoPipe,
  ],
  template: `
    @if (paidThroughProvider()) {
      <vd-manage-subscription />
    } @else if (available()) {
      <div class="stack-3" data-billing="checkout">
        @if (!session.isAuthenticated()) {
          <div class="stack-1">
            <a
              class="btn btn-primary btn-block wrap"
              routerLink="/auth/login"
              [queryParams]="loginParams"
              data-action="checkout-login"
              >{{ 'upgrade.checkout.signin' | transloco }}</a
            >
            <p class="hint">{{ 'upgrade.checkout.signin_note' | transloco }}</p>
          </div>
        } @else if (session.plan() === 'PRO') {
          <p class="text-sm muted" data-checkout-note>
            <vd-icon name="ok" size="sm" />{{ 'upgrade.checkout.already_pro' | transloco }}
          </p>
        } @else {
          <div class="stack-1">
            <button
              type="button"
              class="btn btn-primary btn-block wrap"
              data-action="checkout"
              [attr.aria-busy]="busy()"
              [attr.aria-disabled]="busy() || guard.coolingDown()"
              aria-describedby="checkout-note"
              (click)="start()"
            >
              <vd-icon [name]="busy() ? 'refresh' : 'lock'" [spin]="busy()" />
              {{ 'upgrade.checkout.button' | transloco }}
            </button>
            <p class="hint" id="checkout-note">{{ 'upgrade.checkout.note' | transloco }}</p>
          </div>
          <p class="live-region text-sm muted" role="status">
            @if (busy()) {
              {{ 'upgrade.checkout.busy' | transloco }}
            }
          </p>
        }
        @if (problem(); as p) {
          @if (p.code === 'plan_already_active' && session.plan() !== 'PRO') {
            <!-- The summary is not PRO, yet the server refuses a new subscription: one of the
                 user's provider subscriptions has not ended (it is settled in the portal). -->
            <vd-notice
              tone="warn"
              icon="info"
              live="alert"
              [heading]="'upgrade.checkout.unsettled_title' | transloco"
              data-code="plan_already_active"
            >
              <p>{{ 'upgrade.checkout.unsettled_text' | transloco }}</p>
              <vd-manage-subscription class="portal" />
            </vd-notice>
          } @else if (p.code === 'plan_already_active' || p.code === 'email_not_verified') {
            <vd-notice
              [tone]="p.code === 'plan_already_active' ? 'info' : 'warn'"
              [icon]="p.code === 'plan_already_active' ? 'info' : 'mail'"
              live="alert"
              [heading]="p.messageKey + '.title' | transloco"
              [attr.data-code]="p.code"
            >
              <p>{{ p.messageKey + '.body' | transloco }}</p>
              <div vdNoticeActions>
                <a class="btn btn-sm wrap" routerLink="/account">{{
                  (p.code === 'plan_already_active'
                    ? 'upgrade.checkout.account_link'
                    : 'upgrade.checkout.verify_link'
                  ) | transloco
                }}</a>
              </div>
            </vd-notice>
          } @else {
            <vd-problem-notice [problem]="p" [retryable]="true" (retry)="start()" />
          }
        }
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .wrap {
      white-space: normal;
      text-align: center;
    }
    [vdNoticeActions] {
      display: contents;
    }
    .portal {
      margin-top: var(--vd-space-2);
    }
  `,
  host: { '(window:pageshow)': 'onPageShow($event)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CheckoutPanelComponent {
  private readonly billing = inject(BillingNavigator);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  /** `Plan.checkoutAvailable` of the PRO plan, as `GET /plans` reported it. */
  readonly available = input.required<boolean>();

  protected readonly loginParams = { returnUrl: '/upgrade' };
  protected readonly busy = signal(false);
  protected readonly problem = signal<ApiProblem | null>(null);
  /** Cancels a pending session when the page is left; holds the rate-limit countdown. */
  protected readonly guard = new ProviderActionGuard(inject(DestroyRef));

  protected readonly paidThroughProvider = computed(() => {
    const me = this.session.isAuthenticated() ? this.session.me() : null;
    return me?.plan.code === 'PRO' && me.subscription.source === 'PROVIDER';
  });

  protected async start(): Promise<void> {
    if (this.busy() || this.guard.coolingDown()) {
      return;
    }
    this.busy.set(true);
    this.problem.set(null);
    try {
      // On success the browser leaves the app; the button stays busy until then.
      await this.billing.open('checkout', this.guard.signal);
    } catch (error: unknown) {
      if (this.guard.signal.aborted) {
        return;
      }
      this.busy.set(false);
      const problem = ApiProblem.from(error);
      this.guard.apply(problem);
      if (problem.status === 401) {
        if (this.router.currentNavigation() === null) {
          void this.router.navigate(['/auth/login'], {
            queryParams: loginQueryParams('/upgrade'),
          });
        }
        return;
      }
      this.problem.set(problem);
      if (problem.code === 'plan_already_active') {
        // The server says the plan is PRO already: show what it says now.
        await this.session.reloadMe();
      }
    }
  }

  /** A page restored from the back-forward cache must not keep the busy state of its exit. */
  protected onPageShow(event: PageTransitionEvent): void {
    if (event.persisted) {
      this.busy.set(false);
    }
  }
}

import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { ApiProblem } from '../../../core/api/api-problem';
import { loginQueryParams } from '../../../core/auth/return-url';
import {
  BILLING_CONFLICT_PAUSE_SECONDS,
  ProviderActionGuard,
} from '../../../core/billing/action-guard';
import { BillingNavigator } from '../../../core/billing/billing-navigator';
import { IconComponent } from '../../../shared/icon/icon.component';
import { NoticeComponent } from '../../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../../shared/notice/problem-notice.component';

let nextId = 0;

/**
 * "Manage subscription" for a PRO plan paid through the payment provider (ADR-0014): opens the
 * provider's hosted billing portal (`POST /billing/portal-session`), where payment methods,
 * invoices and cancellation are handled. Shown by its hosts only when the server reports the
 * ACTIVE subscription with `source = PROVIDER`; the portal returns the browser to `/account`.
 *
 * `401` leads to the login page, `409 no_billing_account` is an information notice, every other
 * problem (`429` with its countdown, `503 billing_unavailable`) is a retryable notice. After
 * `429` the button itself also waits for the countdown, and leaving the page cancels a pending
 * session request, so a late answer never moves the browser.
 */
@Component({
  selector: 'vd-manage-subscription',
  imports: [IconComponent, NoticeComponent, ProblemNoticeComponent, TranslocoPipe],
  template: `
    <div class="stack-3" data-billing="portal">
      <div class="stack-1">
        <button
          type="button"
          class="btn btn-block wrap"
          data-action="manage-subscription"
          [attr.aria-busy]="busy()"
          [attr.aria-disabled]="busy() || guard.coolingDown()"
          [attr.aria-describedby]="hintId"
          (click)="open()"
        >
          <vd-icon [name]="busy() ? 'refresh' : 'ext'" [spin]="busy()" />
          {{ 'account.billing.manage' | transloco }}
        </button>
        <p class="hint" [id]="hintId">{{ 'account.billing.manage_hint' | transloco }}</p>
      </div>
      <p class="live-region text-sm muted" role="status">
        @if (busy()) {
          {{ 'account.billing.busy' | transloco }}
        }
      </p>
      @if (problem(); as p) {
        @if (p.code === 'no_billing_account') {
          <vd-notice
            tone="info"
            live="alert"
            [heading]="'problem.no_billing_account.title' | transloco"
            data-code="no_billing_account"
          >
            <p>{{ 'problem.no_billing_account.body' | transloco }}</p>
          </vd-notice>
        } @else {
          <vd-problem-notice [problem]="p" [retryable]="true" (retry)="open()" />
        }
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .wrap {
      white-space: normal;
      text-align: center;
    }
  `,
  host: { '(window:pageshow)': 'onPageShow($event)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ManageSubscriptionComponent {
  private readonly billing = inject(BillingNavigator);
  private readonly router = inject(Router);

  protected readonly hintId = `manage-subscription-hint-${nextId++}`;
  protected readonly busy = signal(false);
  protected readonly problem = signal<ApiProblem | null>(null);
  /** Cancels a pending session when the page is left; holds the rate-limit countdown. */
  protected readonly guard = new ProviderActionGuard(
    inject(DestroyRef),
    inject(BILLING_CONFLICT_PAUSE_SECONDS),
  );

  protected async open(): Promise<void> {
    if (this.busy() || this.guard.coolingDown()) {
      return;
    }
    this.busy.set(true);
    this.problem.set(null);
    try {
      // On success the browser leaves the app; the button stays busy until then.
      await this.billing.open('portal', this.guard.signal);
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
            queryParams: loginQueryParams(this.router.url),
          });
        }
        return;
      }
      this.problem.set(problem);
    }
  }

  /** A page restored from the back-forward cache must not keep the busy state of its exit. */
  protected onPageShow(event: PageTransitionEvent): void {
    if (event.persisted) {
      this.busy.set(false);
    }
  }
}

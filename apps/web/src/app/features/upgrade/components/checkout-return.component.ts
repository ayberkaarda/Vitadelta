import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  InjectionToken,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { SessionStore } from '../../../core/auth/session.store';
import { Formatter } from '../../../core/format/formatter';
import { IconComponent } from '../../../shared/icon/icon.component';
import { NoticeComponent } from '../../../shared/notice/notice.component';

/** How the payment provider returned the browser (`/upgrade?checkout=…`, ADR-0014). */
export type CheckoutOutcome = 'success' | 'canceled';

/** Reads the `checkout` query parameter; any other value is ignored. */
export function checkoutOutcome(value: string | null): CheckoutOutcome | null {
  return value === 'success' || value === 'canceled' ? value : null;
}

/**
 * Pauses (ms) between the `/me` reloads after a successful checkout. The plan changes only when
 * the provider's signed webhook reaches the API, which can be a moment after the browser
 * returns; after these attempts (about 15 s) the page says the payment is being processed.
 */
export const CHECKOUT_CONFIRM_DELAYS = new InjectionToken<readonly number[]>(
  'CHECKOUT_CONFIRM_DELAYS',
  { providedIn: 'root', factory: () => [1000, 2000, 4000, 8000] },
);

type ReturnState =
  | { readonly kind: 'canceled' }
  | { readonly kind: 'confirming' }
  | { readonly kind: 'active'; readonly until: string | null }
  | { readonly kind: 'pending' }
  | { readonly kind: 'signed-out' };

/**
 * Banner shown on `/upgrade` after the provider's hosted checkout returned the browser. A
 * return URL never changes a plan: after `success` the banner reloads `/me` a few times with
 * growing pauses and reports PRO only once the server does; otherwise it says the payment is
 * being processed and offers to check again. `canceled` says that nothing was charged.
 */
@Component({
  selector: 'vd-checkout-return',
  imports: [IconComponent, NoticeComponent, RouterLink, TranslocoPipe],
  template: `
    <div class="live-region" aria-live="polite" [attr.aria-busy]="state().kind === 'confirming'">
      @switch (state().kind) {
        @case ('canceled') {
          <vd-notice
            tone="neutral"
            icon="info"
            [heading]="'upgrade.return.canceled_title' | transloco"
            data-return="canceled"
          >
            <p>{{ 'upgrade.return.canceled_text' | transloco }}</p>
          </vd-notice>
        }
        @case ('confirming') {
          <vd-notice
            tone="info"
            icon="clock"
            [heading]="'upgrade.return.confirming_title' | transloco"
            data-return="confirming"
          >
            <p class="row-2">
              <vd-icon name="refresh" size="sm" [spin]="true" />
              {{ 'upgrade.return.confirming_text' | transloco }}
            </p>
          </vd-notice>
        }
        @case ('active') {
          @let until = activeUntil();
          <vd-notice
            tone="ok"
            [heading]="'upgrade.return.active_title' | transloco"
            data-return="active"
          >
            <p>
              @if (until) {
                {{ 'upgrade.return.active_until' | transloco: { date: until } }}
              } @else {
                {{ 'upgrade.return.active_text' | transloco }}
              }
            </p>
            <div vdNoticeActions>
              <a class="btn btn-sm wrap" routerLink="/account">{{
                'upgrade.checkout.account_link' | transloco
              }}</a>
            </div>
          </vd-notice>
        }
        @case ('pending') {
          <vd-notice
            tone="warn"
            icon="clock"
            [heading]="'upgrade.return.pending_title' | transloco"
            data-return="pending"
          >
            <p>{{ 'upgrade.return.pending_text' | transloco }}</p>
            <div vdNoticeActions>
              <button type="button" class="btn btn-sm wrap" (click)="confirm(true)">
                <vd-icon name="refresh" />{{ 'upgrade.return.check_again' | transloco }}
              </button>
            </div>
          </vd-notice>
        }
        @case ('signed-out') {
          <vd-notice
            tone="info"
            icon="user"
            [heading]="'upgrade.return.signed_out_title' | transloco"
            data-return="signed-out"
          >
            <p>{{ 'upgrade.return.signed_out_text' | transloco }}</p>
            <div vdNoticeActions>
              <a class="btn btn-sm wrap" routerLink="/auth/login" [queryParams]="loginParams">{{
                'upgrade.signin_login' | transloco
              }}</a>
            </div>
          </vd-notice>
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
    [vdNoticeActions] {
      display: contents;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CheckoutReturnComponent implements OnInit {
  private readonly session = inject(SessionStore);
  private readonly format = inject(Formatter);
  private readonly delays = inject(CHECKOUT_CONFIRM_DELAYS);

  readonly outcome = input.required<CheckoutOutcome>();

  protected readonly loginParams = { returnUrl: '/upgrade' };
  protected readonly state = signal<ReturnState>({ kind: 'confirming' });
  protected readonly activeUntil = computed(() => {
    const current = this.state();
    return current.kind === 'active' && current.until ? this.format.date(current.until) : null;
  });

  private run = 0;
  private destroyed = false;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      if (this.timer !== null) {
        clearTimeout(this.timer);
      }
    });
  }

  ngOnInit(): void {
    if (this.outcome() === 'canceled') {
      this.state.set({ kind: 'canceled' });
    } else {
      void this.confirm();
    }
  }

  /**
   * Waits for the server to report PRO, reloading `/me` with the configured pauses. `fresh`
   * reloads before the first look (a repeated check); on arrival the `/me` of the start-up is
   * current.
   */
  protected async confirm(fresh = false): Promise<void> {
    const run = ++this.run;
    const current = () => run === this.run && !this.destroyed;
    this.state.set({ kind: 'confirming' });
    await this.session.whenReady();
    for (let attempt = 0; attempt <= this.delays.length; attempt++) {
      if (!current()) {
        return;
      }
      if (!this.session.isAuthenticated()) {
        this.state.set({ kind: 'signed-out' });
        return;
      }
      if (attempt > 0 || fresh) {
        if (attempt > 0) {
          await this.pause(this.delays[attempt - 1]);
          if (!current()) {
            return;
          }
        }
        await this.session.reloadMe();
        if (!current()) {
          return;
        }
      }
      const me = this.session.isAuthenticated() ? this.session.me() : null;
      if (me?.plan.code === 'PRO') {
        this.state.set({ kind: 'active', until: me.subscription.currentPeriodEnd });
        return;
      }
    }
    if (current()) {
      this.state.set(this.session.isAuthenticated() ? { kind: 'pending' } : { kind: 'signed-out' });
    }
  }

  private pause(ms: number): Promise<void> {
    return new Promise((resolve) => {
      this.timer = setTimeout(() => {
        this.timer = null;
        resolve();
      }, ms);
    });
  }
}

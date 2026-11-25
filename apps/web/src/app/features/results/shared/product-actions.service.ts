import { Injectable, WritableSignal, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { ApiProblem } from '../../../core/api/api-problem';
import { AlertSubscription, AlertTrigger } from '../../../core/api/api-types';
import { loginQueryParams } from '../../../core/auth/return-url';
import { SessionStore } from '../../../core/auth/session.store';

/** Which action a problem notice belongs to. */
export type ProductAction = 'save' | 'alert' | 'resend';

/**
 * Save and alert state of one product row or page (project specification §8.4, §10.3).
 * Starts from the server's `saved` and `alerts`, changes optimistically while a request runs and
 * rolls back when the server refuses. The client never decides whether a limit is reached: it
 * shows the server's `entitlement_exceeded` answer.
 */
export class ProductActionState {
  readonly saved: WritableSignal<boolean>;
  readonly alerts: WritableSignal<readonly AlertSubscription[]>;
  readonly busy = signal<ProductAction | null>(null);
  /** Last refusal of the server, rendered by `vd-product-action-notice`. */
  readonly problem = signal<ApiProblem | null>(null);
  readonly problemAction = signal<ProductAction | null>(null);
  /** `AcceptedMessage.messageKey` after a resend of the verification e-mail. */
  readonly resentKey = signal<string | null>(null);

  /** Active price-drop alert (the one the row's alert toggle controls). */
  readonly priceAlert = computed(
    () => this.alerts().find((alert) => alert.trigger === 'PRICE_DROP' && alert.active) ?? null,
  );
  readonly activeAlerts = computed(() => this.alerts().filter((alert) => alert.active));

  constructor(
    readonly productId: string,
    readonly country: string,
    saved: boolean,
    alerts: readonly AlertSubscription[],
  ) {
    this.saved = signal(saved);
    this.alerts = signal(alerts);
  }
}

const PENDING_ID = 'pending';

/** Save / alert / resend flows shared by the results rows, the breakdown drawer and the detail page. */
@Injectable({ providedIn: 'root' })
export class ProductActionsService {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  create(
    productId: string,
    country: string,
    saved: boolean,
    alerts: readonly AlertSubscription[],
  ): ProductActionState {
    return new ProductActionState(productId, country, saved, alerts);
  }

  /** Query parameters of the login link that returns to the current page. */
  loginParams(): Record<string, string> {
    return loginQueryParams(this.router.url);
  }

  /** Saves or removes the product; optimistic with rollback. */
  async toggleSave(state: ProductActionState): Promise<void> {
    if (!this.requireSignIn() || state.busy()) {
      return;
    }
    const before = state.saved();
    this.start(state, 'save');
    state.saved.set(!before);
    try {
      if (before) {
        await firstValueFrom(this.api.removeSavedProduct(state.productId, state.country));
      } else {
        await firstValueFrom(
          this.api.saveProduct({ productId: state.productId, countryCode: state.country }),
        );
      }
      void this.session.reloadMe();
    } catch (error: unknown) {
      state.saved.set(before);
      this.fail(state, 'save', error);
    } finally {
      state.busy.set(null);
    }
  }

  /**
   * Row toggle for the price-drop alert: turns the active one off, reactivates an inactive one
   * or creates one with the server's default threshold. Optimistic with rollback.
   */
  async toggleAlert(state: ProductActionState): Promise<void> {
    if (!this.requireSignIn() || state.busy()) {
      return;
    }
    const active = state.priceAlert();
    if (active) {
      await this.patchAlert(state, active, { active: false });
      return;
    }
    await this.createAlert(state, 'PRICE_DROP', null);
  }

  /**
   * Creates (or reactivates) the alert for `trigger`. An existing subscription is changed with
   * `PATCH` because `POST` answers an existing one unchanged. Returns `true` on success.
   */
  async createAlert(
    state: ProductActionState,
    trigger: AlertTrigger,
    thresholdPercent: number | null,
  ): Promise<boolean> {
    if (!this.requireSignIn() || state.busy()) {
      return false;
    }
    const existing = state.alerts().find((alert) => alert.trigger === trigger);
    if (existing) {
      const change: { active?: boolean; thresholdPercent?: number } = {};
      if (!existing.active) {
        change.active = true;
      }
      if (
        trigger === 'PRICE_DROP' &&
        thresholdPercent !== null &&
        thresholdPercent !== existing.thresholdPercent
      ) {
        change.thresholdPercent = thresholdPercent;
      }
      return Object.keys(change).length === 0 ? true : this.patchAlert(state, existing, change);
    }

    const before = state.alerts();
    const savedBefore = state.saved();
    this.start(state, 'alert');
    state.alerts.set(
      sortAlerts([...before, pendingAlert(state, trigger, thresholdPercent, this.nowIso())]),
    );
    try {
      const created = await firstValueFrom(
        this.api.createAlert({
          productId: state.productId,
          countryCode: state.country,
          trigger,
          ...(trigger === 'PRICE_DROP' && thresholdPercent !== null ? { thresholdPercent } : {}),
        }),
      );
      state.alerts.set(sortAlerts([...before, created]));
      // Creating an alert saves the product implicitly (server rule).
      state.saved.set(true);
      void this.session.reloadMe();
      return true;
    } catch (error: unknown) {
      state.alerts.set(before);
      state.saved.set(savedBefore);
      this.fail(state, 'alert', error);
      return false;
    } finally {
      state.busy.set(null);
    }
  }

  /** Resends the verification e-mail after `email_not_verified`. */
  async resendVerification(state: ProductActionState): Promise<void> {
    const email = this.session.user()?.email;
    if (!email || state.busy()) {
      return;
    }
    state.busy.set('resend');
    try {
      const accepted = await firstValueFrom(this.api.resendVerification({ email }));
      state.resentKey.set(accepted.messageKey);
    } catch (error: unknown) {
      this.fail(state, 'resend', error);
    } finally {
      state.busy.set(null);
    }
  }

  private async patchAlert(
    state: ProductActionState,
    alert: AlertSubscription,
    change: { active?: boolean; thresholdPercent?: number },
  ): Promise<boolean> {
    const before = state.alerts();
    this.start(state, 'alert');
    state.alerts.set(
      before.map((entry) => (entry.id === alert.id ? { ...entry, ...change } : entry)),
    );
    try {
      const updated = await firstValueFrom(this.api.updateAlert(alert.id, change));
      state.alerts.set(before.map((entry) => (entry.id === alert.id ? updated : entry)));
      void this.session.reloadMe();
      return true;
    } catch (error: unknown) {
      state.alerts.set(before);
      this.fail(state, 'alert', error);
      return false;
    } finally {
      state.busy.set(null);
    }
  }

  private requireSignIn(): boolean {
    if (this.session.isAuthenticated()) {
      return true;
    }
    void this.router.navigate(['/auth/login'], { queryParams: this.loginParams() });
    return false;
  }

  private start(state: ProductActionState, action: ProductAction): void {
    state.busy.set(action);
    state.problem.set(null);
    state.problemAction.set(null);
    state.resentKey.set(null);
  }

  private fail(state: ProductActionState, action: ProductAction, error: unknown): void {
    state.problem.set(ApiProblem.from(error));
    state.problemAction.set(action);
  }

  private nowIso(): string {
    return new Date().toISOString();
  }
}

const TRIGGER_ORDER: Readonly<Record<AlertTrigger, number>> = {
  PRICE_DROP: 0,
  BACK_IN_STOCK: 1,
  PRICE_CHANGE: 2,
};

function sortAlerts(alerts: readonly AlertSubscription[]): AlertSubscription[] {
  return [...alerts].sort((a, b) => TRIGGER_ORDER[a.trigger] - TRIGGER_ORDER[b.trigger]);
}

/** Placeholder shown while the create request runs; replaced by the server's answer. */
function pendingAlert(
  state: ProductActionState,
  trigger: AlertTrigger,
  thresholdPercent: number | null,
  createdAt: string,
): AlertSubscription {
  return {
    id: PENDING_ID,
    productId: state.productId,
    countryCode: state.country,
    trigger,
    thresholdPercent: trigger === 'PRICE_DROP' ? thresholdPercent : null,
    baselinePrice: null,
    baselineCurrency: '',
    active: true,
    createdAt,
    lastTriggeredAt: null,
  };
}

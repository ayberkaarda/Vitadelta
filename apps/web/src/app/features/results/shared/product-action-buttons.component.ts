import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, provideTranslocoScope } from '@jsverse/transloco';

import { SessionStore } from '../../../core/auth/session.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { ProductActionState, ProductActionsService } from './product-actions.service';

/**
 * Save toggle and price-alert toggle of one product (design hand-off §4.3, §4.6).
 *
 * - Anonymous visitors get links to `/auth/login?returnUrl=…` (the server would answer 401).
 * - Signed-in users get `aria-pressed` toggle buttons; the state changes optimistically and
 *   rolls back on a refusal, which `vd-product-action-notice` explains.
 *
 * `variant="icon"` renders square icon buttons (results rows), `text` labelled buttons
 * (drawer footer, product page).
 */
@Component({
  selector: 'vd-product-action-buttons',
  imports: [IconComponent, RouterLink, TranslocoPipe],
  providers: [provideTranslocoScope('results')],
  template: `
    @let s = state();
    @if (session.isAuthenticated()) {
      <button
        type="button"
        class="btn"
        [class.btn-sm]="small()"
        [class.btn-icon]="variant() === 'icon'"
        data-action="save"
        [attr.aria-pressed]="s.saved()"
        [attr.aria-busy]="s.busy() === 'save' ? 'true' : null"
        [attr.aria-label]="
          variant() === 'icon' ? ('results.actions.save_named' | transloco: { name: name() }) : null
        "
        (click)="actions.toggleSave(s)"
      >
        <vd-icon name="save" />
        @if (variant() === 'text') {
          {{ (s.saved() ? 'results.actions.saved' : 'results.actions.save') | transloco }}
        }
      </button>
      @if (showAlert()) {
        <button
          type="button"
          class="btn"
          [class.btn-sm]="small()"
          [class.btn-icon]="variant() === 'icon'"
          data-action="alert"
          [class.wide-only]="alertWideOnly()"
          [attr.aria-pressed]="alertOn()"
          [attr.aria-busy]="s.busy() === 'alert' ? 'true' : null"
          [attr.aria-label]="
            variant() === 'icon'
              ? ('results.actions.alert_named' | transloco: { name: name() })
              : null
          "
          (click)="actions.toggleAlert(s)"
        >
          <vd-icon name="bell" />
          @if (variant() === 'text') {
            {{ (alertOn() ? 'results.actions.alert_on' : 'results.actions.alert') | transloco }}
          }
        </button>
      }
    } @else {
      <a
        class="btn"
        [class.btn-sm]="small()"
        [class.btn-icon]="variant() === 'icon'"
        data-action="save"
        routerLink="/auth/login"
        [queryParams]="loginParams()"
        [attr.aria-label]="'results.actions.login_save' | transloco: { name: name() }"
      >
        <vd-icon name="save" />
        @if (variant() === 'text') {
          {{ 'results.actions.save' | transloco }}
        }
      </a>
      @if (showAlert()) {
        <a
          class="btn"
          [class.btn-sm]="small()"
          [class.btn-icon]="variant() === 'icon'"
          data-action="alert"
          [class.wide-only]="alertWideOnly()"
          routerLink="/auth/login"
          [queryParams]="loginParams()"
          [attr.aria-label]="'results.actions.login_alert' | transloco: { name: name() }"
        >
          <vd-icon name="bell" />
          @if (variant() === 'text') {
            {{ 'results.actions.alert' | transloco }}
          }
        </a>
      }
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      flex-wrap: wrap;
      gap: var(--vd-space-2);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductActionButtonsComponent {
  protected readonly session = inject(SessionStore);
  protected readonly actions = inject(ProductActionsService);

  readonly state = input.required<ProductActionState>();
  /** Product name for the accessible labels of icon buttons. */
  readonly name = input.required<string>();
  readonly variant = input<'icon' | 'text'>('icon');
  readonly small = input(true);
  /** `false` on pages with their own alert form (product detail). */
  readonly showAlert = input(true);
  /**
   * Hides the alert toggle below 48rem (results cards: Breakdown, Save and Compare share one row;
   * the alert stays reachable in the breakdown sheet and on the product page).
   */
  readonly alertWideOnly = input(false);

  protected readonly alertOn = computed(() => this.state().priceAlert() !== null);

  /** Read on every render so the return URL follows the current page and its query. */
  protected loginParams(): Record<string, string> {
    return this.actions.loginParams();
  }
}

import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, provideTranslocoScope } from '@jsverse/transloco';

import { LimitNoticeComponent } from '../../../shared/notice/limit-notice.component';
import { NoticeComponent } from '../../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../../shared/notice/problem-notice.component';
import { ProductActionState, ProductActionsService } from './product-actions.service';

/**
 * Explains why a save or alert action was refused (design hand-off §4.4):
 * - `entitlement_exceeded` → limit notice from the server's `upgradeHint` plus a link to manage
 *   the list;
 * - `email_not_verified` → warning with "Resend verification e-mail";
 * - anything else → the generic problem notice.
 */
@Component({
  selector: 'vd-product-action-notice',
  imports: [
    LimitNoticeComponent,
    NoticeComponent,
    ProblemNoticeComponent,
    RouterLink,
    TranslocoPipe,
  ],
  providers: [provideTranslocoScope('results')],
  template: `
    @let s = state();
    @if (s.resentKey(); as key) {
      <vd-notice tone="ok" live="status">
        <p>{{ key | transloco }}</p>
      </vd-notice>
    } @else if (s.problem(); as p) {
      @if (p.code === 'entitlement_exceeded' && p.upgradeHint) {
        <vd-limit-notice [problem]="p">
          @if (s.problemAction() === 'alert') {
            <a vdNoticeActions class="btn btn-sm" routerLink="/account/alerts">{{
              'results.actions.manage_alerts' | transloco
            }}</a>
          } @else {
            <a vdNoticeActions class="btn btn-sm" routerLink="/account/saved">{{
              'results.actions.manage_saved' | transloco
            }}</a>
          }
        </vd-limit-notice>
      } @else if (p.code === 'email_not_verified') {
        <vd-notice
          tone="warn"
          live="alert"
          data-code="email_not_verified"
          [heading]="'problem.email_not_verified.title' | transloco"
        >
          <p>{{ 'problem.email_not_verified.body' | transloco }}</p>
          <div vdNoticeActions>
            <button
              type="button"
              class="btn btn-sm"
              [attr.aria-busy]="s.busy() === 'resend' ? 'true' : null"
              (click)="actions.resendVerification(s)"
            >
              {{ 'results.actions.resend' | transloco }}
            </button>
          </div>
        </vd-notice>
      } @else {
        <vd-problem-notice [problem]="p" />
      }
    }
  `,
  styles: `
    :host {
      display: block;
    }
    :host:empty {
      display: none;
    }
    [vdNoticeActions] {
      display: contents;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductActionNoticeComponent {
  protected readonly actions = inject(ProductActionsService);

  readonly state = input.required<ProductActionState>();
}

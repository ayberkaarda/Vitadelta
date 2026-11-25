import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { ApiProblem } from '../../../core/api/api-problem';
import { SubscriptionActivation } from '../../../core/api/api-types';
import { SessionStore } from '../../../core/auth/session.store';
import { Formatter } from '../../../core/format/formatter';
import { ControlDirective, FieldComponent } from '../../../shared/form/field.component';
import { IconComponent } from '../../../shared/icon/icon.component';
import { NoticeComponent } from '../../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../../shared/notice/problem-notice.component';

let nextId = 0;

/**
 * Promo-code redemption (`POST /me/subscription/promo`, project specification §8.8). Every
 * rejected code shows the same message (`promo_invalid`); `email_not_verified` and
 * `rate_limited` are shown as notices. On success the session reloads `/me`, so the plan tag
 * and the meters follow the server.
 */
@Component({
  selector: 'vd-promo-form',
  imports: [
    ControlDirective,
    FieldComponent,
    IconComponent,
    NoticeComponent,
    ProblemNoticeComponent,
    TranslocoPipe,
  ],
  template: `
    <section class="card stack" [attr.aria-labelledby]="ids.title">
      <h2 class="h4" [id]="ids.title">{{ headingKey() | transloco }}</h2>
      @if (problem(); as p) {
        <vd-problem-notice [problem]="p" (retry)="submit()" />
      }
      @let err = errorKey();
      <form class="stack-3" novalidate (submit)="submit($event)">
        <vd-field
          [controlId]="ids.code"
          [label]="'account.promo.label' | transloco"
          [hint]="'account.promo.hint' | transloco"
          [error]="err ? (err | transloco) : null"
        >
          <input
            #codeInput
            vdControl
            class="inp mono"
            type="text"
            name="promo-code"
            autocomplete="off"
            autocapitalize="characters"
            spellcheck="false"
            [attr.placeholder]="'account.promo.placeholder' | transloco"
            [value]="code()"
            (input)="code.set(codeInput.value)"
          />
        </vd-field>
        <button class="btn btn-primary btn-block" type="submit" [attr.aria-busy]="busy()">
          <vd-icon [name]="busy() ? 'refresh' : 'key'" [spin]="busy()" />
          {{ 'account.promo.submit' | transloco }}
        </button>
      </form>
      <div class="live-region" aria-live="polite">
        @if (activeUntil(); as date) {
          <vd-notice tone="ok" [heading]="'account.promo.success' | transloco: { date }">
            <p>{{ 'account.promo.success_text' | transloco }}</p>
          </vd-notice>
        }
      </div>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromoFormComponent {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly format = inject(Formatter);

  /** i18n key of the card heading. */
  readonly headingKey = input('account.promo.title');
  readonly redeemed = output<SubscriptionActivation>();

  private readonly uid = nextId++;
  protected readonly ids = { title: `promo-title-${this.uid}`, code: `promo-code-${this.uid}` };
  protected readonly code = signal('');
  protected readonly busy = signal(false);
  protected readonly errorKey = signal<string | null>(null);
  protected readonly problem = signal<ApiProblem | null>(null);
  private readonly activation = signal<SubscriptionActivation | null>(null);

  protected readonly activeUntil = computed(() => {
    const end = this.activation()?.subscription.currentPeriodEnd;
    return end ? this.format.date(end) : null;
  });

  protected async submit(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.busy()) {
      return;
    }
    const code = this.code().trim();
    this.problem.set(null);
    this.activation.set(null);
    if (code === '') {
      this.errorKey.set('validation.required');
      return;
    }
    this.errorKey.set(null);
    this.busy.set(true);
    try {
      const activation = await firstValueFrom(this.api.redeemPromoCode({ code }));
      this.code.set('');
      this.activation.set(activation);
      this.redeemed.emit(activation);
      await this.session.reloadMe();
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      if (problem.code === 'promo_invalid' || problem.code === 'validation_failed') {
        this.errorKey.set('problem.promo_invalid.body');
      } else {
        this.problem.set(problem);
      }
    } finally {
      this.busy.set(false);
    }
  }
}

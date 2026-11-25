import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { CreatePromoCodeRequest, PromoCode } from '../../core/api/api-types';
import { Formatter } from '../../core/format/formatter';
import { ErrorSummaryComponent } from '../../shared/form/error-summary.component';
import { ControlDirective, FieldComponent } from '../../shared/form/field.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { EmptyStateComponent } from '../../shared/state/empty-state.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import {
  FormErrors,
  NO_FORM_ERRORS,
  formErrors,
  problemFormErrors,
} from '../../shared/form/field-errors';

type LoadState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly items: readonly PromoCode[] }
  | { readonly kind: 'error'; readonly problem: ApiProblem };

const POSITIVE_INTEGER = /^[1-9]\d{0,5}$/;
const PREFIX_PATTERN = /^[A-Za-z0-9]{0,16}$/;

/**
 * `/admin/promo-codes` (project specification §8.8, §8.9; design hand-off §4.10): create codes
 * (the generated code is shown once, with a copy button), list them and delete one after an
 * inline confirmation.
 */
@Component({
  selector: 'vd-admin-promo-codes-page',
  imports: [
    ControlDirective,
    EmptyStateComponent,
    ErrorSummaryComponent,
    FieldComponent,
    IconComponent,
    NoticeComponent,
    ProblemNoticeComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  templateUrl: './admin-promo-codes-page.component.html',
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminPromoCodesPageComponent {
  private readonly api = inject(ApiClient);
  protected readonly format = inject(Formatter);

  protected readonly ids = {
    prefix: 'promo-prefix',
    plan: 'promo-plan',
    duration: 'promo-duration',
    max: 'promo-max',
    expires: 'promo-expires',
  } as const;

  protected readonly state = signal<LoadState>({ kind: 'loading' });
  protected readonly prefix = signal('');
  protected readonly duration = signal('30');
  protected readonly maxRedemptions = signal('100');
  protected readonly expires = signal('');
  protected readonly creating = signal(false);
  protected readonly errors = signal<FormErrors>(NO_FORM_ERRORS);
  protected readonly createProblem = signal<ApiProblem | null>(null);
  /** The code returned by the last create call, shown once until dismissed. */
  protected readonly created = signal<PromoCode | null>(null);
  protected readonly copied = signal<string | null>(null);
  protected readonly confirming = signal<string | null>(null);
  protected readonly deleting = signal(false);
  protected readonly listProblem = signal<ApiProblem | null>(null);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.state.set({ kind: 'loading' });
    try {
      const list = await firstValueFrom(this.api.listPromoCodes());
      this.state.set({ kind: 'ready', items: list.items });
    } catch (error: unknown) {
      this.state.set({ kind: 'error', problem: ApiProblem.from(error) });
    }
  }

  protected usedUp(code: PromoCode): boolean {
    return code.redeemedCount >= code.maxRedemptions;
  }

  protected async create(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.creating()) {
      return;
    }
    const prefix = this.prefix().trim();
    const duration = this.duration().trim();
    const max = this.maxRedemptions().trim();
    const expires = this.expires();
    const errors = formErrors([
      ...(PREFIX_PATTERN.test(prefix)
        ? []
        : [[this.ids.prefix, 'admin.promo.prefix_error'] as const]),
      ...(POSITIVE_INTEGER.test(duration)
        ? []
        : [[this.ids.duration, 'admin.promo.whole_number'] as const]),
      ...(POSITIVE_INTEGER.test(max) ? [] : [[this.ids.max, 'admin.promo.whole_number'] as const]),
    ]);
    this.errors.set(errors);
    this.createProblem.set(null);
    if (errors.summary.length > 0) {
      return;
    }
    const body: CreatePromoCodeRequest = {
      planCode: 'PRO',
      durationDays: Number(duration),
      maxRedemptions: Number(max),
      expiresAt: expires ? `${expires}T23:59:59Z` : null,
      ...(prefix ? { prefix: prefix.toUpperCase() } : {}),
    };
    this.creating.set(true);
    this.created.set(null);
    this.copied.set(null);
    try {
      const code = await firstValueFrom(this.api.createPromoCode(body));
      this.created.set(code);
      this.prefix.set('');
      this.expires.set('');
      const current = this.state();
      if (current.kind === 'ready') {
        this.state.set({
          kind: 'ready',
          items: [code, ...current.items.filter((entry) => entry.code !== code.code)],
        });
      }
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      if (problem.code === 'validation_failed' && problem.errors.length > 0) {
        this.errors.set(
          problemFormErrors(
            problem,
            {
              prefix: this.ids.prefix,
              planCode: this.ids.plan,
              durationDays: this.ids.duration,
              maxRedemptions: this.ids.max,
              expiresAt: this.ids.expires,
            },
            this.ids.prefix,
          ),
        );
      } else {
        this.createProblem.set(problem);
      }
    } finally {
      this.creating.set(false);
    }
  }

  protected async copy(code: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(code);
      this.copied.set(code);
    } catch {
      this.copied.set('');
    }
  }

  protected async remove(code: PromoCode): Promise<void> {
    if (this.deleting()) {
      return;
    }
    this.deleting.set(true);
    this.listProblem.set(null);
    try {
      await firstValueFrom(this.api.deletePromoCode(code.code));
      const current = this.state();
      if (current.kind === 'ready') {
        this.state.set({
          kind: 'ready',
          items: current.items.filter((entry) => entry.code !== code.code),
        });
      }
      if (this.created()?.code === code.code) {
        this.created.set(null);
      }
      this.confirming.set(null);
    } catch (error: unknown) {
      this.listProblem.set(ApiProblem.from(error));
    } finally {
      this.deleting.set(false);
    }
  }
}

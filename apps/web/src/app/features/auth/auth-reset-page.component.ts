import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { takeEmailToken } from '../../core/auth/email-token';
import { ErrorSummaryComponent } from '../../shared/form/error-summary.component';
import { ControlDirective, FieldComponent } from '../../shared/form/field.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { FocusOnInitDirective } from './focus-on-init.directive';
import {
  FormErrors,
  NO_FORM_ERRORS,
  formErrors,
  problemFormErrors,
} from '../../shared/form/field-errors';
import { MIN_PASSWORD_LENGTH } from './form-errors';

/**
 * `/auth/reset#token=…` (project specification §8.2, §10.2). The token was taken out of the
 * address bar before the application started (`captureEmailToken`, before any request) and
 * is handed over once from memory; it is sent only in the POST body. A successful reset goes
 * to the login page.
 */
@Component({
  selector: 'vd-auth-reset-page',
  imports: [
    ControlDirective,
    ErrorSummaryComponent,
    FieldComponent,
    FocusOnInitDirective,
    IconComponent,
    ProblemNoticeComponent,
    RouterLink,
    TranslocoPipe,
  ],
  templateUrl: './auth-reset-page.component.html',
  styleUrl: './auth-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthResetPageComponent {
  private readonly api = inject(ApiClient);
  private readonly router = inject(Router);
  private readonly token: string | null = takeEmailToken('/auth/reset');

  protected readonly ids = { password: 'reset-password', repeat: 'reset-repeat' } as const;
  protected readonly password = signal('');
  protected readonly repeat = signal('');
  protected readonly busy = signal(false);
  protected readonly errors = signal<FormErrors>(NO_FORM_ERRORS);
  protected readonly problem = signal<ApiProblem | null>(null);
  /** `true` when the link has no usable token or the API rejected it. */
  protected readonly invalid = signal(this.token === null);

  protected async submit(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.busy() || this.token === null) {
      return;
    }
    const password = this.password();
    const repeat = this.repeat();
    const errors = formErrors([
      ...(password.length < MIN_PASSWORD_LENGTH
        ? [[this.ids.password, 'validation.too_short'] as const]
        : []),
      ...(repeat !== password ? [[this.ids.repeat, 'auth.errors.password_mismatch'] as const] : []),
    ]);
    this.errors.set(errors);
    this.problem.set(null);
    if (errors.summary.length > 0) {
      return;
    }
    this.busy.set(true);
    try {
      await firstValueFrom(this.api.resetPassword({ token: this.token, newPassword: password }));
      this.password.set('');
      this.repeat.set('');
      await this.router.navigateByUrl('/auth/login?reset=done');
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      const tokenRejected =
        problem.code === 'token_invalid' ||
        problem.code === 'token_expired' ||
        problem.errors.some((entry) => entry.field === 'token');
      if (tokenRejected) {
        this.password.set('');
        this.repeat.set('');
        this.invalid.set(true);
      } else if (problem.code === 'validation_failed' && problem.errors.length > 0) {
        this.errors.set(
          problemFormErrors(problem, { newPassword: this.ids.password }, this.ids.password),
        );
      } else {
        this.problem.set(problem);
      }
    } finally {
      this.busy.set(false);
    }
  }
}

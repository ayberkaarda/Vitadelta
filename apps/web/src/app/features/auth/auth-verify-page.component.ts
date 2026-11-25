import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { takeEmailToken } from '../../core/auth/email-token';
import { SessionStore } from '../../core/auth/session.store';
import { ErrorSummaryComponent } from '../../shared/form/error-summary.component';
import { ControlDirective, FieldComponent } from '../../shared/form/field.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { FocusOnInitDirective } from './focus-on-init.directive';
import {
  FormErrors,
  NO_FORM_ERRORS,
  formErrors,
  problemFormErrors,
  serverMessageKey,
} from '../../shared/form/field-errors';
import { authFieldMessage, isEmailShaped } from './form-errors';

export type VerifyState = 'verifying' | 'verified' | 'expired' | 'invalid' | 'failed';

/**
 * `/auth/verify#token=…` (project specification §8.2, §10.2). The token was taken out of the
 * address bar before the application started (`captureEmailToken`, before any request) and
 * is handed over once from memory; it is posted in the body only and kept for a retry.
 * Expired or invalid links offer a resend form.
 */
@Component({
  selector: 'vd-auth-verify-page',
  imports: [
    ControlDirective,
    ErrorSummaryComponent,
    FieldComponent,
    FocusOnInitDirective,
    IconComponent,
    NoticeComponent,
    ProblemNoticeComponent,
    RouterLink,
    TranslocoPipe,
  ],
  templateUrl: './auth-verify-page.component.html',
  styleUrl: './auth-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthVerifyPageComponent {
  private readonly api = inject(ApiClient);
  protected readonly session = inject(SessionStore);
  private readonly token: string | null;

  protected readonly ids = { email: 'verify-email' } as const;
  protected readonly state = signal<VerifyState>('verifying');
  protected readonly problem = signal<ApiProblem | null>(null);

  protected readonly email = signal('');
  protected readonly resendBusy = signal(false);
  protected readonly resentKey = signal<string | null>(null);
  protected readonly resendErrors = signal<FormErrors>(NO_FORM_ERRORS);
  protected readonly resendProblem = signal<ApiProblem | null>(null);

  constructor() {
    this.token = takeEmailToken('/auth/verify');
    if (this.token === null) {
      this.state.set('invalid');
    } else {
      void this.verify();
    }
  }

  protected async verify(): Promise<void> {
    if (this.token === null) {
      return;
    }
    this.state.set('verifying');
    this.problem.set(null);
    try {
      await firstValueFrom(this.api.verifyEmail({ token: this.token }));
      this.state.set('verified');
      if (this.session.isAuthenticated()) {
        await this.session.reloadMe();
      }
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      switch (problem.code) {
        case 'token_expired':
          this.state.set('expired');
          break;
        case 'token_invalid':
        case 'validation_failed':
          this.state.set('invalid');
          break;
        default:
          this.problem.set(problem);
          this.state.set('failed');
      }
    }
  }

  protected async resend(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.resendBusy()) {
      return;
    }
    const email = this.email().trim();
    const errors = formErrors(
      email === ''
        ? [[this.ids.email, 'validation.required']]
        : isEmailShaped(email)
          ? []
          : [[this.ids.email, 'auth.errors.email']],
    );
    this.resendErrors.set(errors);
    this.resendProblem.set(null);
    this.resentKey.set(null);
    if (errors.summary.length > 0) {
      return;
    }
    this.resendBusy.set(true);
    try {
      const response = await firstValueFrom(this.api.resendVerification({ email }));
      this.resentKey.set(serverMessageKey(response.messageKey, 'auth.verify.resent'));
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      if (problem.code === 'validation_failed' && problem.errors.length > 0) {
        this.resendErrors.set(
          problemFormErrors(problem, { email: this.ids.email }, this.ids.email, authFieldMessage),
        );
      } else {
        this.resendProblem.set(problem);
      }
    } finally {
      this.resendBusy.set(false);
    }
  }
}

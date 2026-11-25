import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { ErrorSummaryComponent } from '../../shared/form/error-summary.component';
import { ControlDirective, FieldComponent } from '../../shared/form/field.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import {
  FormErrors,
  NO_FORM_ERRORS,
  formErrors,
  problemFormErrors,
  serverMessageKey,
} from '../../shared/form/field-errors';
import { authFieldMessage, isEmailShaped } from './form-errors';

/**
 * `/auth/forgot` (project specification §8.2). The API answers every address with the same
 * generic confirmation; the page shows it and disables the button ("Link sent").
 */
@Component({
  selector: 'vd-auth-forgot-page',
  imports: [
    ControlDirective,
    ErrorSummaryComponent,
    FieldComponent,
    NoticeComponent,
    ProblemNoticeComponent,
    RouterLink,
    TranslocoPipe,
  ],
  templateUrl: './auth-forgot-page.component.html',
  styleUrl: './auth-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthForgotPageComponent {
  private readonly api = inject(ApiClient);

  protected readonly ids = { email: 'forgot-email' } as const;
  protected readonly email = signal('');
  protected readonly busy = signal(false);
  protected readonly errors = signal<FormErrors>(NO_FORM_ERRORS);
  protected readonly problem = signal<ApiProblem | null>(null);
  protected readonly sentKey = signal<string | null>(null);

  protected async submit(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.busy() || this.sentKey() !== null) {
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
    this.errors.set(errors);
    this.problem.set(null);
    if (errors.summary.length > 0) {
      return;
    }
    this.busy.set(true);
    try {
      const response = await firstValueFrom(this.api.forgotPassword({ email }));
      this.sentKey.set(serverMessageKey(response.messageKey, 'auth.forgot.check_email'));
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      if (problem.code === 'validation_failed' && problem.errors.length > 0) {
        this.errors.set(
          problemFormErrors(problem, { email: this.ids.email }, this.ids.email, authFieldMessage),
        );
      } else {
        this.problem.set(problem);
      }
    } finally {
      this.busy.set(false);
    }
  }
}

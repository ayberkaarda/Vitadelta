import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { safeReturnUrl } from '../../core/auth/return-url';
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
} from '../../shared/form/field-errors';
import { authFieldMessage, isEmailShaped } from './form-errors';

/**
 * `/auth/login` (project specification §8.2, §10.4; design hand-off §4.7). After a successful
 * login the user returns to `returnUrl`, which is only ever used through the allow-list helper
 * and `router.navigateByUrl`. `reason=session_expired` shows the "session ended" banner.
 */
@Component({
  selector: 'vd-auth-login-page',
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
  templateUrl: './auth-login-page.component.html',
  styleUrl: './auth-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthLoginPageComponent {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  /** Query parameter; untrusted until it passed {@link safeReturnUrl}. */
  readonly returnUrl = input<string>();
  /** `session_expired` after a failed refresh. */
  readonly reason = input<string>();
  /** `done` after a successful password reset. */
  readonly reset = input<string>();

  protected readonly ids = { email: 'login-email', password: 'login-password' } as const;
  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly errors = signal<FormErrors>(NO_FORM_ERRORS);
  protected readonly problem = signal<ApiProblem | null>(null);
  protected readonly disabled = signal(false);

  protected async submit(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.busy()) {
      return;
    }
    const email = this.email().trim();
    const password = this.password();
    const errors = formErrors([
      ...(email === ''
        ? [[this.ids.email, 'validation.required'] as const]
        : isEmailShaped(email)
          ? []
          : [[this.ids.email, 'auth.errors.email'] as const]),
      ...(password === '' ? [[this.ids.password, 'validation.required'] as const] : []),
    ]);
    this.errors.set(errors);
    this.problem.set(null);
    if (errors.summary.length > 0) {
      return;
    }
    this.busy.set(true);
    try {
      const response = await firstValueFrom(this.api.login({ email, password }));
      this.password.set('');
      await this.session.signIn(response);
      await this.router.navigateByUrl(safeReturnUrl(this.returnUrl()));
    } catch (error: unknown) {
      this.onError(ApiProblem.from(error));
    } finally {
      this.busy.set(false);
    }
  }

  private onError(problem: ApiProblem): void {
    if (problem.code === 'account_disabled') {
      this.password.set('');
      this.disabled.set(true);
      return;
    }
    if (problem.code === 'validation_failed' && problem.errors.length > 0) {
      this.errors.set(
        problemFormErrors(
          problem,
          { email: this.ids.email, password: this.ids.password },
          this.ids.email,
          authFieldMessage,
        ),
      );
      return;
    }
    this.problem.set(problem);
  }
}

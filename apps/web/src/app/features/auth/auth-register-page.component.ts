import { ChangeDetectionStrategy, Component, inject, linkedSignal, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { Formatter } from '../../core/format/formatter';
import { RegionStore } from '../../core/state/region.store';
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
import { authFieldMessage, isEmailShaped, MIN_PASSWORD_LENGTH } from './form-errors';

/**
 * `/auth/register` (project specification §8.2; design hand-off §4.7). The API answers every
 * accepted registration with the same generic message, whether or not the address exists; the
 * page then shows the "check your e-mail" state with a resend button.
 */
@Component({
  selector: 'vd-auth-register-page',
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
  templateUrl: './auth-register-page.component.html',
  styleUrl: './auth-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthRegisterPageComponent {
  private readonly api = inject(ApiClient);
  protected readonly region = inject(RegionStore);
  protected readonly format = inject(Formatter);

  protected readonly ids = {
    email: 'register-email',
    password: 'register-password',
    country: 'register-country',
  } as const;

  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly country = linkedSignal(() => this.region.countryCode() ?? '');
  protected readonly busy = signal(false);
  protected readonly errors = signal<FormErrors>(NO_FORM_ERRORS);
  protected readonly problem = signal<ApiProblem | null>(null);

  /** Set once the API accepted the registration: the generic message key and the address. */
  protected readonly sent = signal<{ readonly messageKey: string; readonly email: string } | null>(
    null,
  );
  protected readonly resendBusy = signal(false);
  protected readonly resentKey = signal<string | null>(null);
  protected readonly resendProblem = signal<ApiProblem | null>(null);

  constructor() {
    void this.region.loadCountries();
  }

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
      ...(password.length < MIN_PASSWORD_LENGTH
        ? [[this.ids.password, 'validation.too_short'] as const]
        : []),
    ]);
    this.errors.set(errors);
    this.problem.set(null);
    if (errors.summary.length > 0) {
      return;
    }
    this.busy.set(true);
    try {
      const country = this.country();
      const response = await firstValueFrom(
        this.api.register({
          email,
          password,
          locale: this.region.lang(),
          ...(country ? { countryCode: country } : {}),
        }),
      );
      this.password.set('');
      this.sent.set({
        messageKey: serverMessageKey(response.messageKey, 'auth.register.check_email'),
        email,
      });
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      if (problem.code === 'validation_failed' && problem.errors.length > 0) {
        this.errors.set(
          problemFormErrors(
            problem,
            {
              email: this.ids.email,
              password: this.ids.password,
              countryCode: this.ids.country,
              locale: this.ids.country,
            },
            this.ids.email,
            authFieldMessage,
          ),
        );
      } else if (problem.code === 'country_not_found') {
        this.errors.set(formErrors([[this.ids.country, 'auth.errors.country']]));
      } else {
        this.problem.set(problem);
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected async resend(): Promise<void> {
    const sent = this.sent();
    if (!sent || this.resendBusy()) {
      return;
    }
    this.resendBusy.set(true);
    this.resentKey.set(null);
    this.resendProblem.set(null);
    try {
      const response = await firstValueFrom(this.api.resendVerification({ email: sent.email }));
      this.resentKey.set(serverMessageKey(response.messageKey, 'auth.verify.resent'));
    } catch (error: unknown) {
      this.resendProblem.set(ApiProblem.from(error));
    } finally {
      this.resendBusy.set(false);
    }
  }
}

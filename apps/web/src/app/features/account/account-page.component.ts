import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { UpdateMeRequest } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { AppLang, isAppLang } from '../../core/i18n/languages';
import { CurrencyStore } from '../../core/state/currency.store';
import { RegionStore } from '../../core/state/region.store';
import { ConfirmDialogComponent } from '../../shared/dialog/confirm-dialog.component';
import { ErrorSummaryComponent } from '../../shared/form/error-summary.component';
import { ControlDirective, FieldComponent } from '../../shared/form/field.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { NoticeComponent } from '../../shared/notice/notice.component';
import { ProblemNoticeComponent } from '../../shared/notice/problem-notice.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import {
  FormErrors,
  NO_FORM_ERRORS,
  formErrors,
  problemFormErrors,
  serverMessageKey,
} from '../../shared/form/field-errors';
import { PlanCardComponent } from './components/plan-card.component';
import { PromoFormComponent } from './components/promo-form.component';

/**
 * `/account` (project specification §10.2; design hand-off §4.8): preferences saved with
 * `PATCH /me` (default country, language, display currency, alert e-mails — the target of the
 * e-mail footer link `#email-alerts`), verification reminder, plan card with usage meters from
 * `/me`, promo-code form and account deletion behind a confirmation dialog.
 */
@Component({
  selector: 'vd-account-page',
  imports: [
    ControlDirective,
    ConfirmDialogComponent,
    ErrorSummaryComponent,
    FieldComponent,
    IconComponent,
    NoticeComponent,
    PlanCardComponent,
    ProblemNoticeComponent,
    PromoFormComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  templateUrl: './account-page.component.html',
  styleUrl: './account-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountPageComponent {
  private readonly api = inject(ApiClient);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);
  protected readonly region = inject(RegionStore);
  protected readonly format = inject(Formatter);
  protected readonly currency = inject(CurrencyStore);

  protected readonly me = this.session.me;
  protected readonly ids = {
    country: 'pref-country',
    locale: 'pref-locale',
    currency: 'pref-currency',
    alerts: 'email-alerts',
    deletePassword: 'delete-password',
  } as const;
  protected readonly langs: readonly AppLang[] = ['en', 'tr'];

  protected readonly country = linkedSignal(() => this.me()?.defaultCountryCode ?? '');
  protected readonly locale = linkedSignal<AppLang>(() => this.me()?.locale ?? 'en');
  protected readonly displayCurrency = linkedSignal(() => this.me()?.displayCurrency ?? '');
  protected readonly emailAlerts = linkedSignal(() => this.me()?.emailAlertsEnabled ?? true);
  protected readonly currencyOptions = computed(() =>
    this.currency.currencies().map((entry) => entry.code),
  );

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveErrors = signal<FormErrors>(NO_FORM_ERRORS);
  protected readonly saveProblem = signal<ApiProblem | null>(null);

  protected readonly resendBusy = signal(false);
  protected readonly resentKey = signal<string | null>(null);
  protected readonly resendProblem = signal<ApiProblem | null>(null);

  protected readonly deleteOpen = signal(false);
  protected readonly deletePassword = signal('');
  protected readonly deleting = signal(false);
  protected readonly deleteErrors = signal<FormErrors>(NO_FORM_ERRORS);
  protected readonly deleteProblem = signal<ApiProblem | null>(null);

  constructor() {
    void this.region.loadCountries();
    void this.currency.loadCurrencies();
    void this.session.reloadMe();
  }

  protected onLocale(value: string): void {
    if (isAppLang(value)) {
      this.locale.set(value);
    }
  }

  protected edited(): void {
    this.saved.set(false);
  }

  protected async save(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.saving()) {
      return;
    }
    const body: UpdateMeRequest = {
      locale: this.locale(),
      displayCurrency: this.displayCurrency() || null,
      emailAlertsEnabled: this.emailAlerts(),
      ...(this.country() ? { defaultCountryCode: this.country() } : {}),
    };
    this.saving.set(true);
    this.saved.set(false);
    this.saveErrors.set(NO_FORM_ERRORS);
    this.saveProblem.set(null);
    try {
      const me = await firstValueFrom(this.api.updateMe(body));
      this.session.applyMe(me);
      this.currency.setDisplayCurrency(me.displayCurrency);
      void this.region.setLang(me.locale);
      this.saved.set(true);
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      if (problem.code === 'validation_failed' && problem.errors.length > 0) {
        this.saveErrors.set(
          problemFormErrors(
            problem,
            {
              defaultCountryCode: this.ids.country,
              locale: this.ids.locale,
              displayCurrency: this.ids.currency,
              emailAlertsEnabled: this.ids.alerts,
            },
            this.ids.country,
          ),
        );
      } else if (problem.code === 'fx_unsupported_currency') {
        this.saveErrors.set(formErrors([[this.ids.currency, 'account.prefs.currency_error']]));
      } else if (problem.code === 'country_not_found') {
        this.saveErrors.set(formErrors([[this.ids.country, 'account.prefs.country_error']]));
      } else {
        this.saveProblem.set(problem);
      }
    } finally {
      this.saving.set(false);
    }
  }

  protected async resendVerification(): Promise<void> {
    const email = this.me()?.email;
    if (!email || this.resendBusy()) {
      return;
    }
    this.resendBusy.set(true);
    this.resentKey.set(null);
    this.resendProblem.set(null);
    try {
      const response = await firstValueFrom(this.api.resendVerification({ email }));
      this.resentKey.set(serverMessageKey(response.messageKey, 'auth.verify.resent'));
    } catch (error: unknown) {
      this.resendProblem.set(ApiProblem.from(error));
    } finally {
      this.resendBusy.set(false);
    }
  }

  protected openDelete(): void {
    this.deletePassword.set('');
    this.deleteErrors.set(NO_FORM_ERRORS);
    this.deleteProblem.set(null);
    this.deleteOpen.set(true);
  }

  protected closeDelete(): void {
    if (!this.deleting()) {
      this.deletePassword.set('');
      this.deleteOpen.set(false);
    }
  }

  protected async confirmDelete(): Promise<void> {
    if (this.deleting()) {
      return;
    }
    const password = this.deletePassword();
    if (password === '') {
      this.deleteErrors.set(formErrors([[this.ids.deletePassword, 'validation.required']]));
      return;
    }
    this.deleting.set(true);
    this.deleteErrors.set(NO_FORM_ERRORS);
    this.deleteProblem.set(null);
    try {
      await firstValueFrom(this.api.deleteMe({ password }));
      this.deletePassword.set('');
      this.deleteOpen.set(false);
      // The API revoked every session of the deleted account; no logout call is needed.
      await this.session.forget();
      await this.router.navigateByUrl('/');
    } catch (error: unknown) {
      const problem = ApiProblem.from(error);
      if (problem.code === 'invalid_credentials') {
        this.deleteErrors.set(
          formErrors([[this.ids.deletePassword, 'account.delete.wrong_password']]),
        );
      } else if (problem.code === 'validation_failed' && problem.errors.length > 0) {
        this.deleteErrors.set(
          problemFormErrors(
            problem,
            { password: this.ids.deletePassword },
            this.ids.deletePassword,
          ),
        );
      } else {
        this.deleteProblem.set(problem);
      }
    } finally {
      this.deleting.set(false);
    }
  }
}

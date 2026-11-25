import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import authEn from '../../../../public/i18n/auth/en.json';
import authTr from '../../../../public/i18n/auth/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { RegionStore } from '../../core/state/region.store';
import { FIXTURE_COUNTRIES } from '../../testing/api-fixtures';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { AuthForgotPageComponent } from './auth-forgot-page.component';
import { AuthRegisterPageComponent } from './auth-register-page.component';
import { serverMessageKey } from '../../shared/form/field-errors';
import { isEmailShaped } from './form-errors';

async function settle(fixture: { whenStable(): Promise<unknown> }): Promise<void> {
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  await fixture.whenStable();
}

function type(host: HTMLElement, id: string, value: string): void {
  const input = host.querySelector<HTMLInputElement>(`#${id}`);
  if (input) {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }
}

describe('AuthRegisterPageComponent', () => {
  let register: ReturnType<typeof vi.fn>;
  let resendVerification: ReturnType<typeof vi.fn>;

  async function setup() {
    register = vi.fn(() => of({ messageKey: 'auth.register.check_email' }));
    resendVerification = vi.fn(() => of({ messageKey: 'auth.verify.resent' }));
    TestBed.configureTestingModule({
      imports: [
        AuthRegisterPageComponent,
        translocoTesting({ scopes: { auth: { en: authEn, tr: authTr } } }),
      ],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: { register, resendVerification } },
        {
          provide: RegionStore,
          useValue: {
            countries: signal(FIXTURE_COUNTRIES),
            countryCode: signal('TR'),
            lang: signal('en'),
            loadCountries: vi.fn(async () => undefined),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(AuthRegisterPageComponent);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    const submit = async () => {
      host.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
      await settle(fixture);
    };
    return { fixture, host, submit };
  }

  it('registers with locale and country and shows the generic check-your-e-mail state', async () => {
    const { fixture, host, submit } = await setup();
    expect(host.querySelector<HTMLSelectElement>('#register-country')?.value).toBe('TR');
    type(host, 'register-email', 'new@example.com');
    type(host, 'register-password', 'correct-horse-battery');
    await submit();
    expect(register).toHaveBeenCalledWith({
      email: 'new@example.com',
      password: 'correct-horse-battery',
      locale: 'en',
      countryCode: 'TR',
    });
    expect(host.querySelector('h1')?.textContent).toContain(authEn.register.sent_title);
    expect(host.textContent).toContain(EN.auth.register.check_email);
    expect(host.querySelector('input[type="password"]')).toBeNull();

    host.querySelector<HTMLButtonElement>('button')?.click();
    await settle(fixture);
    expect(resendVerification).toHaveBeenCalledWith({ email: 'new@example.com' });
  });

  it('never renders an unknown server message key', async () => {
    const { host, submit } = await setup();
    register.mockReturnValue(of({ messageKey: 'admin.secret' }));
    type(host, 'register-email', 'new@example.com');
    type(host, 'register-password', 'correct-horse-battery');
    await submit();
    expect(host.textContent).toContain(EN.auth.register.check_email);
  });

  it('enforces the password rule before calling the API', async () => {
    const { host, submit } = await setup();
    type(host, 'register-email', 'new@example.com');
    type(host, 'register-password', 'short');
    await submit();
    expect(register).not.toHaveBeenCalled();
    expect(host.querySelector('#register-password-error')?.textContent).toContain(
      EN.validation.too_short,
    );
  });

  it('shows server field errors such as a breached password', async () => {
    const { host, submit } = await setup();
    register.mockReturnValue(
      throwError(
        () =>
          new ApiProblem({
            kind: 'problem',
            status: 422,
            code: 'validation_failed',
            errors: [{ field: 'password', code: 'breached_password', message: 'x' }],
          }),
      ),
    );
    type(host, 'register-email', 'new@example.com');
    type(host, 'register-password', 'password1234');
    await submit();
    expect(host.querySelector('#register-password')?.getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#register-password-error')?.textContent).toContain(
      EN.validation.breached_password,
    );
  });

  it('shows a rate-limit notice', async () => {
    const { host, submit } = await setup();
    register.mockReturnValue(
      throwError(() => new ApiProblem({ kind: 'problem', status: 429, code: 'rate_limited' })),
    );
    type(host, 'register-email', 'new@example.com');
    type(host, 'register-password', 'correct-horse-battery');
    await submit();
    expect(host.textContent).toContain(EN.problem.rate_limited.title);
  });
});

describe('AuthForgotPageComponent', () => {
  it('always shows the generic confirmation and disables the button', async () => {
    const forgotPassword = vi.fn(() => of({ messageKey: 'auth.forgot.check_email' }));
    TestBed.configureTestingModule({
      imports: [
        AuthForgotPageComponent,
        translocoTesting({ scopes: { auth: { en: authEn, tr: authTr } } }),
      ],
      providers: [provideRouter([]), { provide: ApiClient, useValue: { forgotPassword } }],
    });
    const fixture = TestBed.createComponent(AuthForgotPageComponent);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    host.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(forgotPassword).not.toHaveBeenCalled();

    type(host, 'forgot-email', 'someone@example.com');
    host.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(forgotPassword).toHaveBeenCalledWith({ email: 'someone@example.com' });
    const button = host.querySelector<HTMLButtonElement>('button[type="submit"]');
    expect(button?.disabled).toBe(true);
    expect(button?.textContent).toContain(authEn.forgot.sent);
    expect(host.textContent).toContain(EN.auth.forgot.check_email);
  });

  it('renders a rate-limit problem', async () => {
    const forgotPassword = vi.fn(() =>
      throwError(() => new ApiProblem({ kind: 'problem', status: 429, code: 'rate_limited' })),
    );
    TestBed.configureTestingModule({
      imports: [
        AuthForgotPageComponent,
        translocoTesting({ scopes: { auth: { en: authEn, tr: authTr } } }),
      ],
      providers: [provideRouter([]), { provide: ApiClient, useValue: { forgotPassword } }],
    });
    const fixture = TestBed.createComponent(AuthForgotPageComponent);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    type(host, 'forgot-email', 'someone@example.com');
    host.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(host.textContent).toContain(EN.problem.rate_limited.title);
  });
});

describe('form helpers', () => {
  it('checks the rough e-mail shape', () => {
    expect(isEmailShaped('a@b.co')).toBe(true);
    expect(isEmailShaped('a@b')).toBe(false);
    expect(isEmailShaped('not an email')).toBe(false);
  });

  it('accepts only known server message keys', () => {
    expect(serverMessageKey('auth.forgot.check_email', 'auth.verify.resent')).toBe(
      'auth.forgot.check_email',
    );
    expect(serverMessageKey('anything.else', 'auth.verify.resent')).toBe('auth.verify.resent');
    expect(serverMessageKey(undefined, 'auth.verify.resent')).toBe('auth.verify.resent');
  });
});

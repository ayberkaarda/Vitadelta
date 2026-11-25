import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import authEn from '../../../../public/i18n/auth/en.json';
import authTr from '../../../../public/i18n/auth/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { SessionStore } from '../../core/auth/session.store';
import { fixtureTokenResponse } from '../../testing/api-fixtures';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { AuthLoginPageComponent } from './auth-login-page.component';

describe('AuthLoginPageComponent', () => {
  let fixture: ComponentFixture<AuthLoginPageComponent>;
  let login: ReturnType<typeof vi.fn>;
  let signIn: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.spyOn>;

  const host = () => fixture.nativeElement as HTMLElement;
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  };
  const type = (id: string, value: string) => {
    const input = host().querySelector<HTMLInputElement>(`#${id}`);
    if (!input) {
      throw new Error(`missing #${id}`);
    }
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };
  const submit = async () => {
    host()
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
  };

  async function setup(inputs: Record<string, string> = {}): Promise<void> {
    login = vi.fn(() => of(fixtureTokenResponse()));
    signIn = vi.fn(async () => undefined);
    TestBed.configureTestingModule({
      imports: [
        AuthLoginPageComponent,
        translocoTesting({ scopes: { auth: { en: authEn, tr: authTr } } }),
      ],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: { login } },
        { provide: SessionStore, useValue: { signIn } },
      ],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(AuthLoginPageComponent);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    await settle();
  }

  async function fillAndSubmit(): Promise<void> {
    type('login-email', 'user@example.com');
    type('login-password', 'correct-horse-battery');
    await submit();
  }

  it('signs in and returns to an app-relative returnUrl', async () => {
    await setup({ returnUrl: '/account/saved' });
    await fillAndSubmit();
    expect(login).toHaveBeenCalledWith({
      email: 'user@example.com',
      password: 'correct-horse-battery',
    });
    expect(signIn).toHaveBeenCalledWith(fixtureTokenResponse());
    expect(navigate).toHaveBeenCalledWith('/account/saved');
  });

  it.each(['https://evil.example', '//evil.example', '/\\evil.example', 'javascript:alert(1)'])(
    'falls back to the start page for the hostile returnUrl %s',
    async (hostile) => {
      await setup({ returnUrl: hostile });
      await fillAndSubmit();
      expect(navigate).toHaveBeenCalledTimes(1);
      expect(navigate).toHaveBeenCalledWith('/');
    },
  );

  it('goes to the start page without a returnUrl', async () => {
    await setup();
    await fillAndSubmit();
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('validates required fields on the client without calling the API', async () => {
    await setup();
    await submit();
    expect(login).not.toHaveBeenCalled();
    expect(host().querySelector('#login-email')?.getAttribute('aria-invalid')).toBe('true');
    expect(host().querySelector('#login-password')?.getAttribute('aria-invalid')).toBe('true');
    expect(host().querySelector('[role="alert"]')?.textContent).toContain(EN.validation.required);
  });

  it('shows the invalid-credentials alert and stays on the page', async () => {
    await setup();
    login.mockReturnValue(
      throwError(
        () => new ApiProblem({ kind: 'problem', status: 401, code: 'invalid_credentials' }),
      ),
    );
    await fillAndSubmit();
    expect(host().textContent).toContain(EN.problem.invalid_credentials.body);
    expect(navigate).not.toHaveBeenCalled();
    expect(signIn).not.toHaveBeenCalled();
  });

  it('replaces the form with the disabled-account state', async () => {
    await setup();
    login.mockReturnValue(
      throwError(() => new ApiProblem({ kind: 'problem', status: 403, code: 'account_disabled' })),
    );
    await fillAndSubmit();
    expect(host().querySelector('form')).toBeNull();
    expect(host().querySelector('h1')?.textContent).toContain(EN.problem.account_disabled.title);
    expect(host().textContent).toContain(authEn.login.disabled_body);
    expect(host().querySelector('a[href="/privacy"]')).not.toBeNull();
  });

  it('shows the retry-after countdown when rate limited', async () => {
    await setup();
    login.mockReturnValue(
      throwError(
        () =>
          new ApiProblem({
            kind: 'problem',
            status: 429,
            code: 'rate_limited',
            retryAfterSeconds: 30,
          }),
      ),
    );
    await fillAndSubmit();
    expect(host().textContent).toContain(EN.problem.rate_limited.title);
    expect(host().textContent).toContain('Try again in 30 s');
  });

  it('maps validation_failed field errors to the controls', async () => {
    await setup();
    login.mockReturnValue(
      throwError(
        () =>
          new ApiProblem({
            kind: 'problem',
            status: 422,
            code: 'validation_failed',
            errors: [{ field: 'email', code: 'invalid_format', message: 'bad' }],
          }),
      ),
    );
    await fillAndSubmit();
    const email = host().querySelector('#login-email');
    expect(email?.getAttribute('aria-invalid')).toBe('true');
    expect(host().querySelector('#login-email-error')?.textContent).toContain(authEn.errors.email);
  });

  it('shows the session-ended banner and the password-changed notice', async () => {
    await setup({ reason: 'session_expired', reset: 'done' });
    expect(host().querySelector('[data-notice="session-ended"]')?.textContent).toContain(
      authEn.login.session_ended,
    );
    expect(host().querySelector('[data-notice="password-changed"]')?.textContent).toContain(
      authEn.login.password_changed,
    );
  });

  it('never puts the password into the URL or the console', async () => {
    const log = vi.spyOn(console, 'log');
    await setup();
    await fillAndSubmit();
    expect(navigate.mock.calls.flat().join(' ')).not.toContain('correct-horse-battery');
    expect(log.mock.calls.flat().join(' ')).not.toContain('correct-horse-battery');
  });
});

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import authEn from '../../../../public/i18n/auth/en.json';
import authTr from '../../../../public/i18n/auth/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { AuthResetPageComponent } from './auth-reset-page.component';

const TOKEN = 'cmVzZXQtc3ludGhldGljLXRlc3QtdG9rZW4tMDAwMDAx';

describe('AuthResetPageComponent', () => {
  let fixture: ComponentFixture<AuthResetPageComponent>;
  let resetPassword: ReturnType<typeof vi.fn>;
  let navigate: ReturnType<typeof vi.spyOn>;
  const order: string[] = [];

  const host = () => fixture.nativeElement as HTMLElement;
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  };
  const type = (id: string, value: string) => {
    const input = host().querySelector<HTMLInputElement>(`#${id}`);
    if (input) {
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
  };
  const submit = async () => {
    host()
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
  };

  async function setup(hash = `#token=${TOKEN}`): Promise<void> {
    window.history.replaceState(null, '', `/auth/reset${hash}`);
    order.length = 0;
    const replaceState = window.history.replaceState.bind(window.history);
    vi.spyOn(window.history, 'replaceState').mockImplementation((...args) => {
      order.push('replaceState');
      replaceState(...args);
    });
    resetPassword = vi.fn(() => {
      order.push('reset');
      return of(undefined);
    });
    TestBed.configureTestingModule({
      imports: [
        AuthResetPageComponent,
        translocoTesting({ scopes: { auth: { en: authEn, tr: authTr } } }),
      ],
      providers: [provideRouter([]), { provide: ApiClient, useValue: { resetPassword } }],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(AuthResetPageComponent);
    await settle();
  }

  afterEach(() => {
    vi.restoreAllMocks();
    window.history.replaceState(null, '', '/');
  });

  it('removes the fragment at once and posts the token with the new password', async () => {
    await setup();
    expect(window.location.hash).toBe('');
    expect(order).toEqual(['replaceState']);
    type('reset-password', 'a-new-long-password');
    type('reset-repeat', 'a-new-long-password');
    await submit();
    expect(order).toEqual(['replaceState', 'reset']);
    expect(resetPassword).toHaveBeenCalledWith({
      token: TOKEN,
      newPassword: 'a-new-long-password',
    });
    expect(navigate).toHaveBeenCalledWith('/auth/login?reset=done');
  });

  it('checks length and repetition before calling the API', async () => {
    await setup();
    type('reset-password', 'short');
    type('reset-repeat', 'other');
    await submit();
    expect(resetPassword).not.toHaveBeenCalled();
    expect(host().querySelector('#reset-password-error')?.textContent).toContain(
      EN.validation.too_short,
    );
    expect(host().querySelector('#reset-repeat-error')?.textContent).toContain(
      authEn.errors.password_mismatch,
    );
  });

  it('shows the breached-password error from the server', async () => {
    await setup();
    resetPassword.mockReturnValue(
      throwError(
        () =>
          new ApiProblem({
            kind: 'problem',
            status: 422,
            code: 'validation_failed',
            errors: [{ field: 'newPassword', code: 'breached_password', message: 'x' }],
          }),
      ),
    );
    type('reset-password', 'password1234');
    type('reset-repeat', 'password1234');
    await submit();
    expect(host().querySelector('#reset-password-error')?.textContent).toContain(
      EN.validation.breached_password,
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it.each(['token_invalid', 'token_expired'] as const)(
    'switches to the no-longer-valid state on %s',
    async (code) => {
      await setup();
      resetPassword.mockReturnValue(
        throwError(() => new ApiProblem({ kind: 'problem', status: 400, code })),
      );
      type('reset-password', 'a-new-long-password');
      type('reset-repeat', 'a-new-long-password');
      await submit();
      expect(host().querySelector('h1')?.textContent).toContain(authEn.reset.invalid_title);
      expect(host().querySelector('a[href="/auth/forgot"]')).not.toBeNull();
    },
  );

  it('shows the invalid state at once when the link has no token', async () => {
    await setup('');
    expect(host().querySelector('form')).toBeNull();
    expect(host().querySelector('h1')?.textContent).toContain(authEn.reset.invalid_title);
  });

  it('shows other problems as a notice', async () => {
    await setup();
    resetPassword.mockReturnValue(
      throwError(
        () =>
          new ApiProblem({
            kind: 'problem',
            status: 429,
            code: 'rate_limited',
            retryAfterSeconds: 5,
          }),
      ),
    );
    type('reset-password', 'a-new-long-password');
    type('reset-repeat', 'a-new-long-password');
    await submit();
    expect(host().textContent).toContain(EN.problem.rate_limited.title);
  });
});

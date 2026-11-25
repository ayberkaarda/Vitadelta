import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';

import authEn from '../../../../public/i18n/auth/en.json';
import authTr from '../../../../public/i18n/auth/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { SessionStore } from '../../core/auth/session.store';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { AuthVerifyPageComponent } from './auth-verify-page.component';

const TOKEN = 'dGhpcy1pcy1hLXN5bnRoZXRpYy10ZXN0LXRva2VuLTAx';

describe('AuthVerifyPageComponent', () => {
  let fixture: ComponentFixture<AuthVerifyPageComponent>;
  let verifyEmail: ReturnType<typeof vi.fn>;
  let resendVerification: ReturnType<typeof vi.fn>;
  let reloadMe: ReturnType<typeof vi.fn>;
  const calls: string[] = [];

  const host = () => fixture.nativeElement as HTMLElement;
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  };

  async function setup(
    hash: string,
    verify: () => Observable<void>,
    authenticated = false,
  ): Promise<void> {
    window.history.replaceState(null, '', `/auth/verify${hash}`);
    calls.length = 0;
    const replaceState = window.history.replaceState.bind(window.history);
    vi.spyOn(window.history, 'replaceState').mockImplementation((...args) => {
      calls.push('replaceState');
      replaceState(...args);
    });
    verifyEmail = vi.fn((body: unknown) => {
      calls.push(`verify:${JSON.stringify(body)}`);
      return verify();
    });
    resendVerification = vi.fn(() => of({ messageKey: 'auth.verify.resent' }));
    reloadMe = vi.fn(async () => undefined);
    TestBed.configureTestingModule({
      imports: [
        AuthVerifyPageComponent,
        translocoTesting({ scopes: { auth: { en: authEn, tr: authTr } } }),
      ],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: { verifyEmail, resendVerification } },
        {
          provide: SessionStore,
          useValue: { isAuthenticated: () => authenticated, reloadMe },
        },
      ],
    });
    fixture = TestBed.createComponent(AuthVerifyPageComponent);
    await settle();
  }

  afterEach(() => {
    vi.restoreAllMocks();
    window.history.replaceState(null, '', '/');
  });

  it('reads the token from the fragment, removes it first and posts it in the body', async () => {
    await setup(`#token=${TOKEN}`, () => of(undefined));
    expect(calls).toEqual(['replaceState', `verify:${JSON.stringify({ token: TOKEN })}`]);
    expect(window.location.hash).toBe('');
    expect(window.location.href).not.toContain(TOKEN);
    expect(host().querySelector('h1')?.textContent).toContain(authEn.verify.success_title);
    expect(host().querySelector('a[href="/auth/login"]')).not.toBeNull();
  });

  it('reloads the profile of a signed-in user after verification', async () => {
    await setup(`#token=${TOKEN}`, () => of(undefined), true);
    expect(reloadMe).toHaveBeenCalled();
    expect(host().querySelector('a[href="/account"]')).not.toBeNull();
  });

  it('shows the expired state with a resend form', async () => {
    await setup(`#token=${TOKEN}`, () =>
      throwError(() => new ApiProblem({ kind: 'problem', status: 400, code: 'token_expired' })),
    );
    expect(host().querySelector('h1')?.textContent).toContain(authEn.verify.expired_title);
    const input = host().querySelector<HTMLInputElement>('#verify-email');
    expect(input).not.toBeNull();
    if (input) {
      input.value = 'user@example.com';
      input.dispatchEvent(new Event('input'));
    }
    host()
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(resendVerification).toHaveBeenCalledWith({ email: 'user@example.com' });
    expect(host().textContent).toContain(EN.auth.verify.resent);
  });

  it('shows the invalid state for a rejected token', async () => {
    await setup(`#token=${TOKEN}`, () =>
      throwError(() => new ApiProblem({ kind: 'problem', status: 400, code: 'token_invalid' })),
    );
    expect(host().querySelector('h1')?.textContent).toContain(authEn.verify.invalid_title);
  });

  it('treats a missing or malformed token as invalid without calling the API', async () => {
    await setup('#token=short', () => of(undefined));
    expect(verifyEmail).not.toHaveBeenCalled();
    expect(window.location.hash).toBe('');
    expect(host().querySelector('h1')?.textContent).toContain(authEn.verify.invalid_title);
  });

  it('offers a retry for other failures', async () => {
    let attempts = 0;
    await setup(`#token=${TOKEN}`, () => {
      attempts++;
      return attempts === 1
        ? throwError(() => new ApiProblem({ kind: 'network', status: 0, code: null }))
        : of(undefined);
    });
    expect(host().textContent).toContain(EN.problem.network.title);
    host().querySelector<HTMLButtonElement>('vd-problem-notice button')?.click();
    await settle();
    expect(verifyEmail).toHaveBeenCalledTimes(2);
    expect(host().querySelector('h1')?.textContent).toContain(authEn.verify.success_title);
  });

  it('validates the resend e-mail on the client', async () => {
    await setup('', () => of(undefined));
    host()
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(resendVerification).not.toHaveBeenCalled();
    expect(host().querySelector('#verify-email')?.getAttribute('aria-invalid')).toBe('true');
  });
});

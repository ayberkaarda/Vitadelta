import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { NEVER, Observable, of, throwError } from 'rxjs';

import accountEn from '../../../../../public/i18n/account/en.json';
import accountTr from '../../../../../public/i18n/account/tr.json';
import { ApiClient } from '../../../core/api/api-client';
import { ApiProblem } from '../../../core/api/api-problem';
import { BillingRedirect } from '../../../core/api/api-types';
import { ExternalNavigation } from '../../../core/billing/billing-navigator';
import { EN, translocoTesting } from '../../../testing/transloco-testing';
import { ManageSubscriptionComponent } from './manage-subscription.component';

const PORTAL_URL = 'https://billing.stripe.com/p/session/test_YWNjdF8xUXdFcnR5';

describe('ManageSubscriptionComponent', () => {
  let fixture: ComponentFixture<ManageSubscriptionComponent>;
  let createBillingPortalSession: ReturnType<typeof vi.fn<() => Observable<BillingRedirect>>>;
  let assign: ReturnType<typeof vi.fn>;

  const host = () => fixture.nativeElement as HTMLElement;
  const button = () => {
    const element = host().querySelector<HTMLButtonElement>('button[data-action]');
    if (!element) {
      throw new Error('missing the manage-subscription button');
    }
    return element;
  };
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  };

  async function setup(): Promise<void> {
    createBillingPortalSession = vi.fn(() => of({ url: PORTAL_URL }));
    assign = vi.fn();
    TestBed.configureTestingModule({
      imports: [
        ManageSubscriptionComponent,
        translocoTesting({ scopes: { account: { en: accountEn, tr: accountTr } } }),
      ],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: { createBillingPortalSession } },
        { provide: ExternalNavigation, useValue: { assign } },
      ],
    });
    fixture = TestBed.createComponent(ManageSubscriptionComponent);
    await settle();
  }

  async function fail(problem: ApiProblem): Promise<void> {
    createBillingPortalSession.mockReturnValueOnce(throwError(() => problem));
    button().click();
    await settle();
  }

  it('opens the provider billing portal', async () => {
    await setup();
    expect(button().textContent).toContain(accountEn.billing.manage);
    const hint = host().querySelector(`#${button().getAttribute('aria-describedby')}`);
    expect(hint?.textContent).toContain(accountEn.billing.manage_hint);
    button().click();
    await settle();
    expect(createBillingPortalSession).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith(PORTAL_URL);
  });

  it('shows a busy state and opens one session only', async () => {
    await setup();
    createBillingPortalSession.mockReturnValueOnce(NEVER);
    button().click();
    await settle();
    button().click();
    await settle();
    expect(createBillingPortalSession).toHaveBeenCalledTimes(1);
    expect(button().getAttribute('aria-busy')).toBe('true');
    expect(host().querySelector('[role="status"]')?.textContent).toContain(accountEn.billing.busy);
  });

  it('explains that there is no paid subscription yet (409 no_billing_account)', async () => {
    await setup();
    await fail(new ApiProblem({ kind: 'problem', status: 409, code: 'no_billing_account' }));
    const notice = host().querySelector('[data-code="no_billing_account"]');
    expect(notice?.textContent).toContain(EN.problem.no_billing_account.title);
    expect(notice?.textContent).toContain(EN.problem.no_billing_account.body);
    expect(notice?.querySelector('[data-tone="info"]')).not.toBeNull();
    expect(button().getAttribute('aria-busy')).toBe('false');
    expect(assign).not.toHaveBeenCalled();
  });

  it('offers a retry when the payment service is unavailable (503)', async () => {
    await setup();
    await fail(new ApiProblem({ kind: 'problem', status: 503, code: 'billing_unavailable' }));
    const notice = host().querySelector('[data-code="billing_unavailable"]');
    expect(notice?.textContent).toContain(EN.problem.billing_unavailable.title);
    notice?.querySelector<HTMLButtonElement>('button')?.click();
    await settle();
    expect(createBillingPortalSession).toHaveBeenCalledTimes(2);
    expect(assign).toHaveBeenCalledWith(PORTAL_URL);
  });

  it('keeps the rate-limit countdown (429)', async () => {
    await setup();
    await fail(
      new ApiProblem({ kind: 'problem', status: 429, code: 'rate_limited', retryAfterSeconds: 12 }),
    );
    expect(host().querySelector('[data-code="rate_limited"] button')?.textContent).toContain(
      'Try again in 12 s',
    );
  });

  it('sends a signed-out user to the login page (401)', async () => {
    await setup();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    await fail(new ApiProblem({ kind: 'problem', status: 401, code: 'unauthorized' }));
    expect(navigate).toHaveBeenCalledWith(['/auth/login'], { queryParams: {} });
    expect(host().querySelector('vd-problem-notice, vd-notice')).toBeNull();
  });
});

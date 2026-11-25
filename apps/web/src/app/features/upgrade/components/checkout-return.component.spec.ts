import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import upgradeEn from '../../../../../public/i18n/upgrade/en.json';
import upgradeTr from '../../../../../public/i18n/upgrade/tr.json';
import { Me } from '../../../core/api/api-types';
import { SessionStore } from '../../../core/auth/session.store';
import { fixtureMe } from '../../../testing/api-fixtures';
import { provideTestLocale } from '../../../testing/test-providers';
import { translocoTesting } from '../../../testing/transloco-testing';
import {
  CHECKOUT_CONFIRM_DELAYS,
  CheckoutOutcome,
  CheckoutReturnComponent,
  checkoutOutcome,
} from './checkout-return.component';

const FREE_ME: Me = fixtureMe();
const PRO_ME: Me = fixtureMe({
  plan: {
    code: 'PRO',
    name: 'Pro',
    savedProductsLimit: null,
    alertSubscriptionsLimit: null,
    notificationsPerMonthLimit: null,
  },
  subscription: {
    id: '5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b',
    planCode: 'PRO',
    status: 'ACTIVE',
    source: 'PROVIDER',
    startedAt: '2026-11-25T10:00:00Z',
    currentPeriodEnd: '2026-12-25T10:00:00Z',
    canceledAt: null,
  },
});

describe('checkoutOutcome', () => {
  it.each([
    ['success', 'success'],
    ['canceled', 'canceled'],
    ['cancelled', null],
    ['SUCCESS', null],
    ['', null],
    [null, null],
  ] as const)('reads %j as %j', (value, expected) => {
    expect(checkoutOutcome(value)).toBe(expected);
  });
});

describe('CheckoutReturnComponent', () => {
  let fixture: ComponentFixture<CheckoutReturnComponent>;
  let me: ReturnType<typeof signal<Me | null>>;
  let authenticated: ReturnType<typeof signal<boolean>>;
  let reloadMe: ReturnType<typeof vi.fn<() => Promise<void>>>;

  const host = () => fixture.nativeElement as HTMLElement;
  const shown = () => host().querySelector('[data-return]')?.getAttribute('data-return') ?? null;

  function configure(initial: Me | null): void {
    me = signal(initial);
    authenticated = signal(initial !== null);
    reloadMe = vi.fn(async () => undefined);
    TestBed.configureTestingModule({
      imports: [
        CheckoutReturnComponent,
        translocoTesting({ scopes: { upgrade: { en: upgradeEn, tr: upgradeTr } } }),
      ],
      providers: [
        provideRouter([]),
        provideTestLocale('en-US'),
        {
          provide: SessionStore,
          useValue: {
            isAuthenticated: authenticated,
            me,
            reloadMe,
            whenReady: async () => undefined,
          },
        },
        { provide: CHECKOUT_CONFIRM_DELAYS, useValue: [1, 2, 3] },
      ],
    });
  }

  function create(outcome: CheckoutOutcome): void {
    fixture = TestBed.createComponent(CheckoutReturnComponent);
    fixture.componentRef.setInput('outcome', outcome);
    fixture.detectChanges();
  }

  /** Lets the short test pauses and the reloads run to the end. */
  async function drain(): Promise<void> {
    for (let step = 0; step < 15; step++) {
      await new Promise((resolve) => setTimeout(resolve, 5));
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  it('waits about 15 s in four growing pauses by default', () => {
    TestBed.configureTestingModule({});
    expect(TestBed.inject(CHECKOUT_CONFIRM_DELAYS)).toEqual([1000, 2000, 4000, 8000]);
  });

  it('says that nothing was charged after a canceled checkout', async () => {
    configure(FREE_ME);
    create('canceled');
    await drain();
    expect(shown()).toBe('canceled');
    expect(host().textContent).toContain(upgradeEn.return.canceled_text);
    expect(reloadMe).not.toHaveBeenCalled();
  });

  it('reports PRO at once when /me already says so', async () => {
    configure(PRO_ME);
    create('success');
    await drain();
    expect(shown()).toBe('active');
    expect(host().textContent).toContain('PRO is active until Dec 25, 2026.');
    expect(host().querySelector('a[href="/account"]')).not.toBeNull();
    expect(reloadMe).not.toHaveBeenCalled();
  });

  it('reloads /me with growing pauses until the webhook has landed', async () => {
    configure(FREE_ME);
    reloadMe.mockImplementationOnce(async () => undefined);
    reloadMe.mockImplementationOnce(async () => me.set(PRO_ME));
    create('success');
    expect(shown()).toBe('confirming');
    expect(host().querySelector('[aria-live="polite"]')?.getAttribute('aria-busy')).toBe('true');
    await drain();
    expect(reloadMe).toHaveBeenCalledTimes(2);
    expect(shown()).toBe('active');
  });

  it('stops after the last attempt, says the payment is being processed and checks again on request', async () => {
    configure(FREE_ME);
    create('success');
    await drain();
    // One reload after each of the three pauses, then it stops.
    expect(reloadMe).toHaveBeenCalledTimes(3);
    expect(shown()).toBe('pending');
    expect(host().textContent).toContain(upgradeEn.return.pending_text);

    reloadMe.mockImplementationOnce(async () => me.set(PRO_ME));
    host().querySelector<HTMLButtonElement>('[data-return="pending"] button')?.click();
    await drain();
    // A repeated check looks at a fresh /me before waiting.
    expect(reloadMe).toHaveBeenCalledTimes(4);
    expect(shown()).toBe('active');
  });

  it('asks a signed-out visitor to log in instead of polling', async () => {
    configure(null);
    create('success');
    await drain();
    expect(shown()).toBe('signed-out');
    expect(
      host().querySelector('a[href="/auth/login?returnUrl=%2Fupgrade"]')?.textContent,
    ).toContain(upgradeEn.signin_login);
    expect(reloadMe).not.toHaveBeenCalled();
  });

  it('stops polling when the page is left', async () => {
    configure(FREE_ME);
    create('success');
    await Promise.resolve();
    fixture.destroy();
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(reloadMe).not.toHaveBeenCalled();
  });
});

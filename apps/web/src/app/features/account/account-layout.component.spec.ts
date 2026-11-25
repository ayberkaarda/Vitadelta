import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import accountEn from '../../../../public/i18n/account/en.json';
import accountTr from '../../../../public/i18n/account/tr.json';
import { SessionStore } from '../../core/auth/session.store';
import { UnreadCountStore } from '../../core/state/unread-count.store';
import { fixtureUser } from '../../testing/api-fixtures';
import { provideTestLocale } from '../../testing/test-providers';
import { translocoTesting } from '../../testing/transloco-testing';
import { AccountLayoutComponent } from './account-layout.component';

let created = 0;

@Component({ selector: 'vd-probe', template: '' })
class ProbeComponent {
  constructor() {
    created += 1;
  }
}

describe('AccountLayoutComponent', () => {
  it('re-creates the routed account page for a new session and removes it when signed out', async () => {
    created = 0;
    const generation = signal(1);
    const authenticated = signal(true);
    TestBed.configureTestingModule({
      imports: [translocoTesting({ scopes: { account: { en: accountEn, tr: accountTr } } })],
      providers: [
        provideTestLocale('en-US'),
        provideRouter([
          {
            path: 'account',
            component: AccountLayoutComponent,
            children: [{ path: '', component: ProbeComponent }],
          },
        ]),
        {
          provide: SessionStore,
          useValue: {
            user: signal(fixtureUser()),
            generation,
            isAuthenticated: authenticated,
          },
        },
        { provide: UnreadCountStore, useValue: { count: signal(null) } },
      ],
    });
    const harness = await RouterTestingHarness.create('/account');
    await harness.fixture.whenStable();
    expect(created).toBe(1);

    generation.set(2);
    await harness.fixture.whenStable();
    expect(created).toBe(2);

    authenticated.set(false);
    await harness.fixture.whenStable();
    expect(harness.routeNativeElement?.querySelector('vd-probe')).toBeNull();
  });
});

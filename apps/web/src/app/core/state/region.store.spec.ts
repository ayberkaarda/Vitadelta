import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';

import { FIXTURE_COUNTRIES, fixtureMe, fixtureUser } from '../../testing/api-fixtures';
import { translocoTesting } from '../../testing/transloco-testing';
import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';
import { UserProfile } from '../api/api-types';
import { SessionStore } from '../auth/session.store';
import { PreferenceStorage } from './preference-storage';
import { RegionStore } from './region.store';

describe('RegionStore', () => {
  let user: ReturnType<typeof signal<UserProfile | null>>;
  let stored: Map<string, string>;
  let api: { listCountries: ReturnType<typeof vi.fn>; updateMe: ReturnType<typeof vi.fn> };
  let applyMe: ReturnType<typeof vi.fn>;

  function create(): RegionStore {
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [
        { provide: ApiClient, useValue: api },
        {
          provide: SessionStore,
          useValue: { user, isAuthenticated: () => user() !== null, applyMe },
        },
        {
          provide: PreferenceStorage,
          useValue: {
            read: (key: string) => stored.get(key) ?? null,
            write: (key: string, value: string | null) =>
              value === null ? stored.delete(key) : stored.set(key, value),
          },
        },
      ],
    });
    return TestBed.inject(RegionStore);
  }

  beforeEach(() => {
    user = signal<UserProfile | null>(null);
    stored = new Map();
    api = {
      listCountries: vi.fn(() => of({ items: [...FIXTURE_COUNTRIES] })),
      updateMe: vi.fn(() => of(fixtureMe({ locale: 'tr' }))),
    };
    applyMe = vi.fn();
  });

  it('loads the countries once and picks a default country', async () => {
    const region = create();
    expect(region.countryCode()).toBeNull();
    await region.loadCountries();
    await region.loadCountries();
    expect(api.listCountries).toHaveBeenCalledTimes(1);
    expect(region.countries()).toHaveLength(3);
    expect(region.countryCode()).not.toBeNull();
  });

  it('retries loading countries after a failure', async () => {
    api.listCountries.mockReturnValueOnce(
      throwError(() => new ApiProblem({ kind: 'network', status: 0, code: null })),
    );
    const region = create();
    await region.loadCountries();
    expect(region.countries()).toEqual([]);
    await region.loadCountries();
    expect(region.countries()).toHaveLength(3);
  });

  it('prefers the stored choice, then the user default country', async () => {
    user.set(fixtureUser({ defaultCountryCode: 'US' }));
    const region = create();
    await region.loadCountries();
    expect(region.countryCode()).toBe('US');
    region.setCountry('DE');
    expect(region.countryCode()).toBe('DE');
    expect(stored.get('country')).toBe('DE');
    region.setCountry('not a code');
    expect(region.countryCode()).toBe('DE');
  });

  it('ignores a stored country that is not supported', async () => {
    stored.set('country', 'ZZ');
    user.set(fixtureUser({ defaultCountryCode: 'TR' }));
    const region = create();
    await region.loadCountries();
    expect(region.countryCode()).toBe('TR');
  });

  it('derives the language from the user, else the stored choice, else the country', async () => {
    const region = create();
    await region.loadCountries();
    region.setCountry('TR');
    expect(region.lang()).toBe('tr');
    expect(region.intlLocale()).toBe('tr-TR');

    stored.set('lang', 'en');
    const second = TestBed.runInInjectionContext(() => new RegionStore());
    await second.loadCountries();
    second.setCountry('TR');
    expect(second.lang()).toBe('en');

    user.set(fixtureUser({ locale: 'tr' }));
    expect(second.lang()).toBe('tr');
  });

  it('switching the language updates Transloco, <html lang> and the profile', async () => {
    user.set(fixtureUser({ locale: 'en' }));
    const region = create();
    await region.setLang('tr');
    TestBed.tick();
    expect(region.lang()).toBe('tr');
    expect(TestBed.inject(TranslocoService).getActiveLang()).toBe('tr');
    expect(document.documentElement.lang).toBe('tr');
    expect(api.updateMe).toHaveBeenCalledWith({ locale: 'tr' });
    expect(applyMe).toHaveBeenCalled();
    expect(stored.get('lang')).toBe('tr');
  });

  it('keeps the switch for the page view when the profile update fails', async () => {
    user.set(fixtureUser({ locale: 'en' }));
    api.updateMe.mockReturnValue(
      throwError(() => new ApiProblem({ kind: 'http', status: 500, code: null })),
    );
    const region = create();
    await region.setLang('tr');
    expect(region.lang()).toBe('tr');
    expect(applyMe).not.toHaveBeenCalled();
  });

  it('does not call the API for anonymous visitors', async () => {
    const region = create();
    await region.setLang('tr');
    expect(api.updateMe).not.toHaveBeenCalled();
  });
});

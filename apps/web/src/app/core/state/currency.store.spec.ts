import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import {
  FIXTURE_COUNTRIES,
  FX_IDENTITY_TRY,
  FX_STALE_EUR,
  FX_UNAVAILABLE_GBP,
  fixtureUser,
} from '../../testing/api-fixtures';
import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';
import { Country, UserProfile } from '../api/api-types';
import { SessionStore } from '../auth/session.store';
import { CurrencyStore } from './currency.store';
import { PreferenceStorage } from './preference-storage';
import { RegionStore } from './region.store';

describe('CurrencyStore', () => {
  let user: ReturnType<typeof signal<UserProfile | null>>;
  let country: ReturnType<typeof signal<Country | null>>;
  let stored: Map<string, string>;
  let api: { listFxCurrencies: ReturnType<typeof vi.fn> };

  function create(): CurrencyStore {
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiClient, useValue: api },
        { provide: SessionStore, useValue: { user } },
        { provide: RegionStore, useValue: { country } },
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
    return TestBed.inject(CurrencyStore);
  }

  beforeEach(() => {
    user = signal<UserProfile | null>(null);
    country = signal<Country | null>(FIXTURE_COUNTRIES[0]);
    stored = new Map();
    api = {
      listFxCurrencies: vi.fn(() =>
        of({
          items: [
            { code: 'EUR', asOf: '2026-11-24', stale: false },
            { code: 'GBP', asOf: null, stale: false },
            { code: 'TRY', asOf: '2026-11-24', stale: false },
            { code: 'USD', asOf: '2026-11-10', stale: true },
          ],
        }),
      ),
    };
  });

  it('defaults to the native currency', () => {
    const store = create();
    expect(store.displayCurrency()).toBeNull();
    expect(store.requestCurrency()).toBeUndefined();
    expect(store.nativeCurrency()).toBe('TRY');
    expect(store.rateState()).toEqual({ kind: 'native' });
  });

  it('uses the user preference until the visitor chooses another currency', () => {
    user.set(fixtureUser({ displayCurrency: 'EUR' }));
    const store = create();
    expect(store.displayCurrency()).toBe('EUR');
    store.setDisplayCurrency(null);
    expect(store.displayCurrency()).toBeNull();
    expect(stored.get('currency')).toBe('NATIVE');
    store.setDisplayCurrency('USD');
    expect(store.requestCurrency()).toBe('USD');
    store.setDisplayCurrency('dollars');
    expect(store.displayCurrency()).toBe('USD');
  });

  it('restores a stored choice and treats the native code as native', () => {
    stored.set('currency', 'TRY');
    expect(create().displayCurrency()).toBeNull();
  });

  it('describes the rate from the currency list until a response is recorded', async () => {
    const store = create();
    await store.loadCurrencies();
    await store.loadCurrencies();
    expect(api.listFxCurrencies).toHaveBeenCalledTimes(1);

    store.setDisplayCurrency('EUR');
    expect(store.rateState()).toEqual({ kind: 'rate', asOf: '2026-11-24' });
    store.setDisplayCurrency('USD');
    expect(store.rateState()).toEqual({ kind: 'stale', asOf: '2026-11-10' });
    store.setDisplayCurrency('GBP');
    expect(store.rateState()).toEqual({ kind: 'unavailable', shownIn: 'TRY' });
  });

  it('prefers the meta.fx of the last response for the selected pair', () => {
    const store = create();
    store.setDisplayCurrency('EUR');
    store.recordMeta({ nativeCurrency: 'TRY', currency: 'EUR', fx: FX_STALE_EUR });
    expect(store.rateState()).toEqual({ kind: 'stale', asOf: '2026-11-17' });
    expect(store.lastMeta()?.fx).toEqual(FX_STALE_EUR);

    store.setDisplayCurrency('GBP');
    store.recordMeta({ nativeCurrency: 'TRY', currency: 'TRY', fx: FX_UNAVAILABLE_GBP });
    expect(store.rateState()).toEqual({ kind: 'unavailable', shownIn: 'TRY' });

    store.setDisplayCurrency('EUR');
    store.recordMeta({
      nativeCurrency: 'TRY',
      currency: 'EUR',
      fx: { ...FX_STALE_EUR, stale: false, asOf: '2026-11-24' },
    });
    expect(store.rateState()).toEqual({ kind: 'rate', asOf: '2026-11-24' });

    store.recordMeta({ nativeCurrency: 'TRY', currency: 'TRY', fx: FX_IDENTITY_TRY });
    expect(store.rateState()).toEqual({ kind: 'unavailable', shownIn: 'TRY' });
  });

  it('retries loading the currency list after a failure', async () => {
    api.listFxCurrencies.mockReturnValueOnce(
      throwError(() => new ApiProblem({ kind: 'network', status: 0, code: null })),
    );
    const store = create();
    await store.loadCurrencies();
    expect(store.currencies()).toEqual([]);
    await store.loadCurrencies();
    expect(store.currencies()).toHaveLength(4);
  });
});

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { convertToParamMap } from '@angular/router';

import { DEFAULT_QUERY, QueryState, QueryStore, parseQuery, serializeQuery } from './query.store';
import { RegionStore } from './region.store';

const FULL: QueryState = {
  country: 'TR',
  stockFilter: 'INCLUDE_OUT_OF_STOCK',
  mode: 'CUSTOM',
  lambda: 0.65,
  categories: { VITAMIN: 1.2, COENZYME: 0 },
  overrides: { VIT_D: 1.5, ZINC: 0, MAGNESIUM: 2 },
  required: [{ code: 'VIT_C', minAmountPerDay: 300, unit: 'mg' }, { code: 'ZINC' }],
  excludeExceedingUl: true,
  page: 3,
  pageSize: 50,
};

describe('query URL serialisation', () => {
  it('round-trips every field through the URL query string', () => {
    const params = serializeQuery(FULL);
    const query = new URLSearchParams(params).toString();
    const restored = parseQuery(convertToParamMap(Object.fromEntries(new URLSearchParams(query))));
    expect(restored).toEqual({ ...FULL, overrides: { MAGNESIUM: 2, VIT_D: 1.5, ZINC: 0 } });
  });

  it('omits defaults so plain links stay short', () => {
    expect(serializeQuery({ ...DEFAULT_QUERY, country: 'DE' })).toEqual({ country: 'DE' });
    expect(serializeQuery(DEFAULT_QUERY)).toEqual({});
  });

  it('writes a readable, stable encoding', () => {
    expect(serializeQuery(FULL)).toEqual({
      country: 'TR',
      stock: 'all',
      mode: 'CUSTOM',
      lambda: '0.65',
      w: 'VITAMIN:1.2,COENZYME:0',
      o: 'MAGNESIUM:2,VIT_D:1.5,ZINC:0',
      req: 'VIT_C:300:mg,ZINC',
      ul: 'exclude',
      page: '3',
      size: '50',
    });
  });

  it('drops the lambda outside CUSTOM mode', () => {
    expect(serializeQuery({ ...FULL, mode: 'VALUE' })['lambda']).toBeUndefined();
    expect(parseQuery({ mode: 'PRICE', lambda: '0.3' }).lambda).toBeNull();
  });

  it('ignores malformed parameters and keeps defaults', () => {
    const state = parseQuery({
      country: 'turkey',
      stock: 'maybe',
      mode: 'BEST',
      w: 'VITAMIN:abc,UNKNOWN:1,MINERAL:1.5,broken',
      o: 'vit_c:1,ZINC:,IRON:Infinity,VIT_E:0.5',
      req: 'VIT_C:300:kg,,VIT_C,bad code,ZINC:5:mcg',
      ul: 'yes',
      page: '-2',
      size: '1000',
    });
    expect(state).toEqual({
      ...DEFAULT_QUERY,
      categories: { MINERAL: 1.5 },
      overrides: { VIT_E: 0.5 },
      required: [{ code: 'VIT_C' }, { code: 'ZINC', minAmountPerDay: 5, unit: 'mcg' }],
      pageSize: 100,
    });
  });

  it('accepts a plain params object', () => {
    expect(parseQuery({ country: 'US', page: '2' })).toEqual({
      ...DEFAULT_QUERY,
      country: 'US',
      page: 2,
    });
  });
});

describe('QueryStore', () => {
  let store: QueryStore;
  const regionCountry = signal<string | null>('DE');

  beforeEach(() => {
    regionCountry.set('DE');
    TestBed.configureTestingModule({
      providers: [
        {
          provide: RegionStore,
          useValue: {
            countryCode: regionCountry,
            setCountry: (code: string) => regionCountry.set(code),
          },
        },
      ],
    });
    store = TestBed.inject(QueryStore);
  });

  it('falls back to the visitor country and includes it in the URL', () => {
    expect(store.country()).toBe('DE');
    expect(store.queryParams()).toEqual({ country: 'DE' });
    store.patch({ country: 'TR' });
    expect(store.queryParams()).toEqual({ country: 'TR' });
  });

  it('restores state from the URL', () => {
    store.restore(convertToParamMap(serializeQuery(FULL)));
    expect(store.state()).toEqual(parseQuery(serializeQuery(FULL)));
  });

  it('makes the country of a restored link the visitor country', () => {
    store.restore({ country: 'TR' });
    expect(regionCountry()).toBe('TR');
    store.restore({ mode: 'PRICE' });
    expect(regionCountry()).toBe('TR');
    expect(store.country()).toBe('TR');
  });

  it('resets to page 1 on any change but a page change, and clears lambda outside CUSTOM', () => {
    store.restore({ mode: 'CUSTOM', lambda: '0.4', page: '4' });
    store.patch({ page: 5 });
    expect(store.state().page).toBe(5);
    store.patch({ stockFilter: 'INCLUDE_OUT_OF_STOCK' });
    expect(store.state().page).toBe(1);
    expect(store.state().lambda).toBe(0.4);
    store.patch({ mode: 'VALUE' });
    expect(store.state().lambda).toBeNull();
    store.reset();
    expect(store.state()).toEqual(DEFAULT_QUERY);
  });

  it('builds the recommendation request from the state', () => {
    store.restore(serializeQuery(FULL));
    expect(store.toRequest('USD')).toEqual({
      country: 'TR',
      stockFilter: 'INCLUDE_OUT_OF_STOCK',
      ranking: { mode: 'CUSTOM', lambda: 0.65 },
      weights: {
        categories: { VITAMIN: 1.2, COENZYME: 0 },
        overrides: { MAGNESIUM: 2, VIT_D: 1.5, ZINC: 0 },
      },
      requiredNutrients: [{ code: 'VIT_C', minAmountPerDay: 300, unit: 'mg' }, { code: 'ZINC' }],
      excludeExceedingUl: true,
      displayCurrency: 'USD',
      page: 3,
      pageSize: 50,
    });
  });

  it('builds a minimal request with defaults', () => {
    expect(store.toRequest()).toEqual({
      country: 'DE',
      stockFilter: 'IN_STOCK_ONLY',
      ranking: { mode: 'VALUE' },
      excludeExceedingUl: false,
      page: 1,
      pageSize: 20,
    });
  });

  it('builds the compare request with the same market and weights', () => {
    store.restore({ country: 'TR', o: 'VIT_C:1.5', stock: 'all' });
    expect(store.toCompareRequest('a', 'b', 'EUR')).toEqual({
      country: 'TR',
      baselineProductId: 'a',
      candidateProductId: 'b',
      stockFilter: 'INCLUDE_OUT_OF_STOCK',
      weights: { overrides: { VIT_C: 1.5 } },
      displayCurrency: 'EUR',
    });
  });

  it('returns no request while no country is known', () => {
    regionCountry.set(null);
    expect(store.toRequest()).toBeNull();
    expect(store.toCompareRequest('a', 'b')).toBeNull();
  });
});

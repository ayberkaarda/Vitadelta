import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { RegionStore } from '../state/region.store';
import {
  Formatter,
  MINUS,
  formatAmount,
  formatCountryName,
  formatDate,
  formatDateTime,
  formatMoney,
  formatRate,
  formatShortDate,
  formatSigned,
} from './formatter';

describe('format helpers', () => {
  it('formats money with 2 decimals in the locale', () => {
    expect(formatMoney('en-US', 13.3333, 'EUR')).toBe('€13.33');
    expect(formatMoney('tr-TR', 1234.5, 'TRY')).toBe('₺1.234,50');
  });

  it('formats amounts, rates and signed deltas', () => {
    expect(formatAmount('en-US', 540)).toBe('540.00');
    expect(formatAmount('en-US', 5.00931, 4)).toBe('5.0093');
    expect(formatRate('en-US', 0.0225)).toBe('0.02250000');
    expect(formatSigned('en-US', 0.0374)).toBe('+0.0374');
    expect(formatSigned('en-US', -0.2667)).toBe(`${MINUS}0.2667`);
    expect(formatSigned('en-US', 0)).toBe('0');
    expect(formatSigned('en-US', -0.00001)).toBe('0');
    expect(formatSigned('tr-TR', -1.5, 2)).toBe(`${MINUS}1,50`);
  });

  it('formats dates in UTC and keeps unparseable values', () => {
    expect(formatDate('en-GB', '2026-11-24')).toBe('24 Nov 2026');
    expect(formatDate('en-US', '2026-11-20T09:00:00Z')).toBe('Nov 20, 2026');
    expect(formatDate('en-US', 'not a date')).toBe('not a date');
    expect(formatDateTime('en-US', 'nope')).toBe('nope');
    expect(formatShortDate('en-GB', '2026-11-17')).toBe('17 Nov');
    expect(formatShortDate('tr-TR', '2026-11-17')).toBe('17 Kas');
    expect(formatShortDate('en-GB', 'x')).toBe('x');
    expect(formatDateTime('en-US', '2026-11-20T09:00:00Z')).toContain('2026');
  });
});

describe('Formatter', () => {
  it('follows the active locale of the region store', () => {
    const locale = signal('en-US');
    TestBed.configureTestingModule({
      providers: [{ provide: RegionStore, useValue: { intlLocale: locale } }],
    });
    const format = TestBed.inject(Formatter);
    expect(format.money(9, 'TRY')).toMatch(/^TRY\s9\.00$/);
    expect(format.integer(67.6)).toBe('68');
    expect(format.amount(2.5)).toBe('2.50');
    expect(format.rate(1)).toBe('1.00000000');
    expect(format.signed(0.5, 1)).toBe('+0.5');
    expect(format.date('2026-11-24')).toBe('Nov 24, 2026');
    expect(format.shortDate('2026-11-24')).toBe('Nov 24');
    expect(format.dateTime('2026-11-24T08:00:00Z')).toContain('2026');
    expect(format.countryName('DE', 'Germany')).toBe('Germany');
    locale.set('tr-TR');
    expect(format.money(9, 'TRY')).toBe('₺9,00');
    expect(format.countryName('DE', 'Germany')).toBe('Almanya');
  });
});

describe('formatCountryName', () => {
  it('names countries in Turkish for a Turkish locale', () => {
    expect(formatCountryName('tr-TR', 'TR', 'Türkiye')).toBe('Türkiye');
    expect(formatCountryName('tr-TR', 'DE', 'Germany')).toBe('Almanya');
    expect(formatCountryName('tr-DE', 'GB', 'United Kingdom')).toBe('Birleşik Krallık');
    expect(formatCountryName('tr', 'US', 'United States')).toBe('Amerika Birleşik Devletleri');
  });

  it('names countries in English for an English locale', () => {
    expect(formatCountryName('en-US', 'TR', 'Türkiye')).toBe('Türkiye');
    expect(formatCountryName('en-TR', 'DE', 'Germany')).toBe('Germany');
    expect(formatCountryName('en-GB', 'GB', 'UK')).toBe('United Kingdom');
    expect(formatCountryName('en', 'US', 'USA')).toBe('United States');
  });

  it('falls back to the API name, then the code, when no localized name exists', () => {
    expect(formatCountryName('tr-TR', 'AA', 'Synthetic Land')).toBe('Synthetic Land');
    expect(formatCountryName('tr-TR', 'de', 'Germany')).toBe('Germany');
    expect(formatCountryName('not a locale!', 'DE', 'Germany')).toBe('Germany');
    expect(formatCountryName('tr-TR', 'QX')).toBe('QX');
    expect(formatCountryName('tr-TR', null, null)).toBe('');
    expect(formatCountryName('tr-TR', undefined)).toBe('');
  });
});

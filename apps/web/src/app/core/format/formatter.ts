import { Injectable, computed, inject } from '@angular/core';

import { RegionStore } from '../state/region.store';

/** U+2212 MINUS SIGN, used for negative deltas in tables and bars. */
export const MINUS = '−';

/** Display rule of the UI: 2 fraction digits for money (API values carry 4). */
export function formatMoney(locale: string, amount: number, currency: string): string {
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

/** Plain amount with `digits` fraction digits (no currency sign), e.g. "540.00". */
export function formatAmount(locale: string, amount: number, digits = 2): string {
  return new Intl.NumberFormat(locale, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(amount);
}

/** FX rate with its stored 8 decimals. */
export function formatRate(locale: string, rate: number): string {
  return formatAmount(locale, rate, 8);
}

/** Signed number with a real minus sign; zero is rendered without a sign. */
export function formatSigned(locale: string, value: number, digits = 4): string {
  const rounded = Number(value.toFixed(digits));
  if (rounded === 0) {
    return formatAmount(locale, 0, 0);
  }
  const body = formatAmount(locale, Math.abs(rounded), digits);
  return rounded > 0 ? `+${body}` : `${MINUS}${body}`;
}

/** Nutrient amount with up to 3 decimals and its canonical unit ("12.5 mcg"). */
export function formatQuantity(locale: string, value: number, unit: string): string {
  const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 3 }).format(value);
  return `${number} ${unit}`;
}

/** Ratio (1 = 100 %) as an integer percent in the locale's own style ("437%", "%437"). */
export function formatPercent(locale: string, ratio: number): string {
  return new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 0 }).format(
    ratio,
  );
}

/** Number with at most `digits` decimals and no padding ("1.5", "7.25", "60"). */
export function formatPlain(locale: string, value: number, digits: number): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: digits }).format(value);
}

/** `YYYY-MM-DD` or an ISO date-time rendered with `dateStyle: 'medium'` in UTC. */
function parseDay(value: string): Date {
  return new Date(/^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T00:00:00Z` : value);
}

export function formatDate(locale: string, value: string): string {
  const date = parseDay(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(date);
}

/** Day and short month ("17 Nov") of a `YYYY-MM-DD` date in UTC, for compact markers. */
export function formatShortDate(locale: string, value: string): string {
  const date = parseDay(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(locale, {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  }).format(date);
}

/** ISO date-time with date and short time in the viewer's time zone (notifications). */
export function formatDateTime(locale: string, value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

/**
 * `Intl` formatting in the active locale (language plus selected country): money, scores,
 * quantities, percentages and dates. Components read the
 * methods inside templates or `computed`s; a locale switch re-renders them.
 */
@Injectable({ providedIn: 'root' })
export class Formatter {
  private readonly region = inject(RegionStore);

  readonly locale = computed(() => this.region.intlLocale());

  money(amount: number, currency: string): string {
    return formatMoney(this.locale(), amount, currency);
  }

  amount(amount: number, digits = 2): string {
    return formatAmount(this.locale(), amount, digits);
  }

  integer(value: number): string {
    return formatAmount(this.locale(), Math.round(value), 0);
  }

  rate(rate: number): string {
    return formatRate(this.locale(), rate);
  }

  signed(value: number, digits = 4): string {
    return formatSigned(this.locale(), value, digits);
  }

  quantity(value: number, unit: string): string {
    return formatQuantity(this.locale(), value, unit);
  }

  /** Coverage or other ratio as an integer percent. */
  percent(ratio: number): string {
    return formatPercent(this.locale(), ratio);
  }

  plain(value: number, digits = 2): string {
    return formatPlain(this.locale(), value, digits);
  }

  /** Weight with one decimal ("1.0", "0.5"). */
  weight(value: number): string {
    return formatAmount(this.locale(), value, 1);
  }

  date(value: string): string {
    return formatDate(this.locale(), value);
  }

  shortDate(value: string): string {
    return formatShortDate(this.locale(), value);
  }

  dateTime(value: string): string {
    return formatDateTime(this.locale(), value);
  }
}

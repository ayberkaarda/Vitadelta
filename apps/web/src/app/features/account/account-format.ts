import { AlertTrigger } from '../../core/api/api-types';

/** Long month name ("November") of a `YYYY-MM-DD` date in UTC. */
export function formatMonth(locale: string, day: string): string {
  const date = new Date(`${day.slice(0, 10)}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) {
    return day;
  }
  return new Intl.DateTimeFormat(locale, { month: 'long', timeZone: 'UTC' }).format(date);
}

/** First day of the month after `periodMonth` (`YYYY-MM-DD`), as `YYYY-MM-DD` (UTC). */
export function nextMonthStart(periodMonth: string): string {
  const date = new Date(`${periodMonth.slice(0, 10)}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) {
    return periodMonth;
  }
  const next = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 1));
  return next.toISOString().slice(0, 10);
}

/** Server order of alert triggers (`SavedProduct.alerts` is sorted the same way). */
export const ALERT_TRIGGERS: readonly AlertTrigger[] = [
  'PRICE_DROP',
  'BACK_IN_STOCK',
  'PRICE_CHANGE',
];

/** Relative change in percent between two prices (display only), `null` without both prices. */
export function percentChange(before: number | null, after: number | null): number | null {
  if (before === null || after === null || before === 0) {
    return null;
  }
  return ((after - before) / before) * 100;
}

/**
 * Parses a threshold typed by the user ("7,5" or "7.5"). Returns `null` for anything that is
 * not a number; the range itself is checked by the API.
 */
export function parseThreshold(text: string): number | null {
  const normalized = text.trim().replace(',', '.');
  if (!/^\d{1,2}(\.\d{1,2})?$/.test(normalized)) {
    return null;
  }
  return Number(normalized);
}

/** Copy of `record` without the entry `key` (records keyed by an id). */
export function withoutKey<T>(
  record: Readonly<Record<string, T>>,
  key: string,
): Readonly<Record<string, T>> {
  return Object.fromEntries(Object.entries(record).filter(([entry]) => entry !== key));
}

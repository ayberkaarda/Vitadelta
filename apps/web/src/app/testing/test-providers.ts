import { Provider, signal } from '@angular/core';

import { RegionStore } from '../core/state/region.store';

/**
 * Replaces the region store with a fixed `Intl` locale so formatting components (money, score
 * bars, dates) render deterministically without loading countries.
 */
export function provideTestLocale(locale = 'en-US'): Provider {
  return { provide: RegionStore, useValue: { intlLocale: signal(locale) } };
}

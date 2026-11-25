import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { FxCurrency, FxMeta } from '../api/api-types';
import { SessionStore } from '../auth/session.store';
import { PreferenceStorage } from './preference-storage';
import { RegionStore } from './region.store';

const CURRENCY_KEY = 'currency';
const CURRENCY_PATTERN = /^[A-Z]{3}$/;

/** Money context of the last API response that carried money (`meta`). */
export interface MoneyContext {
  readonly nativeCurrency: string;
  readonly currency: string;
  readonly fx: FxMeta;
}

/** What the header shows next to the display-currency select. */
export type CurrencyRateState =
  | { readonly kind: 'native' }
  | { readonly kind: 'rate'; readonly asOf: string }
  | { readonly kind: 'stale'; readonly asOf: string }
  | { readonly kind: 'unavailable'; readonly shownIn: string };

/**
 * Display currency and the FX context of the last response (project specification §10.2).
 * `displayCurrency = null` means the country's native currency. The server converts; the
 * client only renders `meta.fx`.
 */
@Injectable({ providedIn: 'root' })
export class CurrencyStore {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly region = inject(RegionStore);
  private readonly storage = inject(PreferenceStorage);

  private currenciesRequested = false;
  private readonly currenciesSignal = signal<readonly FxCurrency[]>([]);
  private readonly chosen = signal<string | null | undefined>(this.readStored());
  private readonly lastMetaSignal = signal<MoneyContext | null>(null);

  /** Display currencies from `/fx/currencies` with their newest rate date. */
  readonly currencies = this.currenciesSignal.asReadonly();
  /** `meta` money context of the last response recorded with {@link recordMeta}. */
  readonly lastMeta = this.lastMetaSignal.asReadonly();

  readonly nativeCurrency = computed(() => this.region.country()?.currency ?? null);

  /** Requested display currency; `null` = native currency of the country. */
  readonly displayCurrency = computed<string | null>(() => {
    const chosen = this.chosen();
    const preferred =
      chosen !== undefined ? chosen : (this.session.user()?.displayCurrency ?? null);
    return preferred !== null && preferred === this.nativeCurrency() ? null : preferred;
  });

  /** Value for the `displayCurrency` request field or query parameter. */
  readonly requestCurrency = computed<string | undefined>(
    () => this.displayCurrency() ?? undefined,
  );

  readonly rateState = computed<CurrencyRateState>(() => {
    const display = this.displayCurrency();
    const native = this.nativeCurrency();
    if (display === null) {
      return { kind: 'native' };
    }
    const meta = this.lastMetaSignal();
    if (meta && meta.fx.quote === display && (native === null || meta.nativeCurrency === native)) {
      if (meta.fx.status === 'UNAVAILABLE' || meta.fx.asOf === null) {
        return { kind: 'unavailable', shownIn: meta.nativeCurrency };
      }
      return meta.fx.stale
        ? { kind: 'stale', asOf: meta.fx.asOf }
        : { kind: 'rate', asOf: meta.fx.asOf };
    }
    const info = this.currenciesSignal().find((entry) => entry.code === display);
    if (!info || info.asOf === null) {
      return { kind: 'unavailable', shownIn: native ?? display };
    }
    return info.stale ? { kind: 'stale', asOf: info.asOf } : { kind: 'rate', asOf: info.asOf };
  });

  /** Loads `/fx/currencies` once. */
  async loadCurrencies(): Promise<void> {
    if (this.currenciesRequested) {
      return;
    }
    this.currenciesRequested = true;
    try {
      const list = await firstValueFrom(this.api.listFxCurrencies());
      this.currenciesSignal.set(list.items);
    } catch {
      this.currenciesRequested = false;
    }
  }

  /** Selects the display currency for this browser (`null` = native). */
  setDisplayCurrency(code: string | null): void {
    if (code !== null && !CURRENCY_PATTERN.test(code)) {
      return;
    }
    this.chosen.set(code);
    this.storage.write(CURRENCY_KEY, code ?? 'NATIVE');
  }

  /** Records `meta` of a money-bearing response so the header can show its rate state. */
  recordMeta(meta: MoneyContext): void {
    this.lastMetaSignal.set({
      nativeCurrency: meta.nativeCurrency,
      currency: meta.currency,
      fx: meta.fx,
    });
  }

  private readStored(): string | null | undefined {
    const value = this.storage.read(CURRENCY_KEY);
    if (value === 'NATIVE') {
      return null;
    }
    return value && CURRENCY_PATTERN.test(value) ? value : undefined;
  }
}

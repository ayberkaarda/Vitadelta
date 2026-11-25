import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { Formatter } from '../core/format/formatter';
import { AVAILABLE_LANGS, AppLang } from '../core/i18n/languages';
import { CurrencyStore } from '../core/state/currency.store';
import { QUERY_PARAM, QueryStore } from '../core/state/query.store';
import { RegionStore } from '../core/state/region.store';
import { IconComponent } from '../shared/icon/icon.component';

let nextId = 0;

/**
 * Country select, display-currency select (with the rate date, stale or unavailable marker)
 * and the language toggle. Used in the header bar and in the mobile menu sheet.
 *
 * Changing the country also updates the query store and, when the current URL carries a
 * `country` parameter (results, compare), that parameter, so the page re-runs its request.
 */
@Component({
  selector: 'vd-region-controls',
  imports: [IconComponent, TranslocoPipe],
  templateUrl: './region-controls.component.html',
  styleUrl: './region-controls.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegionControlsComponent {
  protected readonly region = inject(RegionStore);
  protected readonly currency = inject(CurrencyStore);
  private readonly query = inject(QueryStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly format = inject(Formatter);

  readonly layout = input<'bar' | 'sheet'>('bar');

  protected readonly ids = {
    country: `vd-country-${nextId}`,
    currency: `vd-currency-${nextId}`,
    currencyMeta: `vd-currency-meta-${nextId}`,
    lang: `vd-lang-${nextId++}`,
  };
  protected readonly langs = AVAILABLE_LANGS;

  protected readonly currencyOptions = computed(() => {
    const native = this.currency.nativeCurrency();
    return this.currency
      .currencies()
      .map((entry) => entry.code)
      .filter((code) => code !== native);
  });

  protected readonly rateMeta = computed(() => {
    const state = this.currency.rateState();
    switch (state.kind) {
      case 'native':
        return { kind: state.kind, key: 'shell.currency.native', params: {} };
      case 'rate':
        return {
          kind: state.kind,
          key: 'shell.currency.as_of',
          params: { date: this.format.date(state.asOf) },
        };
      case 'stale':
        return {
          kind: state.kind,
          key: 'shell.currency.stale',
          params: { date: this.format.shortDate(state.asOf) },
        };
      case 'unavailable':
        return {
          kind: state.kind,
          key: 'shell.currency.unavailable',
          params: { currency: state.shownIn },
        };
    }
  });

  protected onCountryChange(event: Event): void {
    const code = (event.target as HTMLSelectElement).value;
    this.region.setCountry(code);
    this.query.patch({ country: code });
    if (this.route.snapshot.queryParamMap.has(QUERY_PARAM.country)) {
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { [QUERY_PARAM.country]: code, [QUERY_PARAM.page]: null },
        queryParamsHandling: 'merge',
      });
    }
  }

  protected onCurrencyChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.currency.setDisplayCurrency(value === '' ? null : value);
  }

  protected setLang(lang: AppLang): void {
    void this.region.setLang(lang);
  }
}

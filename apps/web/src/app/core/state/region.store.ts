import { DOCUMENT, Injectable, computed, effect, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { Country } from '../api/api-types';
import { SessionStore } from '../auth/session.store';
import { AppLang, isAppLang, resolveInitialLang } from '../i18n/languages';
import { PreferenceStorage } from './preference-storage';

const COUNTRY_KEY = 'country';
const LANG_KEY = 'lang';
const COUNTRY_PATTERN = /^[A-Z]{2}$/;

/**
 * Country and language of the current visitor.
 *
 * Country: the choice made in this browser, else the signed-in user's default country, else
 * the first country whose default locale matches the browser language, else the first country.
 *
 * Language: a switch made in this page view, else the signed-in user's `locale`, else a choice
 * stored in this browser, else the selected country's default locale, else the browser language.
 * The active Transloco language and `<html lang>` follow it.
 */
@Injectable({ providedIn: 'root' })
export class RegionStore {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly transloco = inject(TranslocoService);
  private readonly storage = inject(PreferenceStorage);
  private readonly document = inject(DOCUMENT);
  private readonly browserLang: AppLang = resolveInitialLang();

  private countriesRequested = false;
  private readonly countriesSignal = signal<readonly Country[]>([]);
  private readonly chosenCountry = signal<string | null>(this.readStoredCountry());
  private readonly storedLang = signal<AppLang | null>(this.readStoredLang());
  private readonly switchedLang = signal<AppLang | null>(null);

  /** Supported countries from `/countries` (empty until loaded). */
  readonly countries = this.countriesSignal.asReadonly();

  readonly countryCode = computed<string | null>(() => {
    const list = this.countriesSignal();
    const known = (code: string | null | undefined): string | null => {
      if (!code) {
        return null;
      }
      return list.length === 0 || list.some((country) => country.code === code) ? code : null;
    };
    return (
      known(this.chosenCountry()) ??
      known(this.session.user()?.defaultCountryCode) ??
      list.find((country) => country.defaultLocale === this.browserLang)?.code ??
      list[0]?.code ??
      null
    );
  });

  readonly country = computed<Country | null>(
    () => this.countriesSignal().find((country) => country.code === this.countryCode()) ?? null,
  );

  readonly lang = computed<AppLang>(
    () =>
      this.switchedLang() ??
      this.session.user()?.locale ??
      this.storedLang() ??
      this.country()?.defaultLocale ??
      this.browserLang,
  );

  /** BCP 47 locale for `Intl` formatting: language plus the selected country. */
  readonly intlLocale = computed(() => {
    const code = this.countryCode();
    return code ? `${this.lang()}-${code}` : this.lang();
  });

  constructor() {
    effect(() => {
      const lang = this.lang();
      if (this.transloco.getActiveLang() !== lang) {
        this.transloco.setActiveLang(lang);
      }
      this.document.documentElement.lang = lang;
    });
  }

  /** Loads `/countries` once. */
  async loadCountries(): Promise<void> {
    if (this.countriesRequested) {
      return;
    }
    this.countriesRequested = true;
    try {
      const list = await firstValueFrom(this.api.listCountries());
      this.countriesSignal.set(list.items);
    } catch {
      this.countriesRequested = false;
    }
  }

  setCountry(code: string): void {
    if (!COUNTRY_PATTERN.test(code)) {
      return;
    }
    this.chosenCountry.set(code);
    this.storage.write(COUNTRY_KEY, code);
  }

  /** Switches the language; for signed-in users it is also saved as the profile `locale`. */
  async setLang(lang: AppLang): Promise<void> {
    this.switchedLang.set(lang);
    this.storedLang.set(lang);
    this.storage.write(LANG_KEY, lang);
    if (this.session.isAuthenticated() && this.session.user()?.locale !== lang) {
      try {
        const me = await firstValueFrom(this.api.updateMe({ locale: lang }));
        this.session.applyMe(me);
      } catch {
        // The switch still applies to this page view; the profile keeps its previous locale.
        return;
      }
    }
  }

  private readStoredCountry(): string | null {
    const value = this.storage.read(COUNTRY_KEY);
    return value && COUNTRY_PATTERN.test(value) ? value : null;
  }

  private readStoredLang(): AppLang | null {
    const value = this.storage.read(LANG_KEY) ?? undefined;
    return isAppLang(value) ? value : null;
  }
}

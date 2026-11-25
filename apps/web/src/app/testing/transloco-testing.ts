import { ModuleWithProviders } from '@angular/core';
import { Translation, TranslocoTestingModule } from '@jsverse/transloco';

import en from '../../../public/i18n/en.json';
import tr from '../../../public/i18n/tr.json';

/** Translations of one feature scope, e.g. `{ en: resultsEn, tr: resultsTr }`. */
export interface ScopeTranslations {
  readonly en: Translation;
  readonly tr: Translation;
}

/**
 * Transloco for unit tests with the real root translations (and optional feature scopes),
 * preloaded synchronously. Use it in `imports`:
 *
 * ```ts
 * import resultsEn from '../../../../public/i18n/results/en.json';
 * import resultsTr from '../../../../public/i18n/results/tr.json';
 * TestBed.configureTestingModule({
 *   imports: [MyComponent, translocoTesting({ scopes: { results: { en: resultsEn, tr: resultsTr } } })],
 * });
 * ```
 */
export function translocoTesting(
  options: {
    readonly scopes?: Readonly<Record<string, ScopeTranslations>>;
    readonly lang?: 'en' | 'tr';
  } = {},
): ModuleWithProviders<TranslocoTestingModule> {
  const langs: Record<string, Translation> = { en, tr };
  for (const [scope, files] of Object.entries(options.scopes ?? {})) {
    langs[`${scope}/en`] = files.en;
    langs[`${scope}/tr`] = files.tr;
  }
  return TranslocoTestingModule.forRoot({
    langs,
    translocoConfig: {
      availableLangs: ['en', 'tr'],
      defaultLang: options.lang ?? 'en',
      fallbackLang: 'en',
      missingHandler: { logMissingKey: false },
    },
    preloadLangs: true,
  });
}

/** Root English translations, for assertions on rendered text. */
export const EN = en;
/** Root Turkish translations, for assertions on rendered text. */
export const TR = tr;

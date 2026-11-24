import { getBrowserLang } from '@jsverse/transloco';

export const AVAILABLE_LANGS = ['en', 'tr'] as const;

export type AppLang = (typeof AVAILABLE_LANGS)[number];

export const FALLBACK_LANG: AppLang = 'en';

export function isAppLang(value: string | undefined): value is AppLang {
  return AVAILABLE_LANGS.some((lang) => lang === value);
}

export function resolveInitialLang(browserLang: string | undefined = getBrowserLang()): AppLang {
  return isAppLang(browserLang) ? browserLang : FALLBACK_LANG;
}

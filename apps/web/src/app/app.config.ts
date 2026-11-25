import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  isDevMode,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import {
  TitleStrategy,
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
} from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { I18nTitleStrategy } from './core/i18n/i18n-title.strategy';
import { provideI18n } from './core/i18n/provide-i18n';
import { AVAILABLE_LANGS, FALLBACK_LANG, resolveInitialLang } from './core/i18n/languages';
import { TranslocoHttpLoader } from './core/i18n/transloco-http-loader';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' }),
    ),
    { provide: TitleStrategy, useExisting: I18nTitleStrategy },
    provideI18n({
      config: {
        availableLangs: [...AVAILABLE_LANGS],
        defaultLang: resolveInitialLang(),
        fallbackLang: FALLBACK_LANG,
        missingHandler: { useFallbackTranslation: true, logMissingKey: isDevMode() },
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
      },
      loader: TranslocoHttpLoader,
    }),
  ],
};

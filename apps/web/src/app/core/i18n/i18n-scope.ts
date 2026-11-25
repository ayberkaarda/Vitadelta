import { Routes } from '@angular/router';
import { provideTranslocoScope } from '@jsverse/transloco';

/** Route data key holding the translation scope of a feature (read by the title strategy). */
export const I18N_SCOPE_DATA = 'i18nScope';

/**
 * Wraps the routes of a feature so its translation scope (`public/i18n/<scope>/<lang>.json`)
 * is loaded lazily with the feature. Keys of the scope are used with the scope name as prefix:
 * `{{ 'results.title' | transloco }}`. Route `title` values are i18n keys as well.
 *
 * ```ts
 * // src/app/features/results/results.routes.ts
 * export default featureRoutes('results', [
 *   { path: '', title: 'results.page_title', loadComponent: () => import('./results-page.component') },
 * ]);
 * ```
 */
export function featureRoutes(scope: string, children: Routes): Routes {
  return [
    {
      path: '',
      providers: [provideTranslocoScope(scope)],
      data: { [I18N_SCOPE_DATA]: scope },
      children,
    },
  ];
}

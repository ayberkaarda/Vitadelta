import { Routes } from '@angular/router';

/**
 * Top-level routes. Every feature lives in `src/app/features/<feature>/` and exports its routes
 * as the default export of `<feature>.routes.ts` (wrapped in `featureRoutes(scope, …)` so its
 * translation scope loads with it). A feature is registered here with exactly one line,
 * above the not-found fallback:
 *
 *   { path: 'results', loadChildren: () => import('./features/results/results.routes') },
 *
 * Guards for signed-in and admin areas (`authGuard`, `adminGuard`) are applied inside the
 * feature's routes.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', loadChildren: () => import('./features/query/query.routes') },
  { path: 'results', loadChildren: () => import('./features/results/results.routes') },
  { path: 'compare', loadChildren: () => import('./features/compare/compare.routes') },
  { path: 'products', loadChildren: () => import('./features/product/product.routes') },
  { path: 'auth', loadChildren: () => import('./features/auth/auth.routes') },
  { path: 'account', loadChildren: () => import('./features/account/account.routes') },
  { path: 'upgrade', loadChildren: () => import('./features/upgrade/upgrade.routes') },
  { path: 'admin', loadChildren: () => import('./features/admin/admin.routes') },
  { path: 'privacy', loadChildren: () => import('./features/privacy/privacy.routes') },
  {
    path: '**',
    title: 'not_found.page_title',
    loadComponent: () =>
      import('./shell/not-found-page.component').then((m) => m.NotFoundPageComponent),
  },
];

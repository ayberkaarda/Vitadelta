import { adminGuard } from '../../core/auth/guards';
import { featureRoutes } from '../../core/i18n/i18n-scope';

export default featureRoutes('admin', [
  {
    path: '',
    canActivate: [adminGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () => import('./admin-layout.component').then((m) => m.AdminLayoutComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'imports' },
      {
        path: 'imports',
        title: 'admin.imports.page_title',
        loadComponent: () =>
          import('./admin-imports-page.component').then((m) => m.AdminImportsPageComponent),
      },
      {
        path: 'promo-codes',
        title: 'admin.promo.page_title',
        loadComponent: () =>
          import('./admin-promo-codes-page.component').then((m) => m.AdminPromoCodesPageComponent),
      },
      {
        path: 'users',
        title: 'admin.users.page_title',
        loadComponent: () =>
          import('./admin-users-page.component').then((m) => m.AdminUsersPageComponent),
      },
    ],
  },
]);
